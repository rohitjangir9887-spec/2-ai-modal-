package com.phoneuse.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phoneuse.app.AgentRunner
import com.phoneuse.app.AppGraph
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
import kotlinx.coroutines.flow.collect
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
    private val agentRunnerProvider: () -> AgentRunner? = { runCatching { AppGraph.runner }.getOrNull() },
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
    private var replyJob: Job? = null
    private var waitingForAgentReply = false
    private var expectedConversationId: Long? = null
    private var speechGeneration = 0L

    fun isSessionActive(): Boolean = isLiveActive.get()

    init {
        // Wire callbacks to VoiceSessionManager.shared
        VoiceSessionManager.shared.attachCallbacks(
            onStopSpeech = { stopSpeechOnly() },
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

        replyJob?.cancel()
        agentRunnerProvider()?.let { runner ->
            replyJob = scope.launch {
                runner.completedReplies.collect { event ->
                    if (!isLiveActive.get() || !waitingForAgentReply) return@collect
                    val expected = expectedConversationId
                    if (expected != null && event.conversationId != expected) return@collect
                    waitingForAgentReply = false
                    expectedConversationId = null
                    if (event.cancelled) {
                        speakReply("काम रोक दिया गया है।")
                    } else {
                        speakReply(event.text?.takeIf(String::isNotBlank)
                            ?: "इस बार AI का जवाब नहीं मिल पाया। कृपया दोबारा कोशिश करें।")
                    }
                }
            }
        }
        initRecognizer(context)
        triggerListening()
        Log.i(TAG, "Live voice session started (conversationId=$conversationId)")
    }

    fun stopSession() {
        isLiveActive.set(false)
        speechGeneration++
        waitingForAgentReply = false
        expectedConversationId = null
        replyJob?.cancel()
        replyJob = null
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
    private fun stopSpeechOnly() {
        speechGeneration++
        stopSpeakingPulse()
        KokoroVoiceEngine.stop()
        _audioAmplitude.value = 0.0f
    }

    fun interruptSpeech() {
        // The manager already calls stopSpeechOnly() then triggerListening(); do not recursively call bargeIn().
        VoiceSessionManager.shared.bargeIn()
        Log.d(TAG, "Barge-in requested")
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
        if (recognizer?.state?.value?.listening == true) return
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
        if (!isLiveActive.get() || _isMuted.value) return
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
                val paused = agentRunnerProvider()?.setPaused(true) == true
                speakReply(if (paused) JuliPersonality.onTaskPaused() else "अभी कोई चालू टास्क नहीं है।")
            }

            VoiceCommandType.RESUME -> {
                val resumed = agentRunnerProvider()?.setPaused(false) == true
                speakReply(if (resumed) JuliPersonality.onTaskResumed() else "अभी कोई रुका हुआ टास्क नहीं है।")
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

            VoiceCommandType.STATUS -> {
                val runner = agentRunnerProvider()
                val isRunning = runner?.running?.value == true
                val curTask = runner?.currentTask?.value.orEmpty()
                val reply = if (isRunning && curTask.isNotBlank()) {
                    "अभी यह टास्क चल रहा है: $curTask. परिणाम की जाँच पूरी नहीं हुई है।"
                } else if (isRunning) {
                    "Task chal raha hai, main phone par screen automate kar rahi hoon."
                } else {
                    "Abhi koi task active nahi hai. Aap naya task bol sakte hain."
                }
                speakReply(reply)
            }

            VoiceCommandType.START -> {
                // Check if user is asking conversational question or ordering a phone task
                val prompt = command.cleanedPrompt.ifBlank { input }
                val isPhoneTask = command.targetApp != null || isAutomationRequest(prompt)

                _phase.value = if (isPhoneTask) LiveVoicePhase.ACTING else LiveVoicePhase.THINKING
                // The real model answer will be spoken from AgentRunner.completedReplies.
                // Do not substitute a canned "starting" sentence for an actual AI response.
                dispatchToRunner(prompt)
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
        val runner = agentRunnerProvider()
        if (runner == null) {
            _errorMessage.value = "AI engine is not initialized"
            speakReply("AI इंजन तैयार नहीं है। ऐप दोबारा खोलकर कोशिश करें।")
            return
        }
        val convId = _activeConversationId.value
        // Live Voice is a user-originated chat request, not an unsolicited side-assistant screen capture.
        if (!runner.send(convId, text, Source.APP)) {
            speakReply("अभी दूसरा टास्क चल रहा है। उसका काम पूरा होने पर फिर कोशिश करें।")
            return
        }
        waitingForAgentReply = true
        expectedConversationId = convId ?: runner.activeConversation.value
    }

    fun speakReply(text: String, onAfterSpoken: (() -> Unit)? = null) {
        if (!isLiveActive.get()) return
        speechGeneration++
        val generation = speechGeneration
        recognizer?.cancel()
        _lastAssistantReply.value = text
        _phase.value = LiveVoicePhase.SPEAKING
        VoiceSessionManager.shared.onReplySpeaking(willSpeak = true)
        startSpeakingPulse()

        val ctx = activeContext
        if (ctx == null) {
            onPlaybackDone(generation, onAfterSpoken)
            return
        }

        KokoroVoiceEngine.speak(
            context = ctx,
            text = text,
            languageTag = "hi-IN",
            onDone = {
                mainHandler.post {
                    onPlaybackDone(generation, onAfterSpoken)
                }
            },
        )
    }

    private fun onPlaybackDone(generation: Long, onAfterSpoken: (() -> Unit)?) {
        if (!isLiveActive.get() || generation != speechGeneration) return
        stopSpeakingPulse()
        // The shared manager invokes triggerListening() once, if continuous mode is on.
        VoiceSessionManager.shared.onSpeechCompleted()
        onAfterSpoken?.invoke()
        if (!VoiceSessionManager.shared.state.value.continuousMode || _isMuted.value) {
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
