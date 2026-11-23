package com.phoneuse.security

/**
 * Redacts API keys, authorization headers, and sensitive authentication values (passwords, PINs, OTP codes)
 * from logs, persisted checkpoints, diagnostics, and user-facing error strings.
 */
object SecretRedactor {
    private const val REDACTED_KEY = "[REDACTED_KEY]"
    private const val REDACTED_SECRET = "[REDACTED]"

    private val BEARER_PATTERN = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val KEY_PREFIX_PATTERN = Regex("""\b(nvapi-[A-Za-z0-9_-]{8,}|sk-ant-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{8,}|xai-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{12,})""")
    private val FIELD_SECRET_PATTERN = Regex("""(?i)\b(api[_-]?key|authorization|token|password|passwd|secret|otp|pin|passcode)\b\s*[:=]\s*["']?([^\s"',;]{3,})["']?""")

    fun redact(text: String, knownSecrets: Collection<String> = emptyList()): String {
        if (text.isEmpty()) return text
        var out = text
        for (secret in knownSecrets) {
            val s = secret.trim()
            if (s.length >= 6) {
                out = out.replace(s, REDACTED_KEY)
            }
        }
        out = BEARER_PATTERN.replace(out, "Bearer $REDACTED_KEY")
        out = KEY_PREFIX_PATTERN.replace(out, REDACTED_KEY)
        out = FIELD_SECRET_PATTERN.replace(out) { m -> "${m.groupValues[1]}=$REDACTED_SECRET" }
        return out
    }

    fun redactLines(lines: List<String>, knownSecrets: Collection<String> = emptyList()): List<String> =
        lines.map { redact(it, knownSecrets) }

    fun redactHeaders(
        headers: Map<String, String>,
        knownSecrets: Collection<String> = emptyList(),
    ): Map<String, String> = headers.mapValues { (k, v) ->
        val lower = k.lowercase()
        if (lower == "authorization" || lower.contains("api-key") || lower.contains("secret") || lower.contains("token")) {
            REDACTED_KEY
        } else {
            redact(v, knownSecrets)
        }
    }
}
