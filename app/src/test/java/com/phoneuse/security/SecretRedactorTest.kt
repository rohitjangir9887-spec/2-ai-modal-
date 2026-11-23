package com.phoneuse.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun redactsApiKeysAndBearerHeaders() {
        val raw = "Authorization: Bearer nvapi-AbCdEf1234567890XyZ987654 failed for key sk-1234567890abcdef123456"
        val clean = SecretRedactor.redact(raw)
        assertFalse(clean.contains("nvapi-"))
        assertFalse(clean.contains("sk-1234567890"))
        assertTrue(clean.contains("[REDACTED_KEY]"))
    }

    @Test
    fun redactsPasswordsPinsAndOtps() {
        val raw = "password=mySecretPass! otp: 482910 pin=\"9911\" token=tok_live_9876543210"
        val clean = SecretRedactor.redact(raw)
        assertFalse(clean.contains("mySecretPass!"))
        assertFalse(clean.contains("482910"))
        assertFalse(clean.contains("9911"))
        assertFalse(clean.contains("tok_live_9876543210"))
    }

    @Test
    fun hidesIndianIdentityNumbers() {
        val clean = SecretRedactor.redact("Aadhaar 2345 6789 0123 PAN ABCDE1234F")
        assertFalse(clean.contains("2345 6789 0123"))
        assertFalse(clean.contains("ABCDE1234F"))
        assertTrue(clean.contains("[REDACTED]"))
    }

    @Test
    fun redactsKnownRuntimeSecretsLiterally() {
        val raw = "error connecting with custom-key-abc-999111"
        val clean = SecretRedactor.redact(raw, listOf("custom-key-abc-999111"))
        assertEquals("error connecting with [REDACTED_KEY]", clean)
    }

    @Test
    fun redactsMapHeadersCaseInsensitively() {
        val scrubbed = SecretRedactor.redactHeaders(
            mapOf(
                "Authorization" to "Bearer nvapi-secret1234567890",
                "X-Api-Key" to "my-raw-api-key",
                "Content-Type" to "application/json",
            ),
        )
        assertEquals("[REDACTED_KEY]", scrubbed["Authorization"])
        assertEquals("[REDACTED_KEY]", scrubbed["X-Api-Key"])
        assertEquals("application/json", scrubbed["Content-Type"])
    }
}
