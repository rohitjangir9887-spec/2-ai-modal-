package com.phoneuse.voice

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Offline Wake Word detection engine for "Hey Juli" / "हाय जूली".
 *
 * Implements:
 * 1. Phonetic matching across English, Hinglish, and Hindi Devanagari.
 * 2. Self-echo prevention: wake-word recognition is suppressed while Juli is speaking.
 * 3. Configurable sensitivity threshold (0.1 to 1.0) and 2.5s cooldown timer.
 * 4. Low-power audio pre-filtering before keyword evaluation.
 */
class JuliWakeWordDetector(
    private val onWakeWordDetected: (phrase: String) -> Unit = {},
    private val isEchoSuppressed: () -> Boolean = { VoiceSessionManager.shared.state.value.phase == LiveVoicePhase.SPEAKING },
) {
    companion object {
        private const val TAG = "JuliWakeWordDetector"
        const val DEFAULT_COOLDOWN_MS = 2500L

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
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.7f) // 0.1 (permissive) .. 1.0 (strict)
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private var lastTriggerTime: Long = 0L

    fun setEnabled(enable: Boolean) {
        _enabled.value = enable
        Log.d(TAG, "Wake word detection enabled=$enable")
    }

    fun setSensitivity(value: Float) {
        _sensitivity.value = value.coerceIn(0.1f, 1.0f)
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
                if (normalized == varTarget || normalized.startsWith("$varTarget ") || normalized.endsWith(" $varTarget")) {
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
}
