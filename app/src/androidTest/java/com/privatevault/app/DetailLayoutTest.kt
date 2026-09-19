package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class DetailLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardsShowAllEntriesWithoutFolderControls() {
        val folder = VaultGroup(name = "Travel cards", folderType = EntryType.CARD)
        val card = EntryWithDetails(VaultEntry(type = EntryType.CARD, title = "Travel card"), emptyList(), listOf(folder))
        compose.setContent {
            MaterialTheme {
                CategoryCollection(VaultTab.CARDS, listOf(card), listOf(folder), null, "", "Default",
                    {}, {}, {}, {}, null, {}, { _, _ -> }, { it() }, Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("Travel card").assertIsDisplayed()
        compose.onNodeWithText("Folders").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add folder").assertDoesNotExist()
    }

    @Test fun collapsedActionDockIsVisibleAtCompactHeights() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        val height = mutableIntStateOf(420)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, height.intValue.dp).testTag("dockViewport")) {
                        EntryDetail(EntryWithDetails(VaultEntry(type = EntryType.NOTE, title = "Visible dock", notes = "Contents"), emptyList(), emptyList()),
                            model, { _, _ -> }, { it() }, {}, {}, Modifier.fillMaxSize())
                    }
                }
            }
        }
        for (screenHeight in listOf(320, 420)) {
            compose.runOnIdle { height.intValue = screenHeight }
            val viewport = compose.onNodeWithTag("dockViewport").fetchSemanticsNode().boundsInRoot
            val label = "Pull up to edit"
            val dock = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$label $dock must be displayed inside a $screenHeight dp detail screen $viewport",
                compose.onNodeWithText(label).isDisplayed() && dock.top >= viewport.top && dock.bottom <= viewport.bottom)
        }
        compose.onNodeWithContentDescription("Open entry actions").assertIsDisplayed().performClick()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close entry actions").assertIsDisplayed()
    }

    @Test fun detailActionsFitCompactHeightAndEnlargedText() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        val type = mutableStateOf(EntryType.NOTE)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 420.dp).testTag("viewport")) {
                        EntryDetail(EntryWithDetails(VaultEntry(type = type.value, title = "A longer entry title that must not push actions off screen", secondaryValue = "JBSWY3DPEHPK3PXP", notes = "Contents"), emptyList(), emptyList()),
                            model, { _, _ -> }, { it() }, {}, {}, Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.onNodeWithText("Pull up to edit").assertIsDisplayed().performClick()
        for (entryType in listOf(EntryType.NOTE, EntryType.PASSWORD, EntryType.AUTHENTICATOR)) {
            compose.runOnIdle { type.value = entryType }
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            for (label in listOf("Edit", "Delete", "Camera")) {
                compose.onNodeWithText(label).assertIsDisplayed()
                val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue("$entryType $label must be reachable inside the detail viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.left >= viewport.left && bounds.right <= viewport.right)
            }
        }
    }

    @Test fun entryDockStaysInsideTheActualSafeWindow() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("safeDetail")) {
                    EntryDetail(EntryWithDetails(VaultEntry(type = EntryType.NOTE, title = "Visible dock"), emptyList(), emptyList()),
                        model, { _, _ -> }, { it() }, {}, {}, Modifier.fillMaxSize())
                }
            }
        }
        val safeWindow = compose.onNodeWithTag("safeDetail").fetchSemanticsNode().boundsInWindow
        val dock = compose.onNodeWithText("Pull up to edit").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue("Dock $dock must end above the system navigation area $safeWindow", dock.bottom <= safeWindow.bottom)
    }

    @Test fun photoActionsStayAboveSystemNavigation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("safePhotoWindow")) {
                    PhotoViewer(VaultPhoto(entryId = "example", encryptedFileName = "missing"), model) {}
                }
            }
        }
        val safeWindow = compose.onNodeWithTag("safePhotoWindow").fetchSemanticsNode().boundsInWindow
        val delete = compose.onNodeWithText("Delete").fetchSemanticsNode().boundsInWindow
        assertTrue("Photo Delete $delete must display inside $safeWindow",
            compose.onNodeWithText("Delete").isDisplayed() && delete.bottom <= safeWindow.bottom)
    }

    @Test fun groupDeleteActionStaysAboveTheBottomEdge() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 420.dp).testTag("groupViewport")) {
                        GroupManager(listOf(VaultGroup(name = "Example group")), emptyList(), model,
                            { _, _ -> }, { it() }, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Example group").performClick()
        val viewport = compose.onNodeWithTag("groupViewport").fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithText("Delete group").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Delete group $delete must stay inside the group screen $viewport",
            delete.top >= viewport.top && delete.bottom <= viewport.bottom)
    }

    @Test fun settingsCanScrollToItsLastTileAndPrivacyPolicy() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        compose.setContent { MaterialTheme { SettingsDialog(model) {} } }
        compose.onNodeWithText("About").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Privacy policy").performScrollTo()
        val policy = compose.onNodeWithText("Privacy policy").fetchSemanticsNode().boundsInWindow
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .maxBy { it.boundsInWindow.width * it.boundsInWindow.height }.boundsInWindow
        assertTrue("Privacy policy $policy must display within Settings $root after scrolling",
            compose.onNodeWithText("Privacy policy").isDisplayed())
    }

    @Test fun editorActionsStayVisibleAtLargeTextSize() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        val type = mutableStateOf(EntryType.NOTE)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 420.dp).testTag("editorViewport")) {
                        if (type.value == EntryType.AUTHENTICATOR)
                            AuthenticatorEditor(null, emptyList(), emptySet(), model, {}, { _, _ -> })
                        else EntryEditor(null, type.value, emptyList(), emptySet(), model, {}, { _, _ -> })
                    }
                }
            }
        }
        for (entryType in listOf(EntryType.NOTE, EntryType.PASSWORD, EntryType.QUESTION, EntryType.CARD, EntryType.AUTHENTICATOR)) {
            compose.runOnIdle { type.value = entryType }
            val viewport = compose.onNodeWithTag("editorViewport").fetchSemanticsNode().boundsInRoot
            for (label in listOf("Back", if (entryType == EntryType.AUTHENTICATOR) "Save authenticator" else "Save entry")) {
                compose.onNodeWithContentDescription(label).assertIsDisplayed()
                val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
                assertTrue("$entryType $label must stay inside the editor viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.left >= viewport.left && bounds.right <= viewport.right)
            }
        }
        compose.runOnIdle { type.value = EntryType.NOTE }
        compose.onNodeWithText("Note title").performClick()
        compose.onNodeWithContentDescription("Save entry").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test fun editorSaveStaysVisibleWhenKeyboardOpens() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        val keyboardHeight = mutableIntStateOf(0)
        compose.setContent {
            val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { keyboardHeight.intValue = imeBottom }
            MaterialTheme { EntryEditor(null, EntryType.NOTE, emptyList(), emptySet(), model, {}, { _, _ -> }) }
        }
        compose.onNodeWithText("Note title").performClick()
        compose.waitUntil(5_000) { keyboardHeight.intValue > 0 }
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInWindow
        val save = compose.onNodeWithContentDescription("Save entry").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue("Save must stay in the resized window above the keyboard", save.top >= viewport.top && save.bottom <= viewport.bottom)
    }

    @Test fun editorKeepsReadableWidthOnLargeDisplay() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().testTag("wideViewport")) {
                        EntryEditor(null, EntryType.NOTE, emptyList(), emptySet(), model, {}, { _, _ -> })
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("wideViewport").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithText("Note title").fetchSemanticsNode().boundsInRoot
        assertTrue("Editor field must remain readable on a wide display", field.width <= 680f && kotlin.math.abs(field.center.x - viewport.center.x) < 2f)
    }
}
