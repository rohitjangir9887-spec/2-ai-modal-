package com.phoneuse.service

/** Implemented by the overlay manager so gestures and screenshots are not blocked or polluted by it. */
interface OverlayHooks {
    suspend fun <T> passThrough(starts: List<Pair<Int, Int>>, block: suspend () -> T): T
    suspend fun <T> hiddenForCapture(block: suspend () -> T): T

    /** Takes input focus away from the dock so keys and the IME go to the app underneath. */
    suspend fun releaseInputFocus()

    /** Shows where the agent touches: a ripple at ([x], [y]), a trail to ([x2], [y2]) for a swipe. Visual only. */
    fun showTouch(x: Int, y: Int, x2: Int? = null, y2: Int? = null, long: Boolean = false) = Unit

    /** Moves the visible AI pointer to ([x], [y]) before an action executes. */
    fun showPointerMove(x: Int, y: Int, label: String? = null) = Unit

    /**
     * Reports the outcome AFTER dispatching a touch/gesture: animates tap/long-press/swipe when [success] is true,
     * or shows a failed/cancelled cross mark when [success] is false (never faking a click).
     */
    fun showTouchOutcome(
        x: Int,
        y: Int,
        x2: Int? = null,
        y2: Int? = null,
        long: Boolean = false,
        success: Boolean = true,
        label: String? = null,
    ) {
        if (success) showTouch(x, y, x2, y2, long)
    }
}
