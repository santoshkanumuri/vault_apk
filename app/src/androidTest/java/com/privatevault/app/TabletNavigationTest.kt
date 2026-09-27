package com.privatevault.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class TabletNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newItemUsesRightPaneAndKeepsDraftAcrossNavigationAndResize() {
        val model = VaultViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        val width = mutableIntStateOf(1000)
        var keyboard: SoftwareKeyboardController? = null
        var imeBottom = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                keyboard = LocalSoftwareKeyboardController.current
                val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { imeBottom = bottom }
                MaterialTheme {
                    Box(Modifier.requiredSize(width.intValue.dp, 800.dp)) {
                        VaultHome(model, { _, _ -> }, { it() }, FirstRunChoice.NEW, {})
                    }
                }
            }
        }
        compose.onNode(hasText("Notes") and hasAnyAncestor(hasTestTag("vaultSidebar"))).performClick()
        compose.onNodeWithContentDescription("Add note").performClick()
        val list = compose.onNodeWithTag("entryListPane").fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag("entryDetailPane").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Note title").fetchSemanticsNode().boundsInRoot
        assertTrue(title.left >= pane.left && title.right <= pane.right && pane.left >= list.right)
        compose.onNodeWithText("Note title").performTextInput("Unsaved tablet note")
        // The phone's keyboard uses native density; close it before testing the synthetic tablet viewport.
        compose.waitUntil(5_000) { imeBottom > 0 }
        compose.runOnIdle { keyboard?.hide() }
        compose.waitUntil(5_000) { imeBottom == 0 }
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "tablet-editor-review.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${screenshot.absolutePath} /sdcard/nuvori-tablet-review.png"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNode(hasText("Passwords") and hasAnyAncestor(hasTestTag("vaultSidebar"))).performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("Discard this draft?").isDisplayed() }
        compose.onNodeWithText("Discard this draft?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Unsaved tablet note").assertExists()
        compose.runOnIdle { width.intValue = 600 }
        compose.onNodeWithTag("vaultSidebar").assertDoesNotExist()
        compose.onNodeWithText("Unsaved tablet note").assertExists()
        compose.runOnIdle { width.intValue = 1000 }
        compose.onNodeWithTag("entryListPane").assertExists()
        compose.onNodeWithText("Unsaved tablet note").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Note title").assertDoesNotExist()
    }

    @Test fun tabletSidebarUsesLogoAndOpensPasskeysDirectly() {
        val model = VaultViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(1000.dp, 800.dp)) {
                        VaultHome(model, { _, _ -> }, { it() }, FirstRunChoice.NEW, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Nuvori").assertDoesNotExist()
        compose.onNodeWithContentDescription("Nuvori logo").assertExists()
        compose.onNodeWithText("Security questions").assertExists()
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Passkeys").performScrollTo().performClick()
        compose.onNodeWithText("Create passkeys from a supported website in Chrome or Brave. They are encrypted with your vault and included in backups. Deleting one here does not remove its registration on the website.")
            .assertExists()
    }

    @Test fun backFromTabletSidebarReturnsHomeWithoutSettingsDetour() {
        val model = VaultViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(1000.dp, 800.dp)) {
                        VaultHome(model, { _, _ -> }, { it() }, FirstRunChoice.NEW, {})
                    }
                }
            }
        }
        listOf("Notes", "Security questions").forEach { label ->
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("vaultSidebar")))
                .performScrollTo().performClick()
            compose.onNodeWithText("Folders").assertExists()
            Espresso.pressBack()
            compose.onNode(hasText("Home") and !hasAnyAncestor(hasTestTag("vaultSidebar")))
                .assertExists()
            compose.onNodeWithText("Folders").assertDoesNotExist()
        }

        compose.onNode(hasText("Passkeys") and hasAnyAncestor(hasTestTag("vaultSidebar")))
            .performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Home") and !hasAnyAncestor(hasTestTag("vaultSidebar")))
            .assertExists()
        compose.onNodeWithText("Appearance").assertDoesNotExist()
    }
}
