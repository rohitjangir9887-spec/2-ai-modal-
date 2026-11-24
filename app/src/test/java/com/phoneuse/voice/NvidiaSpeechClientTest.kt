package com.phoneuse.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaSpeechClientTest {
    @Test fun rejectsBlankKeysAndRemoteCleartextEndpoints() {
        // Regression test: operator precedence previously allowed blank keys on https:// URLs.
        assertFalse(NvidiaSpeechClient.isAsrConfigured("https://integrate.api.nvidia.com/v1/audio/transcriptions", ""))
        assertFalse(NvidiaSpeechClient.isAsrConfigured("https://integrate.api.nvidia.com/v1/audio/transcriptions", "   "))
        assertFalse(NvidiaSpeechClient.isTtsConfigured("https://integrate.api.nvidia.com/v1/audio/speech", ""))
        assertFalse(NvidiaSpeechClient.isAsrConfigured("http://evil.example.com/asr", "nvapi-valid-key-123"))
        assertFalse(NvidiaSpeechClient.isTtsConfigured("ftp://integrate.api.nvidia.com/tts", "nvapi-valid-key-123"))
    }

    @Test fun acceptsHttpsAndLoopbackCleartextWithKey() {
        assertTrue(NvidiaSpeechClient.isAsrConfigured("https://integrate.api.nvidia.com/v1/audio/transcriptions", "nvapi-valid-key-123"))
        assertTrue(NvidiaSpeechClient.isTtsConfigured("https://integrate.api.nvidia.com/v1/audio/speech", "nvapi-valid-key-123"))
        assertTrue(NvidiaSpeechClient.isAsrConfigured("http://localhost:8080/v1/audio/transcriptions", "local-key"))
        assertTrue(NvidiaSpeechClient.isTtsConfigured("http://127.0.0.1:9000/v1/audio/speech", "local-key"))
    }
}
