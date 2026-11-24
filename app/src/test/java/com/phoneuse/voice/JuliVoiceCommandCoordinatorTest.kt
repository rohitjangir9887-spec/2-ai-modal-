package com.phoneuse.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JuliVoiceCommandCoordinatorTest {

    private val coordinator = JuliVoiceCommandCoordinator()

    @Test
    fun cleanPromptStripsWakeWordPrefixes() {
        assertEquals("WhatsApp kholo", coordinator.cleanPrompt("Hey Juli, WhatsApp kholo"))
        assertEquals("WhatsApp kholo", coordinator.cleanPrompt("Juli, WhatsApp kholo"))
        assertEquals("Google par search karo", coordinator.cleanPrompt("हे जूली, Google par search karo"))
        assertEquals("यूट्यूब शुरू करो", coordinator.cleanPrompt("सुनो जूली, यूट्यूब शुरू करो"))
        assertEquals("सीधे काम करो", coordinator.cleanPrompt("सीधे काम करो"))
    }

    @Test
    fun classifyDetectsStopCommandsAcrossLanguages() {
        assertEquals(VoiceCommandType.STOP, coordinator.classify("Juli, ruko").type)
        assertEquals(VoiceCommandType.STOP, coordinator.classify("Hey Juli, band karo").type)
        assertEquals(VoiceCommandType.STOP, coordinator.classify("Stop").type)
        assertEquals(VoiceCommandType.STOP, coordinator.classify("हे जूली, बंद करो").type)
        assertEquals(VoiceCommandType.STOP, coordinator.classify("काम रोक दो").type)
    }

    @Test
    fun classifyDetectsPauseAndResumeCommands() {
        assertEquals(VoiceCommandType.PAUSE, coordinator.classify("Juli, pause karo").type)
        assertEquals(VoiceCommandType.PAUSE, coordinator.classify("थोड़ी देर रुको").type)
        assertEquals(VoiceCommandType.RESUME, coordinator.classify("Juli, resume karo").type)
        assertEquals(VoiceCommandType.RESUME, coordinator.classify("दोबारा शुरू करो").type)
        assertEquals(VoiceCommandType.RESUME, coordinator.classify("aage badho").type)
    }

    @Test
    fun classifyDetectsReplaceAndQueueCommands() {
        assertEquals(VoiceCommandType.MODIFY, coordinator.classify("Juli, thoda change karo").type)
        assertEquals(VoiceCommandType.MODIFY, coordinator.classify("बदलो इसे").type)
        assertEquals(VoiceCommandType.REPLACE, coordinator.classify("Juli, pehle doosra task karo").type)
        assertEquals(VoiceCommandType.REPLACE, coordinator.classify("इसकी जगह वो करो").type)
        assertEquals(VoiceCommandType.QUEUE, coordinator.classify("Juli, baad mein karna ye").type)
        assertEquals(VoiceCommandType.QUEUE, coordinator.classify("कतार में डालो").type)
    }

    @Test
    fun classifyDetectsAnswersAndConfirmations() {
        assertEquals(VoiceCommandType.ANSWER, coordinator.classify("haan").type)
        assertEquals(VoiceCommandType.ANSWER, coordinator.classify("theek hai").type)
        assertEquals(VoiceCommandType.ANSWER, coordinator.classify("हाँ").type)
        assertEquals(VoiceCommandType.ANSWER, coordinator.classify("yes").type)
        assertEquals(VoiceCommandType.ANSWER, coordinator.classify("bhej do").type)
    }

    @Test
    fun classifyDetectsStartWithAppAliasExtraction() {
        val wa = coordinator.classify("Hey Juli, WhatsApp kholo")
        assertEquals(VoiceCommandType.START, wa.type)
        assertEquals("com.whatsapp", wa.targetApp)

        val yt = coordinator.classify("हे जूली, यूट्यूब खोलो")
        assertEquals(VoiceCommandType.START, yt.type)
        assertEquals("com.google.android.youtube", yt.targetApp)
    }
}
