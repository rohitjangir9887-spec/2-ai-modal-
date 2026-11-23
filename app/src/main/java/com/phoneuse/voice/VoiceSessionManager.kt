package com.phoneuse.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LiveVoicePhase {
    IDLE,
    LISTENING,
    THINKING,
    ACTING,
    SPEAKING,
}

data class VoiceLiveState(
    val phase: LiveVoicePhase = LiveVoicePhase.IDLE,
    val continuousMode: Boolean = false,
    val muted: Boolean = false,
    val bargeInCount: Int = 0,
    val activeLanguageTag: String = "en-US",
)

/**
 * Coordinates the hands-free voice loop (IDLE -> LISTENING -> THINKING -> ACTING -> SPEAKING)
 * and guarantees instant TTS barge-in so spoken replies never overlap user speech.
 */
class VoiceSessionManager(
    private val onStopSpeech: () -> Unit = {},
    private val onStartListening: () -> Unit = {},
) {
    private val _state = MutableStateFlow(VoiceLiveState())
    val state: StateFlow<VoiceLiveState> = _state.asStateFlow()

    fun setContinuousMode(enabled: Boolean) {
        _state.update { it.copy(continuousMode = enabled) }
    }

    fun setLanguageTag(tag: String) {
        _state.update { it.copy(activeLanguageTag = tag) }
    }

    fun toggleMute(): Boolean {
        val next = !_state.value.muted
        if (next) onStopSpeech()
        _state.update { s ->
            s.copy(
                muted = next,
                phase = if (next && s.phase == LiveVoicePhase.SPEAKING) LiveVoicePhase.IDLE else s.phase,
            )
        }
        return next
    }

    /**
     * Immediately interrupts any ongoing TTS playback before entering [LiveVoicePhase.LISTENING].
     */
    fun bargeIn() {
        val wasSpeaking = _state.value.phase == LiveVoicePhase.SPEAKING
        onStopSpeech()
        _state.update { s ->
            s.copy(
                phase = if (s.muted) LiveVoicePhase.IDLE else LiveVoicePhase.LISTENING,
                bargeInCount = if (wasSpeaking) s.bargeInCount + 1 else s.bargeInCount,
            )
        }
        if (!_state.value.muted) onStartListening()
    }

    fun beginListening() {
        if (_state.value.muted) return
        if (_state.value.phase == LiveVoicePhase.SPEAKING) {
            bargeIn()
            return
        }
        onStopSpeech()
        _state.update { it.copy(phase = LiveVoicePhase.LISTENING) }
        onStartListening()
    }

    fun onSpeechRecognized(transcript: String) {
        if (transcript.isBlank()) {
            _state.update { it.copy(phase = LiveVoicePhase.IDLE) }
            return
        }
        _state.update { it.copy(phase = LiveVoicePhase.THINKING) }
    }

    fun onAgentActing() {
        _state.update { it.copy(phase = LiveVoicePhase.ACTING) }
    }

    fun onReplySpeaking(willSpeak: Boolean) {
        if (!willSpeak || _state.value.muted) {
            if (_state.value.continuousMode && !_state.value.muted) {
                _state.update { it.copy(phase = LiveVoicePhase.LISTENING) }
                onStartListening()
            } else {
                _state.update { it.copy(phase = LiveVoicePhase.IDLE) }
            }
            return
        }
        _state.update { it.copy(phase = LiveVoicePhase.SPEAKING) }
    }

    fun onSpeechCompleted() {
        val cur = _state.value
        if (cur.continuousMode && !cur.muted && cur.phase == LiveVoicePhase.SPEAKING) {
            _state.update { it.copy(phase = LiveVoicePhase.LISTENING) }
            onStartListening()
        } else {
            _state.update { it.copy(phase = LiveVoicePhase.IDLE) }
        }
    }

    fun stopSession() {
        onStopSpeech()
        _state.update { it.copy(phase = LiveVoicePhase.IDLE) }
    }

    companion object {
        /** Global shared live session state observed across voice sheets & ReadAloud. */
        val shared = VoiceSessionManager()
    }
}
