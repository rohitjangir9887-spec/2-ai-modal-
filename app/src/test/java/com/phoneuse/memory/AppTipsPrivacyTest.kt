package com.phoneuse.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTipsPrivacyTest {
    @Test fun doesNotRememberPersonalIdentifiersInSuccessfulTaskHints() {
        assertNull(AppTips.task("Saved PAN ABCDE1234F in the form."))
        assertNull(AppTips.task("Aadhaar 2345 6789 0123 was entered."))
        val result = AppTips.steps(listOf("Open Settings", "Entered PAN ABCDE1234F", "Tapped Save"))
        assertFalse(result.contains("ABCDE1234F"))
        assertTrue(result.contains("Open Settings"))
    }

    @Test fun learnedHintsRemainAvailableWithoutKoreanInstructions() {
        val tip = AppTip(1L, "com.sample", "Open product catalogue", "open app → listings", 1L)
        val note = AppTips.note("com.sample", listOf(tip))!!
        assertTrue(note.contains("Previously successful navigation hints"))
        assertTrue(note.contains("product catalogue"))
        assertFalse(note.contains("앱 조작 요령"))
    }
}
