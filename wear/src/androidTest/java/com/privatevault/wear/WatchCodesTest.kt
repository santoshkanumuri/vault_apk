package com.privatevault.wear

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.privatevault.app.watch.WatchAccount
import com.privatevault.app.watch.WatchSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class WatchCodesTest {
    @get:Rule val compose = createComposeRule()

    private val snapshot = WatchSnapshot("sample", listOf(
        account("a", "Amazon"), account("g", "Google"), account("h", "GitHub"),
    ))

    @Test fun setupMessageUsesVisibleTextOnDarkBackground() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { WatchCodes(null, true) {} } }
        val message = compose.onNodeWithText("Open Nuvori on your phone, then choose Watch codes to connect.")
        message.assertIsDisplayed()
        val pixels = message.captureToImage().toPixelMap()
        var brightPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .65f && color.green > .65f && color.blue > .65f) brightPixels++
        }
        assertTrue("Setup text must remain readable on a dark watch screen", brightPixels > 100)
    }

    @Test fun letterCountsOpenOnlyMatchingCodesAndAllOpensEverything() {
        compose.setContent { MaterialTheme { WatchCodes(snapshot, true) {} } }
        compose.onNodeWithContentDescription("All, 3 codes").assertExists()
        compose.onNodeWithTag("letterIndex").performScrollToNode(hasContentDescription("A, 1 code"))
        compose.onNodeWithContentDescription("A, 1 code").assertExists()
        compose.onNodeWithTag("letterIndex").performScrollToNode(hasContentDescription("G, 2 codes"))
        compose.onNodeWithContentDescription("G, 2 codes").assertExists().performClick()
        compose.onNodeWithText("G codes").assertExists()
        compose.onNodeWithText("Amazon").assertDoesNotExist()
        compose.onNodeWithText("‹ Letters").performClick()
        compose.onNodeWithContentDescription("All, 3 codes").performClick()
        compose.onNodeWithText("All codes").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun dialOpensSelectedLetterAfterTwoSecondPause() {
        compose.setContent { MaterialTheme { WatchCodes(snapshot, true) {} } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("letterIndex").performRotaryScrollInput {
            rotateToScrollVertically(28.dp.toPx() + 1f)
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("A codes").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("A codes").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun dialScrollsWithinTheCodeList() {
        val many = WatchSnapshot("sample", (1..12).map { account("$it", "Account $it") })
        compose.setContent { MaterialTheme { WatchCodes(many, true) {} } }
        compose.onNodeWithContentDescription("All, 12 codes").performClick()
        compose.onNodeWithTag("codeList").performRotaryScrollInput {
            rotateToScrollVertically(5_000f)
        }
        compose.onNodeWithText("Account 9").assertExists()
    }

    private fun account(id: String, name: String) = WatchAccount(id, name, "person@example.com",
        "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
}
