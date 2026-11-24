package com.phoneuse.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceSessionTest {

    @Test
    fun voiceSessionManagerAttachesCallbacksAndTriggersBargeIn() {
        var speechStopped = 0
        var listeningStarted = 0
        val mgr = VoiceSessionManager()
        mgr.attachCallbacks(
            onStopSpeech = { speechStopped++ },
            onStartListening = { listeningStarted++ },
        )

        mgr.onReplySpeaking(true)
        assertEquals(LiveVoicePhase.SPEAKING, mgr.state.value.phase)

        mgr.bargeIn()
        assertEquals(LiveVoicePhase.LISTENING, mgr.state.value.phase)
        assertEquals(1, speechStopped)
        assertEquals(1, listeningStarted)
    }

    @Test
    fun continuousModeAutomaticallyLoopsBackToListening() {
        var listeningStarted = 0
        val mgr = VoiceSessionManager()
        mgr.attachCallbacks(
            onStopSpeech = {},
            onStartListening = { listeningStarted++ },
        )
        mgr.setContinuousMode(true)

        mgr.onReplySpeaking(true)
        assertEquals(LiveVoicePhase.SPEAKING, mgr.state.value.phase)

        // When speech finishes, it automatically returns to LISTENING without manual Send
        mgr.onSpeechCompleted()
        assertEquals(LiveVoicePhase.LISTENING, mgr.state.value.phase)
        assertEquals(1, listeningStarted)
    }

    @Test
    fun kokoroAudioSynthesisNeverProducesSilentBuffers() {
        val pcm = KokoroVoiceEngine.generatePcmChunk(
            chunk = "नमस्ते! मैं जूली हूँ।",
            languageTag = "hi-IN",
            profile = KokoroVoiceEngine.IndianVoiceProfile.JULI_FEMALE,
            speed = 1.0f,
        )
        assertTrue(pcm.isNotEmpty())
        assertEquals(0, pcm.size % 2)

        val rms = KokoroVoiceEngine.calculateRmsEnergy(pcm)
        assertTrue("Audible energy expected but was $rms", rms > 1000.0)
    }

    @Test
    fun wakeWordAcousticDetectorCalculatesEnergyAndRespectsCooldown() {
        var triggers = 0
        val detector = JuliWakeWordDetector(onWakeWordDetected = { triggers++ })
        detector.setEnabled(true)

        assertTrue(detector.evaluate("Hey Juli"))
        assertEquals(1, triggers)

        // Immediate subsequent call is blocked by cooldown
        assertFalse(detector.evaluate("Hey Juli"))
        assertEquals(1, triggers)
    }
}
