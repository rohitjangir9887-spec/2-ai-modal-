package com.phoneuse.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phoneuse.app.AgentRunner
import com.phoneuse.app.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ChatGPT-style realtime duplex conversational voice session.
 *
 * Implements the continuous cycle:
 * IDLE -> LISTENING -> THINKING -> ACTING -> SPEAKING -> LISTENING
 *
 * Key capabilities:
 * 1. Automatic turn-taking without tapping manual Send buttons.
 * 2. Instant barge-in: speaking while Juli is talking cancels TTS immediately.
 * 3. Live streaming partial transcripts and audio-reactive amplitude.
 * 4. Direct phone automation integration via [JuliVoiceCommandCoordinator] & [AgentRunner].
 * 5. High-quality natural Indian female voice output with polite Hindi/Hinglish phrasing.
 */
class LiveVoiceSession(
    private val coordinatorProvider: () -> JuliVoiceCommandCoordinator? = { null },
    private val agentRunnerProvider: () -> AgentRunner? = { null },
) {
    companion object {
        private const val TAG = "LiveVoiceSession"
        val shared by lazy { LiveVoiceSession() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _phase = MutableStateFlow(LiveVoicePhase.IDLE)
    val phase: StateFlow<LiveVoicePhase> = _phase.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _lastUserSpeech = MutableStateFlow("")
    val lastUserSpeech: StateFlow<String> = _lastUserSpeech.asStateFlow()

    private val _lastAssistantReply = MutableStateFlow("")
    val lastAssistantReply: StateFlow<String> = _lastAssistantReply.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0.0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var activeContext: Context? = null
    private var recognizer: VoiceRecognizer? = null
    private var speakingPulseJob: Job? = null
    private val isLiveActive = AtomicBoolean(false)

    init {
        // Wire callbacks to VoiceSessionManager.shared
        VoiceSessionManager.shared.attachCallbacks(
            onStopSpeech = { interruptSpeech() },
            onStartListening = { triggerListening() },
        )
    }

    fun startSession(context: Context, conversationId: Long? = null) {
        activeContext = context.applicationContext
        _activeConversationId.value = conversationId
        _errorMessage.value = null
        _partialTranscript.value = ""
        isLiveActive.set(true)
        VoiceSessionManager.shared.setContinuousMode(true)

        initRecognizer(context)
        triggerListening()
        Log.i(TAG, "Live duplex voice session started (conversationId=$conversationId)")
    }

    fun stopSession() {
        isLiveActive.set(false)
        stopSpeakingPulse()
        recognizer?.destroy()
        recognizer = null
        KokoroVoiceEngine.stop()
        VoiceSessionManager.shared.stopSession()
        _phase.value = LiveVoicePhase.IDLE
        _partialTranscript.value = ""
        _audioAmplitude.value = 0.0f
        Log.i(TAG, "Live duplex voice session stopped")
    }

    fun toggleMute(): Boolean {
        val next = !_isMuted.value
        _isMuted.value = next
        if (next) {
            KokoroVoiceEngine.stop()
            recognizer?.stop()
            _phase.value = LiveVoicePhase.IDLE
        } else {
            triggerListening()
        }
        return next
    }

    /**
     * Instant Barge-in: stops any active speech playback immediately
     * and re-arms listening for the user's interruption.
     */
    fun interruptSpeech() {
        stopSpeakingPulse()
        KokoroVoiceEngine.stop()
        VoiceSessionManager.shared.bargeIn()
        _phase.value = LiveVoicePhase.LISTENING
        _audioAmplitude.value = 0.0f
        triggerListening()
        Log.d(TAG, "Barge-in triggered: speech stopped, listening resumed")
    }

    private fun initRecognizer(context: Context) {
        recognizer?.destroy()
        recognizer = VoiceRecognizer(
            context = context,
            onText = { text -> onSpeechFinal(text) },
            onPartial = { partial -> onSpeechPartial(partial) },
            onInterrupted = { heard -> onSpeechFinal(heard) },
            onAudioActivity = {
                // User started speaking while assistant was talking -> barge-in
                if (_phase.value == LiveVoicePhase.SPEAKING) {
                    interruptSpeech()
                }
            },
        )
    }

    private fun triggerListening() {
        if (!isLiveActive.get() || _isMuted.value) return
        stopSpeakingPulse()
        _phase.value = LiveVoicePhase.LISTENING
        _partialTranscript.value = ""

        val ctx = activeContext ?: return
        if (recognizer == null) {
            initRecognizer(ctx)
        }

        try {
            recognizer?.start(languageTag = "hi-IN", languageHints = listOf("hi-IN", "en-IN", "en-US"))
        } catch (e: Exception) {
            Log.w(TAG, "Recognizer start listening error", e)
            _errorMessage.value = e.message
        }
    }

    private fun onSpeechPartial(partial: String) {
        if (!isLiveActive.get()) return
        _partialTranscript.value = partial
        // Live audio level simulation from partial speech activity
        _audioAmplitude.value = (0.3f + (partial.length % 5) * 0.12f).coerceIn(0.2f, 0.95f)
    }

    private fun onSpeechFinal(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            // Re-arm listening if no speech was heard
            if (isLiveActive.get() && !_isMuted.value) {
                triggerListening()
            }
            return
        }

        Log.i(TAG, "Spoken voice input received: '$cleaned'")
        _lastUserSpeech.value = cleaned
        _partialTranscript.value = cleaned
        _phase.value = LiveVoicePhase.THINKING
        _audioAmplitude.value = 0.1f

        processSpokenInput(cleaned)
    }

    private fun processSpokenInput(input: String) {
        val coordinator = coordinatorProvider() ?: JuliVoiceCommandCoordinator(
            context = activeContext,
            agentRunner = agentRunnerProvider(),
            voiceSessionManager = VoiceSessionManager.shared,
        )

        val command = coordinator.classify(input)
        Log.i(TAG, "Voice input classified as: ${command.type} (prompt='${command.cleanedPrompt}')")

        // Handle direct task actions (STOP, PAUSE, RESUME, MODIFY, REPLACE, QUEUE, ANSWER)
        when (command.type) {
            VoiceCommandType.STOP -> {
                agentRunnerProvider()?.stop()
                speakReply(JuliPersonality.onTaskStopped())
            }

            VoiceCommandType.PAUSE -> {
                agentRunnerProvider()?.taskQueue?.setPaused(true)
                speakReply(JuliPersonality.onTaskPaused())
            }

            VoiceCommandType.RESUME -> {
                agentRunnerProvider()?.taskQueue?.setPaused(false)
                speakReply(JuliPersonality.onTaskResumed())
            }

            VoiceCommandType.MODIFY -> {
                val reply = JuliPersonality.onTaskModified(command.cleanedPrompt)
                speakReply(reply)
                dispatchToRunner(command.cleanedPrompt)
            }

            VoiceCommandType.REPLACE -> {
                agentRunnerProvider()?.stop()
                val reply = JuliPersonality.onTaskReplaced(command.cleanedPrompt)
                speakReply(reply)
                dispatchToRunner(command.cleanedPrompt)
            }

            VoiceCommandType.QUEUE -> {
                val convId = _activeConversationId.value
                agentRunnerProvider()?.taskQueue?.enqueue(convId, command.cleanedPrompt, Source.ASSIST)
                speakReply(JuliPersonality.onTaskQueued(command.cleanedPrompt))
            }

            VoiceCommandType.ANSWER -> {
                speakReply(JuliPersonality.onAnswerAcknowledged(command.cleanedPrompt))
            }

            VoiceCommandType.START -> {
                // Check if user is asking conversational question or ordering a phone task
                val prompt = command.cleanedPrompt.ifBlank { input }
                val isPhoneTask = command.targetApp != null || isAutomationRequest(prompt)

                if (isPhoneTask) {
                    _phase.value = LiveVoicePhase.ACTING
                    val ack = JuliPersonality.onTaskStarted(prompt, command.targetApp)
                    speakReply(ack) {
                        dispatchToRunner(prompt)
                    }
                } else {
                    // Conversational AI reasoning
                    dispatchToRunner(prompt)
                }
            }
        }
    }

    private fun isAutomationRequest(text: String): Boolean {
        val lower = text.lowercase()
        val automationTriggers = listOf(
            "kholo", "open", "search", "dhundo", "bhejo", "send", "call", "download",
            "play", "chalao", "scroll", "click", "tap", "type", "likho", "karo", "laga do",
            "खोलो", "सर्च", "ढूंढो", "भेजो", "कॉल", "डाउनलोड", "चलाओ", "करो"
        )
        return automationTriggers.any { lower.contains(it) }
    }

    private fun dispatchToRunner(text: String) {
        val runner = agentRunnerProvider() ?: return
        val convId = _activeConversationId.value
        runner.send(convId, text, Source.ASSIST)
    }

    fun speakReply(text: String, onAfterSpoken: (() -> Unit)? = null) {
        _lastAssistantReply.value = text
        _phase.value = LiveVoicePhase.SPEAKING
        VoiceSessionManager.shared.onReplySpeaking(willSpeak = true)
        startSpeakingPulse()

        val ctx = activeContext
        if (ctx == null) {
            onPlaybackDone(onAfterSpoken)
            return
        }

        KokoroVoiceEngine.speak(
            context = ctx,
            text = text,
            languageTag = "hi-IN",
            onDone = {
                mainHandler.post {
                    onPlaybackDone(onAfterSpoken)
                }
            },
        )
    }

    private fun onPlaybackDone(onAfterSpoken: (() -> Unit)?) {
        stopSpeakingPulse()
        VoiceSessionManager.shared.onSpeechCompleted()
        onAfterSpoken?.invoke()

        // Continuous turn-taking: automatically resume listening after speaking ends
        if (isLiveActive.get() && !_isMuted.value) {
            _phase.value = LiveVoicePhase.LISTENING
            triggerListening()
        } else {
            _phase.value = LiveVoicePhase.IDLE
        }
    }

    private fun startSpeakingPulse() {
        stopSpeakingPulse()
        speakingPulseJob = scope.launch {
            var tick = 0
            while (isActive && _phase.value == LiveVoicePhase.SPEAKING) {
                tick++
                // Organic breathing / wave pulse between 0.35 and 0.85
                val level = 0.35f + 0.5f * kotlin.math.abs(kotlin.math.sin(tick * 0.25f))
                _audioAmplitude.value = level
                delay(60)
            }
        }
    }

    private fun stopSpeakingPulse() {
        speakingPulseJob?.cancel()
        speakingPulseJob = null
    }
}
