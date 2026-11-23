package com.phoneuse.voice

import com.phoneuse.app.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceSessionManagerTest {
    @Test
    fun supportsHindiAndHinglishBcp47TagsAlongsideEnglishAndKorean() {
        assertEquals("hi-IN", speechLanguageTag(VoiceLanguage.HINDI, Locale.US))
        assertEquals("en-IN", speechLanguageTag(VoiceLanguage.HINGLISH, Locale.US))
        assertEquals("en-US", speechLanguageTag(VoiceLanguage.ENGLISH, Locale.KOREA))
        assertEquals("ko-KR", speechLanguageTag(VoiceLanguage.KOREAN, Locale.US))
        assertEquals("hi-IN", speechLanguageTag(VoiceLanguage.SYSTEM, Locale.forLanguageTag("hi-IN")))
    }

    @Test
    fun bargeInImmediatelyStopsTtsBeforeStartingListener() {
        var speechStopped = 0
        var listeningStarted = 0
        val mgr = VoiceSessionManager(
            onStopSpeech = { speechStopped++ },
            onStartListening = { listeningStarted++ },
        )
        mgr.onReplySpeaking(true)
        assertEquals(LiveVoicePhase.SPEAKING, mgr.state.value.phase)

        mgr.bargeIn()
        assertEquals(1, speechStopped)
        assertEquals(1, listeningStarted)
        assertEquals(LiveVoicePhase.LISTENING, mgr.state.value.phase)
        assertEquals(1, mgr.state.value.bargeInCount)
    }

    @Test
    fun continuousModeResumesListeningAfterReplyWhileSingleTurnIdles() {
        var listeningStarted = 0
        val mgr = VoiceSessionManager(onStartListening = { listeningStarted++ })
        mgr.setContinuousMode(true)
        mgr.onReplySpeaking(true)
        mgr.onSpeechCompleted()
        assertEquals(LiveVoicePhase.LISTENING, mgr.state.value.phase)
        assertEquals(1, listeningStarted)

        mgr.setContinuousMode(false)
        mgr.onReplySpeaking(true)
        mgr.onSpeechCompleted()
        assertEquals(LiveVoicePhase.IDLE, mgr.state.value.phase)
        assertEquals(1, listeningStarted)
    }

    @Test
    fun muteBlocksAutoListeningAndStopsTts() {
        var stopped = 0
        var started = 0
        val mgr = VoiceSessionManager(onStopSpeech = { stopped++ }, onStartListening = { started++ })
        mgr.setContinuousMode(true)
        mgr.onReplySpeaking(true)
        assertTrue(mgr.toggleMute())
        assertEquals(1, stopped)
        assertEquals(LiveVoicePhase.IDLE, mgr.state.value.phase)
        mgr.beginListening()
        assertEquals(0, started)
        assertFalse(mgr.toggleMute())
    }
}
