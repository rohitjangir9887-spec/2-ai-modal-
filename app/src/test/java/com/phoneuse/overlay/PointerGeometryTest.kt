package com.phoneuse.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerGeometryTest {
    @Test
    fun clampsCoordinatesWithinScreenBounds() {
        assertEquals(PointerGeometry.Point(0, 0), PointerGeometry.clamp(-25, -100, 1080, 2400))
        assertEquals(PointerGeometry.Point(1079, 2399), PointerGeometry.clamp(1200, 3000, 1080, 2400))
        assertEquals(PointerGeometry.Point(540, 1200), PointerGeometry.clamp(540, 1200, 1080, 2400))
    }

    @Test
    fun mapsNormalizedCoordinatesIntoPixels() {
        assertEquals(PointerGeometry.Point(540, 1200), PointerGeometry.fromNormalized(0.5f, 0.5f, 1080, 2400))
        assertEquals(PointerGeometry.Point(0, 0), PointerGeometry.fromNormalized(-0.2f, -1f, 1080, 2400))
        assertEquals(PointerGeometry.Point(1079, 2399), PointerGeometry.fromNormalized(1.4f, 1.1f, 1080, 2400))
    }

    @Test
    fun remapsRotation90And180And270Correctly() {
        val p90 = PointerGeometry.remapRotation(x = 100, y = 200, fromRotation = 0, toRotation = 90, currentWidth = 2400, currentHeight = 1080)
        assertEquals(PointerGeometry.Point(2400 - 1 - 200, 100), p90)

        val p180 = PointerGeometry.remapRotation(x = 100, y = 200, fromRotation = 0, toRotation = 180, currentWidth = 1080, currentHeight = 2400)
        assertEquals(PointerGeometry.Point(1079 - 100, 2399 - 200), p180)

        val p270 = PointerGeometry.remapRotation(x = 100, y = 200, fromRotation = 0, toRotation = 270, currentWidth = 2400, currentHeight = 1080)
        assertEquals(PointerGeometry.Point(200, 1079 - 100), p270)
    }

    @Test
    fun touchMarkDistinguishesSuccessAndFailureTruthfully() {
        val okMark = TouchMark(id = 1L, x = 200, y = 400, success = true, label = "Tap")
        val failMark = TouchMark(id = 2L, x = 200, y = 400, success = false, label = "Tap failed")
        assertTrue(okMark.success)
        assertFalse(failMark.success)
        assertEquals("Tap failed", failMark.label)
    }
}
