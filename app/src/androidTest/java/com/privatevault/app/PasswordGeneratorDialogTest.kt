package com.privatevault.app

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.security.GeneratorMode
import com.privatevault.app.security.GeneratorSettingsStore
import com.privatevault.app.security.PasswordGenerator
import com.privatevault.app.security.WordList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** The generator dialog on a device: the result it hands back, the saved settings, and screenshots. */
class PasswordGeneratorDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var used: String? = null

    @Before @After fun clearSettings() {
        context.getSharedPreferences("password_generator", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show(dark: Boolean = false) = compose.setContent {
        NuvoriTheme(dark = dark) { PasswordGeneratorDialog(onDismiss = {}, onUse = { used = it }) }
    }

    @Test fun usesAPasswordWithTheDefaultSettings() {
        show()
        compose.onNodeWithText("Very strong · about 122 bits of randomness").assertExists()
        save("password-light.png")
        compose.onNodeWithText("Use password").performClick()
        val value = requireNotNull(used)
        assertEquals(20, value.length)
        assertEquals(GeneratorMode.PASSWORD, GeneratorSettingsStore(context).read().mode)
    }

    @Test fun aPassphraseIsRememberedForTheNextOneTapGeneration() {
        show(dark = true)
        compose.onNodeWithText("Passphrase").performClick()
        compose.onNodeWithText("Strong · about 70 bits of randomness").assertExists()
        save("passphrase-dark.png")
        compose.onNodeWithText("Use passphrase").performClick()
        val words = WordList.load(context)
        val parts = requireNotNull(used).split("-")
        assertEquals(5, parts.size)
        assertTrue(parts.all { it.trimEnd(Char::isDigit).lowercase() in words })
        // Autofill's one-tap generation now makes a passphrase too.
        assertEquals(5, PasswordGenerator.generateWithSavedSettings(context).split("-").size)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "password-generator").apply { mkdirs() }
        FileOutputStream(File(directory, name)).use { output ->
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
