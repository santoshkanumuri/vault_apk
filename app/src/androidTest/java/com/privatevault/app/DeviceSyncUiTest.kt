package com.privatevault.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.sync.DevicePeerStatus
import com.privatevault.app.sync.DeviceSyncPhase
import com.privatevault.app.sync.DeviceSyncStatus
import com.privatevault.app.sync.SyncConflictReview
import com.privatevault.app.sync.SyncConflictSide
import com.privatevault.app.sync.PeerSyncCounts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class DeviceSyncUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowDeviceCardShowsStageHelpAndRemoval() {
        val device = SyncMembershipEntity("vault", "peer-12345678", "Living room tablet", "key",
            "ACTIVE", "owner", 1, 1)
        val removed = mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = VaultColors) {
                Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DeviceSyncState(DeviceSyncStatus(DeviceSyncPhase.TRANSFERRING,
                        "Connected securely. Sending and receiving encrypted changes."), 1, 1)
                    DeviceSyncPeerCard(device, false,
                        DevicePeerStatus(DeviceSyncPhase.TRANSFERRING, "Encrypted changes are being exchanged."),
                        0, 0, "This device's changes: 2 received, 1 applied", "192.168.1.12",
                        true, true, {}, { removed.value = true })
                }
            }
        }
        compose.onNodeWithText("Syncing changes").assertExists()
        compose.onNodeWithText("Devices").assertExists()
        compose.onNodeWithText("Living room tablet").assertExists()
        compose.onAllNodesWithText("Syncing", useUnmergedTree = true).assertCountEquals(2)
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null), "sync-ui-review.png")
        screenshot.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${screenshot.absolutePath} /sdcard/nuvori-sync-ui-review.png"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Connection help").performScrollTo()
        val peerScreenshot = File(screenshot.parentFile, "sync-peer-review.png")
        peerScreenshot.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${peerScreenshot.absolutePath} /sdcard/nuvori-sync-peer-review.png"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Connection help").performClick()
        compose.onNodeWithText("Device Wi-Fi IP address").assertExists()
        compose.onNodeWithText("Remove device").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, removed.value) }
    }

    @Test fun conflictChoiceRequiresExplicitConfirmation() {
        val conflict = SyncConflictReview("one", "password",
            SyncConflictSide("Old title", "This phone", "2026-09-25", false),
            SyncConflictSide("Deleted record", "Living room tablet", "2026-09-26", true), "v1")
        val chosen = mutableStateOf<Boolean?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                ConflictReviewSheet(conflict, {}, { chosen.value = it })
            }
        }
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null), "sync-conflict-review.png")
        screenshot.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${screenshot.absolutePath} /sdcard/nuvori-sync-conflict-review.png"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Use incoming version").performScrollTo().performClick()
        compose.onNodeWithText("This removes the record on paired devices.").assertExists()
        compose.runOnIdle { assertEquals(null, chosen.value) }
        compose.onNodeWithText("Confirm choice").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, chosen.value) }
    }

    @Test fun thisDeviceCanBeNamed() {
        val device = SyncMembershipEntity("vault", "phone-12345678", "Android device", "key",
            "ACTIVE", "owner", 1, 1)
        val saved = mutableStateOf("")
        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                DeviceSyncSelfCard(device, true) { saved.value = it }
            }
        }
        compose.onNodeWithText("This device").assertExists()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Device name").performTextReplacement("My phone")
        compose.onNodeWithText("Save name").performClick()
        compose.runOnIdle { assertEquals("My phone", saved.value) }
    }

    @Test fun completedExchangeWithPendingChangesStaysVisible() {
        val device = SyncMembershipEntity("vault", "peer-12345678", "Tablet", "key",
            "ACTIVE", "owner", 1, 1)
        compose.setContent {
            MaterialTheme(colorScheme = VaultLightColors) {
                DeviceSyncPeerCard(device, false, DevicePeerStatus(DeviceSyncPhase.CHECKED,
                    "Encrypted exchange completed."), 1, 1, "", "", false, false, {}, {},
                    PeerSyncCounts(2, 0, 0, 0), advanced = false)
            }
        }
        compose.onNodeWithText("Changes waiting").assertExists()
        compose.onNodeWithText("2 changes from this device still need to reach it.").assertExists()
    }
}
