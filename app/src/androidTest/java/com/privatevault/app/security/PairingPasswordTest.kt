package com.privatevault.app.security

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PairingPasswordTest {
    @get:Rule val compose = createComposeRule()
    private val prefix = "pairing-password-${UUID.randomUUID()}"
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(base.cacheDir, prefix).apply { mkdirs() }
    private val app = object : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String) = File(root, name)
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun wrongPairingPasswordCreatesNoInvitationAndClearsInput() {
        val model = VaultViewModel(app)
        compose.setContent { PrivateVaultApp(model, false, {}, {}, {}, { _, _ -> }) }
        try {
            compose.runOnIdle { model.setup("long test passphrase".toCharArray()) }
            compose.waitUntil(20_000) { model.status.value is VaultStatus.Unlocked && model.canHostDevicePairing.value }
            val wrongPassword = "incorrect passphrase".toCharArray()
            runBlocking { withContext(Dispatchers.Main) {
                try {
                    model.hostDevicePairing(wrongPassword)
                    fail("An incorrect password must not start pairing")
                } catch (expected: IllegalArgumentException) {
                    assertEquals("Incorrect master password", expected.message)
                }
            } }
            assertTrue(wrongPassword.all { it == '\u0000' })
            assertTrue(model.devicePairingState.value.invitation.isEmpty())
        } finally { compose.runOnIdle { model.lock(LockReason.MANUAL) } }
        assertFalse(model.canHostDevicePairing.value)
        assertNull(model.syncManagerDeviceId.value)
    }
}
