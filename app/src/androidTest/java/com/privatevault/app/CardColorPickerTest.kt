package com.privatevault.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class CardColorPickerTest {
    @get:Rule val compose = createComposeRule()
    private var color by mutableStateOf(0xFF08704AL)
    private val used = listOf(0xFF315B8CL, 0xFFF5EBDDL, 0xFF722F99L)

    private fun show(dark: Boolean = false) = compose.setContent {
        NuvoriTheme(dark = dark) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.testTag("picker").width(380.dp).padding(16.dp)) {
                    CardColorPicker(color = color, onColor = { color = it }, used = used, title = "Travel", lastDigits = "4242")
                }
            }
        }
    }

    private fun hue() = CardColorMath.argbToHsv(color)[0]

    @Test fun showsTheCurrentColorAsHex() {
        show()
        compose.onNodeWithText("#08704A").assertExists()
    }

    @Test fun typingAHexValueAppliesIt() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("#E4B028")
        compose.waitForIdle()
        assertEquals(0xFFE4B028L, color)
        compose.onNode(hasSetTextAction()).performTextReplacement("12")
        compose.waitForIdle()
        assertEquals("an unfinished value changes nothing", 0xFFE4B028L, color)
    }

    @Test fun theHueBarAndTheSquarePickAnyColor() {
        show()
        compose.onNodeWithContentDescription("Hue").performTouchInput { click(Offset(width * 2 / 3f, height / 2f)) }
        compose.waitForIdle()
        assertTrue("hue near blue, was ${hue()}", hue() in 230f..250f)
        // Top right of the square: full saturation and full brightness of that hue.
        val before = CardColorMath.toHex(color)
        val square = compose.onNodeWithContentDescription("Saturation and brightness")
        val bounds = square.fetchSemanticsNode().boundsInRoot
        square.performTouchInput { click(Offset(width - 1f, 1f)) }
        compose.waitForIdle()
        val hsv = CardColorMath.argbToHsv(color)
        assertTrue("saturation ${hsv[1]} (before $before, after ${CardColorMath.toHex(color)}, bounds $bounds)", hsv[1] > 0.97f)
        assertTrue("brightness ${hsv[2]}", hsv[2] > 0.97f)
        compose.onNodeWithText(CardColorMath.toHex(color)).assertExists()
    }

    @Test fun accessibilityServicesCanSetTheHue() {
        show()
        compose.onNodeWithContentDescription("Hue").performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        compose.waitForIdle()
        assertTrue("hue ${hue()}", hue() in 118f..122f)
    }

    @Test fun aColorFromAnotherCardIsOneTapAway() {
        show()
        compose.onNodeWithContentDescription("#722F99").performClick()
        compose.waitForIdle()
        assertEquals(0xFF722F99L, color)
    }

    @Test fun screenshots() {
        show(dark = false)
        save("light.png")
        color = 0xFF315B8CL
        compose.waitForIdle()
        save("light-denim.png")
    }

    @Test fun screenshotDark() {
        color = 0xFFF5EBDDL
        show(dark = true)
        save("dark-cream.png")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "card-color-picker").apply { mkdirs() }
        FileOutputStream(File(directory, name)).use { output ->
            compose.onNodeWithTag("picker").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
