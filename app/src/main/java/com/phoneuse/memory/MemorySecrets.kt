package com.phoneuse.memory

import com.phoneuse.i18n.tr

/** A memory refused because it holds a secret; the message says why (the model relays it to the user). */
class SecretInMemoryException(message: String) : IllegalArgumentException(message)

/**
 * Secrets never go into memory: memories are stored as plain text in the chat database and are sent to the model
 * with every request they match, and the model must never type a password. Refused: a password, PIN, passcode or
 * one-time code given with its value, a card number (Luhn-valid 13-19 digits), and a resident registration number.
 * A fact that only mentions such a thing ("uses a password manager") is fine.
 */
object MemorySecrets {
    private val secretWord = Regex(
        """(?i)पासवर्ड|ओटीपी|पिन|यूपीआई\s*पिन|पासकी|रिकवरी\s*कोड|\bupi[- ]?pin\b|\b[mt]pin\b|비밀\s*번호|비번|암호|패스워드|핀\s*번호|인증\s*번호|보안\s*코드|일회용|\b(?:password|passwd|pwd|passcode|passkey|recovery[- ]?code|backup[- ]?code|seed[- ]?phrase|mnemonic|netbanking|pin|otp|cvc|cvv|verification code|security code|one[- ]time code)\b""",
    )

    /** A value next to the word: 4+ digits, or "word: value" / "word is/hai value" with a digit or 6+ characters. */
    private val secretValue = Regex(
        """\d{4,}|(?i)(?:[:=]|\b(?:is|hai|ka|ki)\b|(?:는|은|이|가)\s)\s*\S*\d\S*|[:=]\s*\S{6,}""",
    )

    // Indian identity documents and banking credentials must never enter persistent AI memory.
    private val aadhaar = Regex("""(?<!\d)[2-9]\d{3}[ -]?\d{4}[ -]?\d{4}(?!\d)""")
    private val pan = Regex("""(?<![A-Za-z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val ifsc = Regex("""(?<![A-Za-z0-9])[A-Z]{4}0[A-Z0-9]{6}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val bankAccount = Regex("""(?i)\b(?:a/c|acct|account)\s*(?:no\.?|number|num|#|:|=|\bis\b|\bhai\b)?\s*\d{9,18}\b""")
    private val apiToken = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{6,}|\b(?:nvapi-[A-Za-z0-9_-]{8,}|sk-ant-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{8,}|xai-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{12,})""")

    private val cardLike = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")
    private val rrn = Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})\s*-?\s*([1-8])\d{6}(?!\d)""")

    /** Normalize Devanagari digits (०-९) to ASCII 0-9 so Indian numeric secrets in Hindi script are caught too. */
    private fun normalizeDigits(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch in '\u0966'..'\u096F') append(('0'.code + (ch.code - 0x0966)).toChar()) else append(ch)
        }
    }

    /** Why [text] may not be remembered, or null when it may. */
    fun refusal(text: String): String? {
        val norm = normalizeDigits(text)
        return when {
            apiToken.containsMatchIn(norm) ->
                tr(
                    "Not remembered: API keys and authorization tokens must not be stored in AI memory",
                    "기억하지 않았어요: API 키나 인증 토큰은 AI 기억에 저장하지 않아요",
                )
            aadhaar.containsMatchIn(norm) || pan.containsMatchIn(norm) ->
                tr(
                    "Not remembered: Aadhaar or PAN details must not be stored in AI memory",
                    "기억하지 않았어요: 신분증 번호는 AI 기억에 저장하지 않아요",
                )
            (ifsc.containsMatchIn(norm) && Regex("""\b\d{9,18}\b""").containsMatchIn(norm)) || bankAccount.containsMatchIn(norm) ->
                tr(
                    "Not remembered: bank account or IFSC details must not be stored in AI memory",
                    "기억하지 않았어요: 계좌 번호나 은행 식별 정보는 AI 기억에 저장하지 않아요",
                )
            rrn.findAll(norm).any { m -> plausibleBirth(m.groupValues[2], m.groupValues[3]) } ->
                tr(
                    "Not remembered: it contains a resident registration number. Memories are plain text sent to the model, so ID numbers are never kept",
                    "기억하지 않았어요: 주민등록번호가 들어 있어요. 기억은 암호화되지 않고 모델에게 보내지므로 주민등록번호는 저장하지 않아요",
                )
            cardLike.findAll(norm).any { luhn(it.value.filter(Char::isDigit)) } ->
                tr(
                    "Not remembered: it contains a card number. Memories are plain text sent to the model, so card numbers are never kept",
                    "기억하지 않았어요: 카드 번호가 들어 있어요. 기억은 암호화되지 않고 모델에게 보내지므로 카드 번호는 저장하지 않아요",
                )
            secretWord.containsMatchIn(norm) && secretValue.containsMatchIn(norm) ->
                tr(
                    "Not remembered: it looks like a password, PIN, OTP, or recovery code. Memories are sent to the model, and the app never enters credentials, so secrets are never kept",
                    "기억하지 않았어요: 비밀번호·PIN·인증번호로 보여요. 기억은 암호화되지 않고 모델에게 보내지며, 앱은 비밀번호를 직접 입력하지 않으므로 비밀 정보는 저장하지 않아요",
                )
            else -> null
        }
    }

    /** Throws [SecretInMemoryException] when [text] holds a secret. */
    fun check(text: String) {
        refusal(text)?.let { throw SecretInMemoryException(it) }
    }

    private fun plausibleBirth(month: String, day: String): Boolean =
        month.toInt() in 1..12 && day.toInt() in 1..31

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        for ((i, ch) in digits.reversed().withIndex()) {
            var d = ch - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
