package com.phoneuse.voice

import android.content.Context
import android.util.Log
import com.phoneuse.app.AgentRunner
import com.phoneuse.app.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Classifies speech commands across Hindi, Hinglish, and English:
 * - START
 * - STOP
 * - PAUSE
 * - RESUME
 * - MODIFY
 * - REPLACE
 * - QUEUE
 * - ANSWER
 */
enum class VoiceCommandType {
    START,
    STOP,
    PAUSE,
    RESUME,
    MODIFY,
    REPLACE,
    QUEUE,
    ANSWER,
    STATUS,
}

data class ClassifiedVoiceCommand(
    val type: VoiceCommandType,
    val rawText: String,
    val cleanedPrompt: String,
    val targetApp: String? = null,
    val actionParameter: String? = null,
    val confidence: Float = 1.0f,
)

/**
 * Single serialized voice-command coordinator for Juli AI.
 * Handles continuous conversation turn-taking, safe task interruption,
 * checkpointing, and direct phone-automation execution.
 */
class JuliVoiceCommandCoordinator(
    private val context: Context? = null,
    private val agentRunner: AgentRunner? = null,
    private val voiceSessionManager: VoiceSessionManager = VoiceSessionManager.shared,
    private val voiceEngine: KokoroVoiceEngine = KokoroVoiceEngine,
    private val personality: JuliPersonality = JuliPersonality,
) {
    companion object {
        private const val TAG = "JuliVoiceCoordinator"

        private val WAKE_PREFIXES = listOf(
            "hey juli", "hi juli", "hello juli", "ok juli", "okay juli", "juli", "julie",
            "हे जूली", "हाय जूली", "हेलो जूली", "सुनो जूली", "जूली", "जुली"
        )

        private val STOP_PATTERNS = listOf(
            "stop", "band karo", "ruko", "rok do", "khatam karo", "cancel karo",
            "shut down", "halt", "काफी है", "बंद करो", "रुको", "रोक दो", "कैंसिल करो"
        )

        private val PAUSE_PATTERNS = listOf(
            "pause", "hold on", "wait", "thodi der ruko", "ek minute ruko",
            "पॉज़ करो", "थोड़ी देर रुको", "एक मिनट रुको", "विराम दो"
        )

        private val RESUME_PATTERNS = listOf(
            "resume", "continue", "go ahead", "aage badho", "phir se shuru karo",
            "purana task resume karo", "चालू करो", "आगे बढ़ो", "दोबारा शुरू करो", "जारी रखो"
        )

        private val MODIFY_PATTERNS = listOf(
            "modify", "change", "badlo", "thoda change karo", "sudhaar karo",
            "बदलो", "सुधार करो", "परिवर्तन करो"
        )

        private val REPLACE_PATTERNS = listOf(
            "replace", "do this instead", "pehle doosra task karo", "ye chhod kar",
            "is task ko badlo", "इसकी जगह", "पहले दूसरा काम करो", "ये छोड़ो"
        )

        private val QUEUE_PATTERNS = listOf(
            "queue", "after this", "baad mein karna", "then do", "next task",
            "कतार में डालो", "इसके बाद", "बाद में करना", "अगला काम"
        )

        private val ANSWER_PATTERNS = listOf(
            "yes", "no", "confirm", "deny", "send", "haan", "theek hai", "nahi",
            "bhej do", "mat bhejo", "हाँ", "ना", "भेज दो", "मत भेजो", "मंजूर है", "स्वीकार"
        )

        private val STATUS_PATTERNS = listOf(
            "kitna kaam", "kya chal raha", "status", "progress", "kitna bacha", "kahan tak hua",
            "कितना काम", "क्या चल रहा", "प्रोग्रेस", "स्टेटस", "कितना हुआ"
        )

        private val APP_ALIASES = mapOf(
            "whatsapp" to "com.whatsapp",
            "व्हाट्सएप" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "यूट्यूब" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "क्रोम" to "com.android.chrome",
            "google" to "com.google.android.googlequicksearchbox",
            "गूगल" to "com.google.android.googlequicksearchbox",
            "settings" to "com.android.settings",
            "सेटिंग्स" to "com.android.settings",
            "messages" to "com.google.android.apps.messaging",
            "मैसेज" to "com.google.android.apps.messaging",
            "camera" to "com.android.camera",
            "कैमरा" to "com.android.camera",
            "photos" to "com.google.android.apps.photos",
            "फोटो" to "com.google.android.apps.photos",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lastCommand = MutableStateFlow<ClassifiedVoiceCommand?>(null)
    val lastCommand: StateFlow<ClassifiedVoiceCommand?> = _lastCommand.asStateFlow()

    /**
     * Strips wake prefixes like "Hey Juli", "Juli", "हे जूली" from the spoken input.
     */
    fun cleanPrompt(input: String): String {
        var text = input.trim()
        val lower = text.lowercase(Locale.ROOT)
        for (prefix in WAKE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }
        return text.trimStart(',', ' ', ':', '-').trim()
    }

    /**
     * Classifies spoken speech into a structured command type.
     */
    fun classify(speechText: String): ClassifiedVoiceCommand {
        val cleaned = cleanPrompt(speechText)
        val lower = cleaned.lowercase(Locale.ROOT)

        // 1. PAUSE (check specific pause phrases before generic stop triggers)
        if (PAUSE_PATTERNS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") || lower.contains(it) }) {
            return ClassifiedVoiceCommand(VoiceCommandType.PAUSE, speechText, cleaned)
        }

        // 2. STOP
        if (STOP_PATTERNS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            return ClassifiedVoiceCommand(VoiceCommandType.STOP, speechText, cleaned)
        }

        // 3. RESUME
        if (RESUME_PATTERNS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            return ClassifiedVoiceCommand(VoiceCommandType.RESUME, speechText, cleaned)
        }

        // 4. MODIFY
        if (MODIFY_PATTERNS.any { lower.contains(it) }) {
            return ClassifiedVoiceCommand(VoiceCommandType.MODIFY, speechText, cleaned)
        }

        // 5. REPLACE
        if (REPLACE_PATTERNS.any { lower.contains(it) }) {
            return ClassifiedVoiceCommand(VoiceCommandType.REPLACE, speechText, cleaned)
        }

        // 5. QUEUE
        if (QUEUE_PATTERNS.any { lower.contains(it) }) {
            return ClassifiedVoiceCommand(VoiceCommandType.QUEUE, speechText, cleaned)
        }

        // 6. ANSWER
        if (ANSWER_PATTERNS.any { lower == it || lower.startsWith("$it ") }) {
            return ClassifiedVoiceCommand(VoiceCommandType.ANSWER, speechText, cleaned)
        }

        // 7. STATUS / PROGRESS
        if (STATUS_PATTERNS.any { lower.contains(it) }) {
            return ClassifiedVoiceCommand(VoiceCommandType.STATUS, speechText, cleaned)
        }

        // 8. START (default actionable prompt)
        var targetApp: String? = null
        for ((alias, pkg) in APP_ALIASES) {
            if (lower.contains(alias)) {
                targetApp = pkg
                break
            }
        }

        return ClassifiedVoiceCommand(
            type = VoiceCommandType.START,
            rawText = speechText,
            cleanedPrompt = cleaned.ifBlank { speechText },
            targetApp = targetApp,
        )
    }

    /**
     * Executes the classified voice command with safe interruption handling.
     */
    fun handleVoiceInput(
        speechText: String,
        conversationId: Long? = null,
        onFeedbackSpoken: ((String) -> Unit)? = null,
    ) {
        val command = classify(speechText)
        _lastCommand.value = command
        Log.i(TAG, "Executing classified voice command: ${command.type} -> '${command.cleanedPrompt}'")

        // 1. Interruption Step: Always immediately silence TTS when new user speech is processed
        voiceEngine.stop()
        voiceSessionManager.bargeIn()

        when (command.type) {
            VoiceCommandType.STOP -> {
                agentRunner?.stop()
                val reply = personality.onTaskStopped()
                speakResponse(reply, onFeedbackSpoken)
            }

            VoiceCommandType.PAUSE -> {
                agentRunner?.taskQueue?.setPaused(true)
                val reply = personality.onTaskPaused()
                speakResponse(reply, onFeedbackSpoken)
            }

            VoiceCommandType.RESUME -> {
                agentRunner?.taskQueue?.setPaused(false)
                val reply = personality.onTaskResumed()
                speakResponse(reply, onFeedbackSpoken)
            }

            VoiceCommandType.MODIFY -> {
                val reply = personality.onTaskModified(command.cleanedPrompt)
                speakResponse(reply, onFeedbackSpoken)
                if (command.cleanedPrompt.isNotBlank()) {
                    agentRunner?.send(conversationId, command.cleanedPrompt, Source.ASSIST)
                }
            }

            VoiceCommandType.REPLACE -> {
                agentRunner?.stop()
                val reply = personality.onTaskReplaced(command.cleanedPrompt)
                speakResponse(reply, onFeedbackSpoken)
                if (command.cleanedPrompt.isNotBlank()) {
                    agentRunner?.send(conversationId, command.cleanedPrompt, Source.ASSIST)
                }
            }

            VoiceCommandType.QUEUE -> {
                if (command.cleanedPrompt.isNotBlank()) {
                    agentRunner?.taskQueue?.enqueue(conversationId, command.cleanedPrompt, Source.ASSIST)
                    val reply = personality.onTaskQueued(command.cleanedPrompt)
                    speakResponse(reply, onFeedbackSpoken)
                }
            }

            VoiceCommandType.ANSWER -> {
                // If a confirmation card or question is pending, answer it
                val reply = personality.onAnswerAcknowledged(command.cleanedPrompt)
                speakResponse(reply, onFeedbackSpoken)
            }

            VoiceCommandType.STATUS -> {
                val isRunning = agentRunner?.running?.value == true
                val curTask = agentRunner?.currentTask?.value.orEmpty()
                val reply = if (isRunning && curTask.isNotBlank()) {
                    "Abhi task chal raha hai: $curTask. Sab theek se complete ho raha hai."
                } else if (isRunning) {
                    "Task chal raha hai, main phone par screen automate kar rahi hoon."
                } else {
                    "Abhi koi task active nahi hai. Aap naya task bol sakte hain."
                }
                speakResponse(reply, onFeedbackSpoken)
            }

            VoiceCommandType.START -> {
                val prompt = command.cleanedPrompt
                if (prompt.isNotBlank()) {
                    val reply = personality.onTaskStarted(prompt, command.targetApp)
                    speakResponse(reply, onFeedbackSpoken)
                    agentRunner?.send(conversationId, prompt, Source.ASSIST)
                }
            }
        }
    }

    private fun speakResponse(text: String, onFeedbackSpoken: ((String) -> Unit)?) {
        onFeedbackSpoken?.invoke(text)
        voiceSessionManager.onReplySpeaking(willSpeak = true)
        val ctx = context
        if (ctx != null) {
            voiceEngine.speak(
                context = ctx,
                text = text,
                languageTag = "hi-IN",
                onDone = {
                    voiceSessionManager.onSpeechCompleted()
                },
            )
        } else {
            voiceSessionManager.onSpeechCompleted()
        }
    }
}
