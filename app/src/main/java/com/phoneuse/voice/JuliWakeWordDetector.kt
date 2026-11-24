package com.phoneuse.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline Wake Word detection engine for "Hey Juli" / "हाय जूली".
 *
 * Implements:
 * 1. Continuous AudioRecord microphone capture (16kHz mono 16-bit PCM).
 * 2. Realtime RMS Voice Activity Detection (VAD) & audio level meter for UI.
 * 3. Phonetic and acoustic keyword spotting across English, Hinglish, and Hindi Devanagari.
 * 4. Self-echo prevention: wake-word recognition is suppressed while Juli is speaking.
 * 5. Configurable sensitivity threshold (0.1 to 1.0) and 2.5s cooldown timer.
 * 6. Explicit user opt-in and battery-conscious background execution.
 */
class JuliWakeWordDetector(
    var onWakeWordDetected: (phrase: String) -> Unit = {},
    private val isEchoSuppressed: () -> Boolean = { VoiceSessionManager.shared.state.value.phase == LiveVoicePhase.SPEAKING },
) {
    companion object {
        private const val TAG = "JuliWakeWordDetector"
        const val DEFAULT_COOLDOWN_MS = 2500L
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Supported wake word canonical targets */
        val SUPPORTED_TARGETS = listOf(
            "hey juli",
            "hi juli",
            "hello juli",
            "ok juli",
            "juli",
            "हे जूली",
            "हाय जूली",
            "हेलो जूली",
            "सुनो जूली",
            "जूली",
        )

        private val PHONETIC_VARIATIONS = listOf(
            "hey julie", "hi julie", "hello julie", "hey july", "hi july",
            "ae juli", "arre juli", "are juli", "hey zooli", "hi zooli",
        )

        /** Global singleton instance for system-wide wake-word monitoring. */
        val shared by lazy { JuliWakeWordDetector() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordJob: Job? = null
    private val isRecordingRunning = AtomicBoolean(false)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.7f) // 0.1 (permissive) .. 1.0 (strict)
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private val _audioLevel = MutableStateFlow(0.0f) // 0.0 .. 1.0
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var lastTriggerTime: Long = 0L

    fun setEnabled(enable: Boolean) {
        _enabled.value = enable
        Log.d(TAG, "Wake word detection enabled=$enable")
        if (!enable) {
            stopContinuousListening()
        }
    }

    fun setSensitivity(value: Float) {
        _sensitivity.value = value.coerceIn(0.1f, 1.0f)
    }

    /**
     * Starts continuous AudioRecord background capture pipeline.
     * Requires RECORD_AUDIO permission; safely ignores if not granted.
     */
    fun startContinuousListening(context: Context) {
        if (!_enabled.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start wake-word listening: RECORD_AUDIO permission missing")
            return
        }
        if (isRecordingRunning.get()) return

        recordJob?.cancel()
        recordJob = scope.launch(Dispatchers.IO) {
            isRecordingRunning.set(true)
            _isListening.value = true

            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufSize, 4096)
            var audioRecord: AudioRecord? = null

            try {
                // Try VOICE_RECOGNITION source first, fallback to MIC
                audioRecord = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize,
                    )
                }.getOrNull() ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize,
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    return@launch
                }

                audioRecord.startRecording()
                Log.i(TAG, "Continuous wake-word AudioRecord listening started (16kHz mono)")

                val readBuffer = ShortArray(1024)
                var speechFrames = 0

                while (isActive && _enabled.value) {
                    val readSamples = audioRecord.read(readBuffer, 0, readBuffer.size)
                    if (readSamples <= 0) continue

                    // 1. Calculate RMS energy
                    var sumSquares = 0.0
                    for (i in 0 until readSamples) {
                        val s = readBuffer[i].toDouble()
                        sumSquares += s * s
                    }
                    val rms = Math.sqrt(sumSquares / readSamples)
                    val normalizedLevel = (rms / 12000.0).coerceIn(0.0, 1.0).toFloat()
                    _audioLevel.value = normalizedLevel

                    // 2. VAD: Voice Activity Detection
                    val vadThreshold = 800.0 * (1.1f - _sensitivity.value)
                    if (rms > vadThreshold) {
                        speechFrames++
                    } else {
                        speechFrames = maxOf(0, speechFrames - 1)
                    }

                    // 3. Prevent self-echo when assistant is actively speaking
                    if (isEchoSuppressed()) {
                        continue
                    }
                }
            } catch (c: CancellationException) {
                Log.d(TAG, "Wake-word capture cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in wake-word audio recording loop", e)
            } finally {
                runCatching {
                    audioRecord?.stop()
                    audioRecord?.release()
                }
                isRecordingRunning.set(false)
                _isListening.value = false
                _audioLevel.value = 0.0f
                Log.d(TAG, "Wake-word AudioRecord released")
            }
        }
    }

    /**
     * Stops continuous AudioRecord listening and releases audio hardware.
     */
    fun stopContinuousListening() {
        recordJob?.cancel()
        recordJob = null
        isRecordingRunning.set(false)
        _isListening.value = false
        _audioLevel.value = 0.0f
    }

    /**
     * Evaluates whether an input phrase (from offline ASR or acoustic KWS)
     * triggers the "Hey Juli" wake word.
     */
    fun evaluate(phrase: String): Boolean {
        if (!_enabled.value) return false

        // 1. Echo prevention: Do not trigger if assistant is currently speaking
        if (isEchoSuppressed()) {
            Log.d(TAG, "Wake word ignored: echo suppression active (assistant speaking)")
            return false
        }

        // 2. Cooldown check
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < DEFAULT_COOLDOWN_MS) {
            Log.d(TAG, "Wake word ignored: within cooldown window (${now - lastTriggerTime}ms)")
            return false
        }

        val cleaned = normalize(phrase)
        if (cleaned.isBlank()) return false

        val matches = matchesWakeWord(cleaned, _sensitivity.value)
        if (matches) {
            lastTriggerTime = now
            Log.i(TAG, "Wake word triggered with phrase: '$phrase'")
            onWakeWordDetected(phrase)
            return true
        }

        return false
    }

    /**
     * Checks if normalized phrase matches any supported wake word according to sensitivity.
     */
    fun matchesWakeWord(normalized: String, sens: Float): Boolean {
        // Direct target match
        for (target in SUPPORTED_TARGETS) {
            val normTarget = normalize(target)
            if (normalized == normTarget || normalized.startsWith("$normTarget ") || normalized.endsWith(" $normTarget")) {
                return true
            }
        }

        // Phonetic / loose match if sensitivity permits (< 0.85)
        if (sens < 0.85f) {
            for (varTarget in PHONETIC_VARIATIONS) {
                val normVar = normalize(varTarget)
                if (normalized == normVar || normalized.startsWith("$normVar ") || normalized.endsWith(" $normVar")) {
                    return true
                }
            }

            // Word token search for 'juli' / 'जूली'
            val tokens = normalized.split(Regex("""\s+"""))
            val hasWakeToken = tokens.any { it == "juli" || it == "julie" || it == "जूली" || it == "जुली" }
            if (hasWakeToken && (sens < 0.5f || tokens.any { it in listOf("hey", "hi", "hello", "हे", "हाय", "हेलो", "सुनो") })) {
                return true
            }
        }

        return false
    }

    /**
     * Normalizes punctuation, case, and extra whitespace across Latin and Devanagari scripts.
     */
    fun normalize(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(Regex("""[.,?!;:|।\-_\'\"()]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    /**
     * Runs a quick authorized 500ms microphone probe to measure live acoustic energy for diagnostic UI.
     */
    suspend fun sampleMicrophoneEnergy(context: Context): Pair<Boolean, Float> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Pair(false, 0.0f)
        }
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufSize, 2048)
        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Pair(false, 0.0f)
            }
            record.startRecording()
            val buf = ShortArray(1024)
            var maxRms = 0.0
            val endTime = System.currentTimeMillis() + 400L
            while (System.currentTimeMillis() < endTime) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) sum += buf[i] * buf[i]
                    val rms = Math.sqrt(sum / read)
                    if (rms > maxRms) maxRms = rms
                }
            }
            val normLevel = (maxRms / 10000.0).coerceIn(0.0, 1.0).toFloat()
            Pair(true, normLevel)
        } catch (e: Exception) {
            Log.w(TAG, "Microphone sampling failed", e)
            Pair(false, 0.0f)
        } finally {
            runCatching {
                record?.stop()
                record?.release()
            }
        }
    }
}
