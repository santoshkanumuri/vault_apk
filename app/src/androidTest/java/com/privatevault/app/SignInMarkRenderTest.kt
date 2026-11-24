package com.privatevault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.SignInMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Sign-in provider marks draw on a real device, in Compose and as the bitmap Autofill shows: each tile has its brand
 * color, a glyph or letter in the middle, and the picker's reminder speaks the whole sentence.
 */
class SignInMarkRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyProviderDrawsItsBrandTileWithAGlyphOrLetter() {
        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                Surface {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Rows of 5: all 10 marks in one row (472 dp) would not fit a phone, and the last ones
                        // would get zero width.
                        SignInMarks.ALL.keys.chunked(5).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { ProviderMark(it, Modifier.testTag("mark-$it"), size = 40.dp) }
                            }
                        }
                        SignInReminderNote(SignInMethod("example.com", "github", "octocat", "id", "octocat", 1, 2, 1,
                            "2026-11-20T10:00:00Z", "pc"))
                    }
                }
            }
        }
        compose.waitForIdle()
        SignInMarks.ALL.forEach { (provider, mark) ->
            val image = compose.onNodeWithTag("mark-$provider").captureToImage().asAndroidBitmap()
            // A point between the edge (a hairline on dark tiles) and the glyph: the brand color.
            assertEquals(provider, mark.rgb, image.getPixel(image.width / 2, image.height / 8) and 0xFFFFFF)
            val ink = signInMarkInk(mark.rgb) and 0xFFFFFF
            val inked = (0 until image.width).any { x -> (0 until image.height).any { y -> (image.getPixel(x, y) and 0xFFFFFF) == ink } }
            assertTrue("$provider draws its glyph or letter", inked)
        }
        compose.onNodeWithContentDescription("Last time you used “Continue with GitHub” as octocat on example.com.").assertIsDisplayed()
    }

    @Test
    fun theAutofillBitmapMatchesTheTile() {
        SignInMarks.ALL.forEach { (provider, mark) ->
            val bitmap = signInMarkBitmap(provider, 96)
            assertEquals(provider, mark.rgb, bitmap.getPixel(48, 4) and 0xFFFFFF)
            val ink = signInMarkInk(mark.rgb) and 0xFFFFFF
            assertTrue("$provider has ink", (0 until 96).any { x -> (0 until 96).any { y -> (bitmap.getPixel(x, y) and 0xFFFFFF) == ink } })
            // The corners are transparent: a rounded tile.
            assertEquals(provider, 0, bitmap.getPixel(0, 0) ushr 24)
        }
    }
}
