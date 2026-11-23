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
    private var last: Attempt? = null
    private var repeats = 0

    fun check(action: String, arguments: String, screenFingerprint: Int?): Decision {
        if (screenFingerprint == null || action !in WATCHED_ACTIONS) return Decision.ALLOW
        val next = Attempt(action, arguments, screenFingerprint)
        repeats = if (last == next) repeats + 1 else 1
        last = next

        // A submitted text message must not be entered twice just because the UI did not
        // update. Uncertain sends need a fresh observation and user-visible recovery.
        val potentiallySent = action == "type_text" &&
            Regex("\\\"submit\\\"\\s*:\\s*true").containsMatchIn(arguments)
        val threshold = if (potentiallySent) 2 else 3
        return when {
            repeats >= threshold + 1 -> Decision.STOP
            repeats >= threshold -> Decision.REPLAN
            else -> Decision.ALLOW
        }
    }

    private companion object {
        val WATCHED_ACTIONS = setOf(
            "tap", "long_press", "type_text", "scroll", "swipe", "open_app",
            "open_uri", "wait", "get_screen", "batch",
        )
    }
}
