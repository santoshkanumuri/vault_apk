package com.privatevault.app.security

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher

class BiometricExpiryTest {
    @Test fun expiredSessionCannotStartOrFinishFingerprintUnlock() {
        // Use the test APK's preferences, leaving the vault app's session untouched.
        val context = InstrumentationRegistry.getInstrumentation().context
        val prefs = context.getSharedPreferences("vault_biometric_session", Context.MODE_PRIVATE)
        val gate = BiometricGate(context)
        fun expire() {
            prefs.edit().putInt("format", 2).putString("wrapped_key", "dummy")
                .putLong("expires_at", System.currentTimeMillis() - 1).commit()
        }
        try {
            expire()
            assertNull(gate.dailyDecryptionCipher())
            expire()
            assertThrows(IllegalStateException::class.java) {
                gate.finishDailyUnlock(Cipher.getInstance("AES/GCM/NoPadding"))
            }
            assertFalse(prefs.contains("wrapped_key"))
        } finally { gate.clearDailySession() }
    }
}
