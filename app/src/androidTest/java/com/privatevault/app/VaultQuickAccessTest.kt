package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VaultQuickAccessTest {
    @get:Rule val compose = createComposeRule()
    private val login = VaultEntry(id = "login", type = EntryType.PASSWORD, title = "Mail login",
        primaryValue = "user@example.com", secondaryValue = "never-render-this-secret", tertiaryValue = "https://example.com",
        linkedAuthenticatorId = "code")
    private val linkedCode = VaultEntry(id = "code", type = EntryType.AUTHENTICATOR, title = "Linked TOTP",
        secondaryValue = "JBSWY3DPEHPK3PXP", linkedApps = "com.example.app")
    private val otherCode = linkedCode.copy(id = "other-code", title = "Other TOTP", linkedApps = "")

    @Test fun autofillDisplayChoicePersistsBothModes() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("vault_preferences", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getBoolean("autofill_keyboard_suggestions", true)
        try {
            compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AutofillPreference() } } }
            compose.onNodeWithText("Account picker").performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle { assertFalse(preferences.getBoolean("autofill_keyboard_suggestions", true)) }
            compose.onNodeWithText("Keyboard suggestions").performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle { assertTrue(preferences.getBoolean("autofill_keyboard_suggestions", false)) }
        } finally { preferences.edit().putBoolean("autofill_keyboard_suggestions", previous).commit() }
    }

    @Test fun autofillAccountRowKeepsSecretOutOfUiAndSelectsOnTap() {
        var selected = false
        compose.setContent {
            MaterialTheme { AutofillAccountRow(login, "Fill login") { selected = true } }
        }
        compose.onNodeWithText(login.primaryValue).assertExists()
        compose.onNodeWithText(login.title).assertExists()
        compose.onNodeWithText(login.secondaryValue, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Fill login").performClick()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test fun passwordTabSearchesAccountsAndCopiesOnlySelectedField() {
        var selection: Pair<VaultEntry, QuickField>? = null
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    VaultQuickAccessContent(listOf(login, linkedCode), null) { entry, field -> selection = entry to field }
                }
            }
        }
        compose.onNodeWithText("Passwords").performClick()
        compose.onNodeWithText("Search passwords").performTextInput("example.com")
        compose.onNodeWithText(login.title).assertExists()
        compose.onNodeWithText(login.secondaryValue).assertDoesNotExist()
        compose.onNodeWithText(linkedCode.title).assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy username for Mail login").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(login to QuickField.USERNAME, selection) }
        compose.onNodeWithContentDescription("Copy password for Mail login").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(login to QuickField.PASSWORD, selection) }
        compose.onNodeWithText("Search passwords").performTextReplacement(login.secondaryValue)
        compose.onNodeWithText(login.title).assertDoesNotExist()
        compose.onNodeWithText("No matching accounts.").assertExists()
    }

    @Test fun totpSearchIncludesAccountsOutsidePreviousAppSuggestions() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    VaultQuickAccessContent(listOf(login, linkedCode, otherCode), "com.example.app") { _, _ -> }
                }
            }
        }
        compose.onNodeWithText(linkedCode.title).assertExists()
        compose.onNodeWithText(otherCode.title).assertDoesNotExist()
        compose.onNodeWithText("Search TOTP accounts").performTextInput("Other")
        compose.onNodeWithText(otherCode.title).assertExists()
        compose.onNodeWithText(login.title).assertDoesNotExist()
        compose.onNodeWithText("Passwords").performClick()
        compose.onNodeWithText(login.title).assertExists()
    }

    @Test fun cardAndQuestionCategoriesCopyEachFieldWithoutRenderingSecrets() {
        val card = VaultEntry(id = "card", type = EntryType.CARD, title = "Travel Visa", primaryValue = "4111111111111111",
            secondaryValue = "A Person", tertiaryValue = "08/29", fourthValue = "987")
        val question = VaultEntry(id = "q", type = EntryType.QUESTION, title = "Bank", primaryValue = "First pet?", secondaryValue = "never-render-answer")
        val copied = mutableListOf<Pair<String, QuickField>>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    VaultQuickAccessContent(listOf(login, linkedCode, card, question), null) { entry, field -> copied += entry.id to field }
                }
            }
        }
        compose.onNodeWithText("Cards").performClick()
        compose.onNodeWithText("Travel Visa").assertExists()
        compose.onNodeWithText("987", substring = true).assertDoesNotExist()
        compose.onNodeWithText("4111111111111111", substring = true).assertDoesNotExist()
        listOf("card number", "expiry", "name on card", "CVV").forEach {
            compose.onNodeWithContentDescription("Copy $it for Travel Visa").performScrollTo().performClick()
        }
        compose.onNodeWithText("Questions").performClick()
        compose.onNodeWithText("First pet?").assertExists()
        compose.onNodeWithText(question.secondaryValue).assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy answer for Bank").performClick()
        compose.onNodeWithText("Passwords").performClick()
        compose.onNodeWithContentDescription("Copy authenticator code for Mail login").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("card" to QuickField.CARD_NUMBER, "card" to QuickField.EXPIRY, "card" to QuickField.NAME,
                "card" to QuickField.CVV, "q" to QuickField.ANSWER, "login" to QuickField.CODE), copied)
        }
    }
}
