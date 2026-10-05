package com.privatevault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.autofill.AutofillPreferences
import com.privatevault.app.sync.DeviceSyncPhase
import com.privatevault.app.sync.DeviceSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hubGroupsPagesShowsStatusAndOpensThePage() {
        val opened = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsHub(SettingsHubSummary(backgroundTimeoutMs = 10_000L, pairedDevices = 2,
                        syncNeedsAttention = true, watchSyncEnabled = false, passkeys = 3, lightMode = false,
                        nfcEnabled = true, nfcSupported = true)) { opened.value = it }
                }
            }
        }
        compose.onNodeWithText("Autofill and passkeys").assertExists()
        compose.onNodeWithText("2 devices").assertExists()
        compose.onNodeWithText("Needs attention").assertExists()
        compose.onNodeWithText("3 saved").assertExists()
        compose.onNodeWithText("Devices & sync").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Android devices", opened.value) }
        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("About", opened.value) }
    }

    @Test fun copyLinkedCodeSwitchPersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = AutofillPreferences.copyLinkedCode(context)
        try {
            AutofillPreferences.setCopyLinkedCode(context, true)
            compose.setContent {
                MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AutofillPreference() } }
            }
            compose.onNodeWithText("Copy linked code after filling").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(AutofillPreferences.copyLinkedCode(context)) }
        } finally { AutofillPreferences.setCopyLinkedCode(context, previous) }
    }

    @Test fun syncNowRunsAndWaitsWhileDevicesExchange() {
        var taps = 0
        val phase = mutableStateOf(DeviceSyncPhase.CHECKED)
        compose.setContent {
            MaterialTheme {
                DeviceSyncState(DeviceSyncStatus(phase.value, "Checked."), 0, 1, 0, 0L) { taps++ }
            }
        }
        compose.onNodeWithText("Sync now").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        compose.runOnIdle { phase.value = DeviceSyncPhase.CONNECTING }
        compose.onNodeWithText("Sync now").assertIsNotEnabled()
    }
}
