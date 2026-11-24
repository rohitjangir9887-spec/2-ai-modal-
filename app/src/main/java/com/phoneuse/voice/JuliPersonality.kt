package com.phoneuse.voice

import java.util.Locale

/**
 * Persona and Indian conversational response generator for JULI AI.
 * Implements warm, respectful, natural tone in Hindi, Hinglish, and Indian English.
 * Provides strict privacy validation to ensure sensitive PII and financial credentials
 * are never stored in memory.
 */
object JuliPersonality {

    enum class Tone {
        POLITE, // "हाँ जी, कर रही हूँ..."
        CASUAL, // "बिल्कुल, अभी करती हूँ..."
    }

    enum class Dialect {
        HINDI,
        HINGLISH,
        INDIAN_ENGLISH,
    }

    var tone: Tone = Tone.POLITE
    var dialect: Dialect = Dialect.HINDI

    // Privacy Patterns for Sensitive Information Filtering
    private val AADHAAR_REGEX = Regex("""\b\d{4}\s?\d{4}\s?\d{4}\b""")
    private val PAN_REGEX = Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]{1}\b""", RegexOption.IGNORE_CASE)
    private val SENSITIVE_KEYWORD_REGEX = Regex(
        """\b(otp|pin|password|passcode|cvv|upi|passphrase|secret|aadhaar|pan)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CARD_NUMBER_REGEX = Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b""")

    /**
     * Checks if a snippet is safe to persist in user memory.
     * Rejects passwords, OTPs, UPI PINs, Aadhaar, PAN, and card numbers.
     */
    fun isSafeToRemember(text: String): Boolean {
        if (text.isBlank()) return false
        if (AADHAAR_REGEX.containsMatchIn(text)) return false
        if (PAN_REGEX.containsMatchIn(text)) return false
        if (CARD_NUMBER_REGEX.containsMatchIn(text)) return false
        if (SENSITIVE_KEYWORD_REGEX.containsMatchIn(text)) return false
        return true
    }

    /**
     * Verifies if a voice command may execute while the device keyguard is locked.
     */
    fun canExecuteOnLockedDevice(command: ClassifiedVoiceCommand, isKeyguardLocked: Boolean): Boolean {
        if (!isKeyguardLocked) return true
        // While phone is locked, destructive or sensitive actions are strictly blocked
        return when (command.type) {
            VoiceCommandType.STOP, VoiceCommandType.PAUSE -> true
            VoiceCommandType.START -> {
                val p = command.cleanedPrompt.lowercase(Locale.ROOT)
                // Only non-sensitive queries are permitted on locked screen
                p.contains("time") || p.contains("samay") || p.contains("battery") || p.contains("weather") || p.contains("mausam")
            }
            else -> false
        }
    }

    fun onTaskStarted(prompt: String, targetApp: String?): String {
        return when (dialect) {
            Dialect.HINDI -> {
                if (targetApp != null && targetApp.contains("whatsapp")) {
                    if (tone == Tone.POLITE) "हाँ जी, व्हाट्सएप खोल रही हूँ।" else "बिल्कुल, व्हाट्सएप ओपन कर रही हूँ।"
                } else if (targetApp != null && targetApp.contains("youtube")) {
                    if (tone == Tone.POLITE) "जी, यूट्यूब शुरू कर रही हूँ।" else "यूट्यूब खोल रही हूँ।"
                } else {
                    if (tone == Tone.POLITE) "जी, आपका काम शुरू कर रही हूँ।" else "ठीक है, अभी करती हूँ।"
                }
            }
            Dialect.HINGLISH -> {
                if (tone == Tone.POLITE) "Haan ji, task start kar rahi hoon." else "Sure, abhi karti hoon."
            }
            Dialect.INDIAN_ENGLISH -> {
                if (tone == Tone.POLITE) "Sure, starting that right away for you." else "On it right away!"
            }
        }
    }

    fun onTaskStopped(): String {
        return when (dialect) {
            Dialect.HINDI -> if (tone == Tone.POLITE) "जी, काम रोक दिया गया है।" else "काम रोक दिया।"
            Dialect.HINGLISH -> "Task stop kar diya gaya hai."
            Dialect.INDIAN_ENGLISH -> "Task has been stopped."
        }
    }

    fun onTaskPaused(): String {
        return when (dialect) {
            Dialect.HINDI -> if (tone == Tone.POLITE) "टास्क पॉज़ कर दिया है। जब चाहें तब बताइएगा।" else "पॉज़ कर दिया।"
            Dialect.HINGLISH -> "Task pause kar diya hai."
            Dialect.INDIAN_ENGLISH -> "Task paused. Let me know when to resume."
        }
    }

    fun onTaskResumed(): String {
        return when (dialect) {
            Dialect.HINDI -> if (tone == Tone.POLITE) "जी, काम दोबारा शुरू कर रही हूँ।" else "काम आगे बढ़ा रही हूँ।"
            Dialect.HINGLISH -> "Task resume kar rahi hoon."
            Dialect.INDIAN_ENGLISH -> "Resuming your task now."
        }
    }

    fun onTaskReplaced(newPrompt: String): String {
        return when (dialect) {
            Dialect.HINDI -> "पुराना काम रोक कर, नया काम शुरू कर रही हूँ।"
            Dialect.HINGLISH -> "Pehle wala task cancel karke naya shuru kar rahi hoon."
            Dialect.INDIAN_ENGLISH -> "Switching to your new request now."
        }
    }

    fun onTaskQueued(newPrompt: String): String {
        return when (dialect) {
            Dialect.HINDI -> "यह काम कतार में जोड़ दिया है। मौजूदा काम के बाद करूँगी।"
            Dialect.HINGLISH -> "Ye task queue mein add kar diya hai."
            Dialect.INDIAN_ENGLISH -> "Added to queue. I will do this next."
        }
    }

    fun onAnswerAcknowledged(answer: String): String {
        return when (dialect) {
            Dialect.HINDI -> "समझ गई, आपका जवाब दर्ज कर लिया है।"
            Dialect.HINGLISH -> "Theek hai, response note kar liya."
            Dialect.INDIAN_ENGLISH -> "Got it, noted your response."
        }
    }

    fun onDeviceLockedBlocker(): String {
        return when (dialect) {
            Dialect.HINDI -> "सुरक्षा के लिए कृपया पहले फोन अनलॉक करें।"
            Dialect.HINGLISH -> "Security ke liye please pehle phone unlock kijiye."
            Dialect.INDIAN_ENGLISH -> "For your security, please unlock your phone first."
        }
    }
}
