package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
                    EntryEditor(null, type.value, emptyList(), emptySet(), model, {}, { _, _ -> })
                }
            }
        }
        for (entryType in listOf(EntryType.NOTE, EntryType.PASSWORD, EntryType.QUESTION, EntryType.CARD)) {
            compose.runOnIdle { type.value = entryType }
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsDisplayed()
            val height = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels
            for (label in listOf("Cancel", "Save")) {
                val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInWindow
                assertTrue("$entryType $label is below the screen", bounds.top >= 0 && bounds.bottom <= height)
            }
        }
        compose.runOnIdle { type.value = EntryType.NOTE }
        compose.onNodeWithText("Note title").performClick()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
