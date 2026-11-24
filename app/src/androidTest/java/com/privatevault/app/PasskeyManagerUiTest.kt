package com.privatevault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.privatevault.app.data.PasskeyEventKind
import com.privatevault.app.data.PasskeyHistoryEvent
import com.privatevault.app.data.PasskeyInfo
import com.privatevault.app.data.PasskeyOrigin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PasskeyManagerUiTest {
    @get:Rule val compose = createComposeRule()
    private val passkey = PasskeyInfo("credential-id-abcdefghijklmnop", "github.com", "user-handle-0123456789", "me@example.com",
        "Me", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(91) { 1 }), 1_790_000_000_000)
    private val history = listOf(
        PasskeyHistoryEvent(3, passkey.id, "github.com", "me@example.com", PasskeyEventKind.USED.name, 1_790_000_300_000,
            "com.android.chrome", "Chrome", "https://github.com"),
        PasskeyHistoryEvent(2, "gone", "old.example", "old", PasskeyEventKind.DELETED.name, 1_790_000_200_000),
        PasskeyHistoryEvent(1, passkey.id, "github.com", "me@example.com", PasskeyEventKind.CREATED.name, 1_790_000_000_000,
            "com.android.chrome", "Chrome"),
    )

    @Test fun aTapShowsTheDetailsAndDeleteAsksTheCaller() {
        var deleted: PasskeyInfo? = null
        val data = PasskeyPageData(listOf(passkey), mapOf(passkey.id to PasskeyOrigin(passkey.id, "com.android.chrome", "Chrome", null)), history)
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { SavedPasskeysSection(data) { deleted = it } } }
        }
        compose.onNodeWithText("github.com").assertExists()
        compose.onNodeWithText("Credential ID").assertDoesNotExist()
        compose.onNodeWithText("me@example.com").performClick()
        compose.onNodeWithText("Credential ID").performScrollTo().assertExists()
        compose.onNodeWithText("credenti…klmnop").assertExists()
        compose.onNodeWithText("Created in Chrome (com.android.chrome)").assertExists()
        compose.onNodeWithText("Signed in to github.com with Chrome").assertExists()
        compose.onNodeWithText("ES256 (ECDSA P-256 with SHA-256) · stays in your encrypted vault").assertExists()
        compose.onNodeWithText("Delete passkey").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(passkey.id, deleted?.id) }
    }

    @Test fun theLedgerKeepsDeletedPasskeysAndFiltersToChanges() {
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { PasskeyHistorySection(history) {} } }
        }
        compose.onNodeWithText("Deleted on this device").assertExists()
        compose.onNodeWithText("Signed in to github.com with Chrome").assertExists()
        compose.onNodeWithText("Changes only").performClick()
        compose.onNodeWithText("Signed in to github.com with Chrome").assertDoesNotExist()
        compose.onNodeWithText("Created in Chrome").assertExists()
    }
}
