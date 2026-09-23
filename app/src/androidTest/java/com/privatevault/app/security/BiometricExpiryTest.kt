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
            prefs.edit().putInt("format", 3).putString("wrapped_key", "dummy")
                .putLong("created_wall", System.currentTimeMillis() - 86_400_000L)
                .putLong("created_elapsed", android.os.SystemClock.elapsedRealtime())
                .putInt("boot_count", android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1))
                .putLong("last_seen_wall", System.currentTimeMillis() - 86_400_000L)
                .putLong("duration", 86_400_000L).commit()
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
