package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Representative Korean card-approval and courier texts, fixed so a parser change cannot silently lose them. */
class SmsParsersTest {
    @Test fun maskCodesOnlyInCodeTexts() {
        assertEquals("[Web발신] 인증번호 [●●●●●●]를 입력해주세요", SmsParsers.maskCodes("[Web발신] 인증번호 [482913]를 입력해주세요"))
        assertEquals("Your verification code is ●●●●", SmsParsers.maskCodes("Your verification code is 7712"))
        // The title can be the context: "인증" title, bare code text.
        assertEquals("●●●●●● 입력", SmsParsers.maskCodes("482913 입력", "본인확인\n482913 입력"))
        // Amounts and ordinary numbers stay.
        assertEquals("12,500원 결제 2026년", SmsParsers.maskCodes("12,500원 결제 2026년"))
        assertEquals("주문번호 1234567", SmsParsers.maskCodes("주문번호 1234567"))
    }

    @Test fun masksHindiAndDevanagariOtpsWhilePreservingInrAmounts() {
        assertEquals("आपका ओटीपी ●●●●●● है", SmsParsers.maskCodes("आपका ओटीपी 482913 है"))
        assertEquals("आपका ओटीपी ●●●●●● है", SmsParsers.maskCodes("आपका ओटीपी ४८२९१३ है"))
        assertEquals("₹2500 के लिए ओटीपी ●●●●●●", SmsParsers.maskCodes("₹2500 के लिए ओटीपी 998877"))
        assertEquals("₹1,500 paid", SmsParsers.maskCodes("₹1,500 paid"))
    }
}
