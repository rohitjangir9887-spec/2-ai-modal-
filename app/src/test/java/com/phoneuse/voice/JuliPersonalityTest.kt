package com.phoneuse.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JuliPersonalityTest {

    @Test
    fun rejectsSensitiveFinancialAndPiiFromMemory() {
        // Aadhaar (12-digit)
        assertFalse(JuliPersonality.isSafeToRemember("User aadhaar is 1234 5678 9012"))
        assertFalse(JuliPersonality.isSafeToRemember("123456789012"))

        // PAN Card (5 letters, 4 digits, 1 letter)
        assertFalse(JuliPersonality.isSafeToRemember("PAN number ABCDE1234F"))

        // OTPs & PINs
        assertFalse(JuliPersonality.isSafeToRemember("Your OTP is 849201"))
        assertFalse(JuliPersonality.isSafeToRemember("Mera UPI PIN 4829 hai"))
        assertFalse(JuliPersonality.isSafeToRemember("Account password secret123"))

        // Credit Cards
        assertFalse(JuliPersonality.isSafeToRemember("Card 4111 2222 3333 4444"))
    }

    @Test
    fun allowsSafePersonalPreferencesInMemory() {
        assertTrue(JuliPersonality.isSafeToRemember("User prefers Hindi voice responses"))
        assertTrue(JuliPersonality.isSafeToRemember("Mother's birthday is on 15th August"))
        assertTrue(JuliPersonality.isSafeToRemember("Always set alarm for 6:30 AM"))
        assertTrue(JuliPersonality.isSafeToRemember("Favorite music artist is Arijit Singh"))
    }

    @Test
    fun enforcesDeviceLockKeyguardRestrictions() {
        val waCommand = ClassifiedVoiceCommand(
            type = VoiceCommandType.START,
            rawText = "WhatsApp par message bhejo",
            cleanedPrompt = "WhatsApp par message bhejo",
            targetApp = "com.whatsapp",
        )

        // When device is locked, sensitive WhatsApp action is strictly blocked
        assertFalse(JuliPersonality.canExecuteOnLockedDevice(waCommand, isKeyguardLocked = true))

        // When device is unlocked, action is allowed
        assertTrue(JuliPersonality.canExecuteOnLockedDevice(waCommand, isKeyguardLocked = false))

        // Stop and pause are allowed even on locked screen
        val stopCommand = ClassifiedVoiceCommand(VoiceCommandType.STOP, "ruko", "ruko")
        assertTrue(JuliPersonality.canExecuteOnLockedDevice(stopCommand, isKeyguardLocked = true))

        // Time / weather query is allowed on locked screen
        val timeCommand = ClassifiedVoiceCommand(VoiceCommandType.START, "samay kya hua hai", "samay kya hua hai")
        assertTrue(JuliPersonality.canExecuteOnLockedDevice(timeCommand, isKeyguardLocked = true))
    }

    @Test
    fun formatsResponsesPolitelyInHindiAndHinglish() {
        JuliPersonality.dialect = JuliPersonality.Dialect.HINDI
        JuliPersonality.tone = JuliPersonality.Tone.POLITE
        assertTrue(JuliPersonality.onTaskStopped().contains("रोक"))

        JuliPersonality.dialect = JuliPersonality.Dialect.HINGLISH
        assertTrue(JuliPersonality.onTaskStopped().contains("stop"))

        JuliPersonality.dialect = JuliPersonality.Dialect.INDIAN_ENGLISH
        assertTrue(JuliPersonality.onTaskStopped().contains("stopped"))
    }
}
