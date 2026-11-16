package com.privatevault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.CardKind
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class CardPickerUiTest {
    @get:Rule val compose = createComposeRule()
    private var includeCode by mutableStateOf(true)
    private var filled: String? = null

    private val cards = listOf(
        VaultEntry(id = "travel", type = EntryType.CARD, title = "Travel card", primaryValue = "4111111111111111", secondaryValue = "J DOE",
            tertiaryValue = "08/29", fourthValue = "123", network = "Visa", color = 0xFF315B8CL),
        VaultEntry(id = "groceries", type = EntryType.CARD, title = "Groceries debit", primaryValue = "5555555555554444", secondaryValue = "J DOE",
            tertiaryValue = "11/27", fourthValue = "456", network = "", cardKind = CardKind.DEBIT, color = 0xFFF5EBDDL),
        VaultEntry(id = "everyday", type = EntryType.CARD, title = "Everyday credit", primaryValue = "378282246310005", secondaryValue = "JAMIE DOE",
            tertiaryValue = "03/28", fourthValue = "", network = "American Express", color = 0xFF202020L),
    )

    private fun show(dark: Boolean, asksForCode: Boolean = true, warning: String? = null) = compose.setContent {
        NuvoriTheme(dark = dark) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.testTag("picker").width(400.dp).padding(16.dp)) {
                    CardFillContent(cards, asksForCode, includeCode, { includeCode = it }, busy = false, embeddedWarning = warning,
                        onFill = { filled = it.id }, onOpenNuvori = {})
                }
            }
        }
    }

    @Test fun showsEachCardWithOnlyItsLastFourDigits() {
        show(dark = false)
        compose.onNodeWithText("Visa •••• 1111 · 08/29").assertExists()
        // The network comes from the number when the card has none saved.
        compose.onNodeWithText("Mastercard •••• 4444 · 11/27").assertExists()
        compose.onNodeWithText("American Express •••• 0005 · 03/28").assertExists()
        compose.onNodeWithText("4111111111111111", substring = true).assertDoesNotExist()
    }

    @Test fun tappingACardFillsIt() {
        show(dark = false)
        compose.onNodeWithContentDescription("Fill Groceries debit, Mastercard •••• 4444 · 11/27").performClick()
        compose.waitForIdle()
        assertEquals("groceries", filled)
    }

    @Test fun theSecurityCodeSwitchShowsOnlyWhenTheFormAsksForOne() {
        show(dark = false)
        compose.onNode(isToggleable()).assertIsOn().performClick()
        compose.onNode(isToggleable()).assertIsOff()
        assertEquals(false, includeCode)
    }

    @Test fun noSwitchWithoutASecurityCodeField() {
        show(dark = false, asksForCode = false)
        compose.onNodeWithText("Also fill the security code").assertDoesNotExist()
    }

    @Test fun screenshots() {
        show(dark = false)
        save("card-picker-light.png")
    }

    @Test fun screenshotDarkWithWarning() {
        show(dark = true, warning = "Example App (com.example.app) receives the card you fill and can read it. Choose a card only if you trust this app.")
        save("card-picker-dark-embedded.png")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "card-picker").apply { mkdirs() }
        FileOutputStream(File(directory, name)).use { output ->
            compose.onNodeWithTag("picker").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
