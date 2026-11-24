package com.phoneuse.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JuliWakeWordDetectorTest {

    @Test
    fun matchesCanonicalWakeWords() {
        var triggered = 0
        val detector = JuliWakeWordDetector(onWakeWordDetected = { triggered++ })
        detector.setEnabled(true)

        assertTrue(detector.evaluate("Hey Juli"))
        assertEquals(1, triggered)

        // Beyond cooldown window test
        val detector2 = JuliWakeWordDetector(onWakeWordDetected = { triggered++ })
        detector2.setEnabled(true)
        assertTrue(detector2.evaluate("हे जूली"))
        assertEquals(2, triggered)

        val detector3 = JuliWakeWordDetector(onWakeWordDetected = { triggered++ })
        detector3.setEnabled(true)
        assertTrue(detector3.evaluate("हाय जूली"))
        assertEquals(3, triggered)
    }

    @Test
    fun rejectsNonMatchingWords() {
        var triggered = 0
        val detector = JuliWakeWordDetector(onWakeWordDetected = { triggered++ })
        detector.setEnabled(true)

        assertFalse(detector.evaluate("Hey Siri"))
        assertFalse(detector.evaluate("Ok Google"))
        assertFalse(detector.evaluate("Alexa"))
        assertFalse(detector.evaluate("नमस्ते भारत"))
        assertEquals(0, triggered)
    }

    @Test
    fun suppressesTriggersWhenAssistantIsSpeakingEchoPrevention() {
        var isSpeaking = true
        var triggered = 0
        val detector = JuliWakeWordDetector(
            onWakeWordDetected = { triggered++ },
            isEchoSuppressed = { isSpeaking },
        )
        detector.setEnabled(true)

        // Blocked because assistant is speaking
        assertFalse(detector.evaluate("Hey Juli"))
        assertEquals(0, triggered)

        // Enabled when speech completes
        isSpeaking = false
        assertTrue(detector.evaluate("Hey Juli"))
        assertEquals(1, triggered)
    }

    @Test
    fun respectsDisabledState() {
        var triggered = 0
        val detector = JuliWakeWordDetector(onWakeWordDetected = { triggered++ })
        detector.setEnabled(false)

        assertFalse(detector.evaluate("Hey Juli"))
        assertEquals(0, triggered)
    }

    @Test
    fun suppressesSecondTriggerWithinCooldownWindow() {
        var triggered = 0
        val detector = JuliWakeWordDetector(onWakeWordDetected = { triggered++ })
        detector.setEnabled(true)

        assertTrue(detector.evaluate("Hey Juli"))
        assertEquals(1, triggered)

        // Immediate subsequent call within 2.5s cooldown
        assertFalse(detector.evaluate("Hey Juli"))
        assertEquals(1, triggered)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
