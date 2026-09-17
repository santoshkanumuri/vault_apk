package com.privatevault.app.security

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import com.privatevault.app.TotpTile
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MaskedCodeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun copyDoesNotRevealAndShowCanBeReversed() {
        val entry = VaultEntry(type = EntryType.AUTHENTICATOR, title = "Dummy", secondaryValue = "JBSWY3DPEHPK3PXP")
        var copied: String? = null
        compose.setContent { MaterialTheme { TotpTile(entry, { _, value -> copied = value }, {}, initiallyMasked = true) } }
        compose.onNodeWithText("••• •••").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Copy authenticator code").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Copy authenticator code").performClick()
        compose.runOnIdle { assertTrue(copied?.matches(Regex("[0-9]{6}")) == true) }
        compose.onNodeWithText("••• •••").assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText("••• •••").assertDoesNotExist()
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("••• •••").assertIsDisplayed()
    }
}
