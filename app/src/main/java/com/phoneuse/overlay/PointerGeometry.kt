package com.phoneuse.overlay

import kotlin.math.roundToInt

/**
 * Pure screen-coordinate math for the visible AI touch pointer: bounds clamping and
 * display rotation normalization.
 */
object PointerGeometry {
    data class Point(val x: Int, val y: Int)

    /** Clamps ([x], [y]) strictly inside the visible screen bounds `[0, max(0, width-1)] x [0, max(0, height-1)]`. */
    fun clamp(x: Int, y: Int, width: Int, height: Int): Point {
        if (width <= 0 || height <= 0) return Point(x.coerceAtLeast(0), y.coerceAtLeast(0))
        return Point(
            x = x.coerceIn(0, width - 1),
            y = y.coerceIn(0, height - 1),
        )
    }

    /**
     * Maps normalized `[0f..1f]` coordinates back into display pixels and clamps safely.
     */
    fun fromNormalized(nx: Float, ny: Float, width: Int, height: Int): Point {
        val px = (nx.coerceIn(0f, 1f) * width).roundToInt()
        val py = (ny.coerceIn(0f, 1f) * height).roundToInt()
        return clamp(px, py, width, height)
    }

    /**
     * Converts a point recorded in [fromRotation] (`0`, `90`, `180`, `270` degrees clockwise)
     * into the current [toRotation] coordinate frame for a screen whose current pixel dimensions are
     * ([currentWidth], [currentHeight]).
     */
    fun remapRotation(
        x: Int,
        y: Int,
        fromRotation: Int,
        toRotation: Int,
        currentWidth: Int,
        currentHeight: Int,
    ): Point {
        val delta = ((toRotation - fromRotation) % 360 + 360) % 360
        if (delta == 0 || currentWidth <= 0 || currentHeight <= 0) {
            return clamp(x, y, currentWidth, currentHeight)
        }
        val (rx, ry) = when (delta) {
            90 -> (currentWidth - 1 - y) to x
            180 -> (currentWidth - 1 - x) to (currentHeight - 1 - y)
            270 -> y to (currentHeight - 1 - x)
            else -> x to y
        }
        return clamp(rx, ry, currentWidth, currentHeight)
    }
}
