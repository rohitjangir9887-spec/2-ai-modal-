package com.phoneuse.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroVoiceEngineTest {

    @Test
    fun chunkTextSplitsAtHindiPurnaViramAndEnglishPunctuation() {
        val hindiText = "नमस्ते। मैं जूली हूँ। आपकी AI फोन असिस्टेंट।"
        val hindiChunks = KokoroVoiceEngine.chunkText(hindiText)
        assertTrue(hindiChunks.size >= 2)
        assertTrue(hindiChunks[0].contains("नमस्ते"))

        val englishText = "Hello there. I am ready to automate your tasks, just tell me what to do!"
        val englishChunks = KokoroVoiceEngine.chunkText(englishText)
        assertTrue(englishChunks.size >= 2)
    }

    @Test
    fun chunkTextHandlesEmptyAndSingleClauses() {
        assertEquals(emptyList<String>(), KokoroVoiceEngine.chunkText(""))
        assertEquals(emptyList<String>(), KokoroVoiceEngine.chunkText("   "))

        val single = KokoroVoiceEngine.chunkText("व्हाट्सएप खोलो")
        assertEquals(1, single.size)
        assertEquals("व्हाट्सएप खोलो", single[0])
    }

    @Test
    fun generatePcmChunkProducesValidAudioSamples() {
        val pcm = KokoroVoiceEngine.generatePcmChunk(
            chunk = "नमस्ते जी",
            languageTag = "hi-IN",
            profile = KokoroVoiceEngine.IndianVoiceProfile.JULI_FEMALE,
            speed = 1.0f,
        )
        // Ensure non-empty PCM 16-bit buffer (even number of bytes)
        assertTrue(pcm.isNotEmpty())
        assertEquals(0, pcm.size % 2)

        // Ensure audio is genuinely audible with non-zero RMS energy (never fake silent zeroes)
        val rms = KokoroVoiceEngine.calculateRmsEnergy(pcm)
        assertTrue("Expected non-zero audible RMS energy, but got $rms", rms > 1000.0)
    }

    @Test
    fun stopInstantlyResetsPlaybackState() {
        KokoroVoiceEngine.stop()
        assertFalse(KokoroVoiceEngine.isSpeaking)
        assertEquals(KokoroVoiceEngine.EngineState.Idle, KokoroVoiceEngine.state.value)
    }
}
