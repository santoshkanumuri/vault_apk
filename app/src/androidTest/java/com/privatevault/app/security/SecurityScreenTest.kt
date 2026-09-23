package com.privatevault.app.security

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.LockReason
import com.privatevault.app.PrivateVaultApp
import com.privatevault.app.VaultStatus
import com.privatevault.app.VaultViewModel
import com.privatevault.app.UnlockScreen
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SecurityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().context.applicationContext as Application

    @Before fun cleanStart() {
        app.deleteDatabase("vault.db")
        app.getSharedPreferences("vault_bootstrap", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("vault_biometric_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanFinish() = cleanStart()

    @Test fun lockRemovesVaultContentFromTheComposeTree() {
        val model = VaultViewModel(app)
        compose.setContent { PrivateVaultApp(model, false, {}, {}, {}, { _, _ -> }) }
        compose.runOnIdle { model.setup("long test passphrase".toCharArray()) }
        compose.waitUntil(20_000) { model.status.value is VaultStatus.Unlocked }
        compose.runOnIdle { model.saveEntry(VaultEntry(type = EntryType.NOTE, title = "Private sentinel"), emptySet()) }
        compose.waitUntil(10_000) { model.entries.value.any { it.entry.title == "Private sentinel" } }
        compose.runOnIdle { model.lock(LockReason.MANUAL) }
        compose.onNodeWithText("Private sentinel").assertDoesNotExist()
        compose.onNodeWithText("Nuvori").assertIsDisplayed()
        assertTrue(model.entries.value.isEmpty())
    }

    @Test fun lockedScreenOffersFingerprintOnlyForAnActiveSession() {
        val model = VaultViewModel(app)
        val eligible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme { UnlockScreen(model, VaultStatus.Locked(LockReason.STARTUP, eligible.value), eligible.value, {}) }
        }
        compose.onNodeWithText("Use fingerprint").assertIsDisplayed()
        compose.onNodeWithText("Use master password").assertIsDisplayed()
        compose.runOnIdle { eligible.value = false }
        compose.onNodeWithText("Use fingerprint").assertDoesNotExist()
        compose.onNodeWithText("Master password").assertIsDisplayed()
    }

    @Test fun immediateBackgroundLockAndScreenOffClearTheSession() {
        val model = VaultViewModel(app)
        compose.setContent { PrivateVaultApp(model, false, {}, {}, {}, { _, _ -> }) }
        compose.runOnIdle { model.setup("long test passphrase".toCharArray()) }
        compose.waitUntil(20_000) { model.status.value is VaultStatus.Unlocked }
        compose.runOnIdle { model.setBackgroundTimeout(0) }
        compose.waitUntil(10_000) { model.securitySettings.value.backgroundTimeoutMs == 0L }
        compose.runOnIdle { model.onAppBackgrounded() }
        compose.waitUntil(10_000) { model.status.value is VaultStatus.Locked }
        assertTrue(model.entries.value.isEmpty())
        compose.runOnIdle { model.onAppForegrounded(); model.unlock("long test passphrase".toCharArray()) }
        compose.waitUntil(20_000) { model.status.value is VaultStatus.Unlocked }
        compose.runOnIdle { model.lock(LockReason.SCREEN_OFF) }
        assertTrue(model.status.value is VaultStatus.Locked)
        assertTrue(model.entries.value.isEmpty())
    }

    @Test fun externalPickerDoesNotUseTheImmediateBackgroundLockAndIntervalChangeClearsFingerprint() {
        val model = VaultViewModel(app)
        compose.setContent { PrivateVaultApp(model, false, {}, {}, {}, { _, _ -> }) }
        compose.runOnIdle { model.setup("long test passphrase".toCharArray()) }
        compose.waitUntil(20_000) { model.status.value is VaultStatus.Unlocked }
        compose.runOnIdle { model.setBackgroundTimeout(0) }
        compose.waitUntil(10_000) { model.securitySettings.value.backgroundTimeoutMs == 0L }
        compose.runOnIdle { model.externalFlowActive = true; model.onAppBackgrounded() }
        assertTrue(model.status.value is VaultStatus.Unlocked)
        compose.runOnIdle { model.onAppForegrounded() }
        assertTrue(model.status.value is VaultStatus.Unlocked)
        val prefs = app.getSharedPreferences("vault_biometric_session", Context.MODE_PRIVATE)
        prefs.edit().putString("wrapped_key", "test").commit()
        compose.runOnIdle { model.setMasterPasswordInterval(604_800_000L) }
        assertTrue(!prefs.contains("wrapped_key"))
        compose.runOnIdle { model.lock(LockReason.MANUAL) }
    }
}
