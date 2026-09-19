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
        for (entryType in listOf(EntryType.NOTE, EntryType.PASSWORD, EntryType.AUTHENTICATOR)) {
            compose.runOnIdle { type.value = entryType }
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            for (label in listOf("Edit", "Delete", "Camera")) {
                compose.onNodeWithText(label).assertIsDisplayed()
                val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue("$entryType $label must be fully inside the detail viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.left >= viewport.left && bounds.right <= viewport.right)
            }
        }
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
