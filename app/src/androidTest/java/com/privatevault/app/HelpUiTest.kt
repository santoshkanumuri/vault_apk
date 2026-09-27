package com.privatevault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HelpUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun syncAnswerOpensTheRelevantSettingsPage() {
        val destination = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HelpPage { destination.value = it }
                }
            }
        }
        compose.onNodeWithText("Which device shows the pairing QR?").performClick()
        compose.onNodeWithText("It shows the QR.", substring = true).assertExists()
        compose.onNodeWithText("Open Android devices").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Android devices", destination.value) }
        compose.onNodeWithText("How do I change the theme?").performScrollTo().performClick()
        compose.onNodeWithText("Open Appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Appearance", destination.value) }
    }
}
