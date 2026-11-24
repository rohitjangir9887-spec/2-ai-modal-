package com.phoneuse.agent

/**
 * Detects repeated model-directed actions against an unchanged UI without executing a
 * potentially destructive retry. No timers or network calls: one guard belongs to one run.
 *
 * A text entry that submits or a send tap may have already caused an external effect that
 * is not visible in the current accessibility tree. Never blindly repeat it.
 */
internal class ActionStallGuard {
    enum class Decision { ALLOW, REPLAN, STOP }

    private data class Attempt(val action: String, val arguments: String, val screen: Int)
    private data class SentRecord(val signature: String, val screenFingerprint: Int)

    private var last: Attempt? = null
    private var repeats = 0
    private val executedSends = ArrayList<SentRecord>()

    fun check(action: String, arguments: String, screenFingerprint: Int?): Decision {
        if (screenFingerprint == null || action !in WATCHED_ACTIONS) return Decision.ALLOW
        val next = Attempt(action, arguments, screenFingerprint)
        repeats = if (last == next) repeats + 1 else 1
        last = next

        // A submitted text message must not be entered twice just because the UI did not
        // update. Uncertain sends need a fresh observation and user-visible recovery.
        val potentiallySent = (action == "type_text" &&
            Regex("\\\"submit\\\"\\s*:\\s*true").containsMatchIn(arguments)) ||
            action == "send_sms"
        val threshold = if (potentiallySent) 2 else 3
        return when {
            repeats >= threshold + 1 -> Decision.STOP
            repeats >= threshold -> Decision.REPLAN
            else -> Decision.ALLOW
        }
    }

    /**
     * Returns true when an equivalent SEND action ([signature]) already executed successfully on this exact
     * [screenFingerprint] (even if `wait`, `get_screen`, `update_plan`, or a model retry ran in between).
     */
    fun isDuplicateSend(signature: String, screenFingerprint: Int?): Boolean {
        if (screenFingerprint == null) return false
        return executedSends.any { it.screenFingerprint == screenFingerprint && it.signature == signature }
    }

    fun recordExecutedSend(signature: String, screenFingerprint: Int?) {
        if (screenFingerprint == null) return
        val record = SentRecord(signature, screenFingerprint)
        if (executedSends.none { it == record }) {
            executedSends += record
            if (executedSends.size > MAX_SENT_RECORDS) {
                executedSends.removeAt(0)
            }
        }
    }

    private companion object {
        const val MAX_SENT_RECORDS = 32
        val WATCHED_ACTIONS = setOf(
            "tap", "long_press", "type_text", "scroll", "swipe", "open_app",
            "open_uri", "wait", "get_screen", "batch", "send_sms",
        )
    }
}
