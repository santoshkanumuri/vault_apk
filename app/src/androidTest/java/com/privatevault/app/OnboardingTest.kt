package com.privatevault.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nextBackAndImportChoiceReachSetup() {
        val page = mutableIntStateOf(0)
        var selected: FirstRunChoice? = null
        compose.setContent {
            MaterialTheme {
                OnboardingScreen(page.intValue, { page.intValue = it }, { selected = it }, {})
            }
        }
        compose.onNodeWithText("Your essentials, together").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Private by design").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous page").performClick()
        compose.onNodeWithText("Your essentials, together").assertIsDisplayed()
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Ready to start?").assertIsDisplayed()
        compose.onNodeWithText("Import browser passwords").performClick()
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle { assertEquals(FirstRunChoice.BROWSER_IMPORT, selected) }
    }

    @Test fun startStaysVisibleOnShortScreenWithLargeText() {
        val page = mutableIntStateOf(3)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 420.dp).testTag("introViewport")) {
                        OnboardingScreen(page.intValue, { page.intValue = it }, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Start").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("introViewport").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithText("Start").fetchSemanticsNode().boundsInRoot
        assertTrue("Start must stay inside the onboarding screen", start.top >= viewport.top && start.bottom <= viewport.bottom)
    }

    @Test fun browserImportChoiceOpensAtTheCsvAction() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val model = VaultViewModel(app)
        compose.setContent {
            MaterialTheme { SettingsDialog(model, FirstRunChoice.BROWSER_IMPORT) {} }
        }
        compose.onNodeWithText("Choose password CSV").assertIsDisplayed()
    }
}
