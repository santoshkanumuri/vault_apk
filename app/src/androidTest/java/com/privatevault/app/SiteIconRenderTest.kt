package com.privatevault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Built-in website icons draw on a real device: GitHub and Netflix get their brand tile, a site
 * without an icon keeps its letter. Saves the row as site-icons.png for a visual check.
 */
class SiteIconRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun brandTilesReplaceLettersOnlyForKnownSites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SiteIconSettings.set(context, true)
        assertNotNull("the bundled icon set loads", runBlocking { SiteIcons.load(context) })

        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                Surface {
                    Row(Modifier.testTag("icons").padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NuvoriAvatar("GitHub", size = 48.dp, site = "https://github.com/login", modifier = Modifier.testTag("github"))
                        NuvoriAvatar("Netflix", size = 48.dp, site = "netflix.com", modifier = Modifier.testTag("netflix"))
                        NuvoriAvatar("Google", size = 48.dp, site = "accounts.google.com", modifier = Modifier.testTag("google"))
                        NuvoriAvatar("Bank of Example", size = 48.dp, site = "https://online.bank.example", modifier = Modifier.testTag("letter"))
                    }
                }
            }
        }
        compose.waitForIdle()

        val github = compose.onNodeWithTag("github").captureToImage().asAndroidBitmap()
        // A corner of the tile, away from the glyph: GitHub's brand color #181717.
        assertEquals(0x181717, github.getPixel(github.width / 2, 3) and 0xFFFFFF)
        val netflix = compose.onNodeWithTag("netflix").captureToImage().asAndroidBitmap()
        assertEquals(0xE50914, netflix.getPixel(netflix.width / 2, 3) and 0xFFFFFF)
        val letter = compose.onNodeWithTag("letter").captureToImage().asAndroidBitmap()
        val tint = letter.getPixel(letter.width / 2, 3) and 0xFFFFFF
        assertTrue("an unknown site keeps the letter tile, not a brand color", tint != 0x181717 && tint != 0xE50914)

        val directory = File(context.getExternalFilesDir(null), "site-icons").apply { mkdirs() }
        FileOutputStream(File(directory, "site-icons.png")).use { output ->
            compose.onNodeWithTag("icons").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
