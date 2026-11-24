package com.privatevault.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.backup.RestoreGroupRole
import com.privatevault.app.backup.RestoreSyncImpact
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.SiteIconPurge
import com.privatevault.app.data.SyncedSiteIcon
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.ItemUsageStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A picker result that arrives after the vault locked is kept (as an address only) and continues after unlock;
 * the restore review and the lock screen say what a restore does; the card face masks the number; stale
 * website icons and the "Most used" counts can go. Synthetic data only.
 */
class PickerAndRestoreUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val backup = Uri.parse("content://com.example.synthetic/backup.pvault")

    @Before fun cleanStart() {
        app.deleteDatabase("vault.db")
        app.getSharedPreferences("vault_bootstrap", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("vault_biometric_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Every model a test made. Each is locked before the database goes, so no late write hits a new file. */
    private val models = mutableListOf<VaultViewModel>()
    private fun model() = VaultViewModel(app).also { models += it }

    @After fun cleanFinish() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.forEach { it.lock(LockReason.MANUAL) } }
        cleanStart()
    }

    private fun unlockedModel(): VaultViewModel {
        val model = model()
        compose.runOnIdle { model.setup("long test passphrase".toCharArray()) }
        compose.waitUntil(90_000) { model.status.value is VaultStatus.Unlocked }
        return model
    }

    @Test fun aBackupChosenWhileTheVaultLockedContinuesAfterUnlock() {
        val model = model()
        compose.setContent { PrivateVaultApp(model, false, {}, {}, {}, { _, _ -> }) }
        compose.runOnIdle { model.setup("long test passphrase".toCharArray()) }
        compose.waitUntil(90_000) { model.status.value is VaultStatus.Unlocked }
        // The screen turned off while the file picker was open; then the person chose the backup.
        compose.runOnIdle {
            model.lock(LockReason.SCREEN_OFF)
            assertFalse(model.pickerReturned(PickerAction.RESTORE_BACKUP, backup))
            assertEquals(PendingPick(PickerAction.RESTORE_BACKUP, backup), model.pendingPick.value)
            assertEquals(PickerResults.lockedNotice(PickerAction.RESTORE_BACKUP, true), model.notice.value?.text)
            // Nothing is handed out while the vault is locked.
            assertNull(model.takePendingPick())
        }
        compose.onNodeWithText("Unlock vault").assertIsDisplayed()
        compose.runOnIdle { model.unlock("long test passphrase".toCharArray()) }
        compose.waitUntil(90_000) { model.status.value is VaultStatus.Unlocked }
        // After unlock, Backup and import opens and asks for the backup password again (it never outlived the lock).
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Check backup").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The vault locked while you chose the backup. Enter its password again to continue.").assertExists()
        compose.runOnIdle { assertNull(model.pendingPick.value) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Check backup").assertDoesNotExist()
    }

    @Test fun aPhotoChosenWhileLockedIsNotKeptButSaysSo() {
        val model = unlockedModel()
        compose.runOnIdle {
            assertTrue(model.pickerReturned(PickerAction.EXPORT_BACKUP, backup))
            assertFalse("A cancelled picker never continues", model.pickerReturned(PickerAction.EXPORT_BACKUP, null))
            model.lock(LockReason.INACTIVITY)
            assertFalse(model.pickerReturned(PickerAction.PHOTO, backup))
            assertNull(model.pendingPick.value)
            assertEquals(PickerResults.lockedNotice(PickerAction.PHOTO, true), model.notice.value?.text)
            assertFalse(model.externalScreenReturned())
        }
    }

    @Test fun theLockScreenSaysTheBackupWasRestored() {
        compose.setContent {
            MaterialTheme { UnlockScreen(model(), VaultStatus.Locked(LockReason.RESTORED, false), false) {} }
        }
        compose.onNodeWithText("The backup was restored. Unlock with your current master password, not the backup's.").assertIsDisplayed()
        compose.onNodeWithText("The vault locked when you left the app.", substring = true).assertDoesNotExist()
    }

    @Test fun theRestoreReviewSaysTheDeviceLeavesItsGroup() {
        val impact = mutableStateOf(RestoreSyncImpact(RestoreGroupRole.MEMBER, 2))
        var moved = false
        var replaced = false
        var exported = false
        compose.setContent {
            MaterialTheme {
                RestoreReviewDialog("The backup has 2 items.", impact.value, canMoveRole = true,
                    onReplace = { replaced = true }, onMoveRole = { moved = true }, onExportFirst = { exported = true }, onCancel = {})
            }
        }
        compose.onNodeWithText("This device will leave its sync group.").assertIsDisplayed()
        compose.onNodeWithText("Move managing role first").assertDoesNotExist()
        compose.onNodeWithText("unlock with your current master password, not the backup's.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Export a backup first").performClick()
        compose.runOnIdle { assertTrue(exported) }
        compose.onNodeWithText("Replace vault").performClick()
        compose.runOnIdle { assertTrue(replaced); impact.value = RestoreSyncImpact(RestoreGroupRole.MANAGER, 1) }
        compose.onNodeWithText("This device will leave its sync group without a managing device.").assertIsDisplayed()
        compose.onNodeWithText("Restore anyway").assertIsDisplayed()
        compose.onNodeWithText("Move managing role first").performClick()
        compose.runOnIdle { assertTrue(moved); impact.value = RestoreSyncImpact.NONE }
        compose.onNodeWithText("This device will leave its sync group.", substring = true).assertDoesNotExist()
    }

    @Test fun theCardFaceMasksTheNumberUntilShowIsTapped() {
        val card = VaultEntry(id = "card", type = EntryType.CARD, title = "Test Visa", primaryValue = "4242424242424242",
            secondaryValue = "Alex Rivera", tertiaryValue = "1229", fourthValue = "123")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    EntryDetail(EntryWithDetails(card, emptyList(), emptyList()), model(), { _, _ -> }, { it() },
                        {}, {}, Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithText("4242 4242 4242 4242").assertDoesNotExist()
        compose.onNodeWithText("•••• •••• •••• 4242").assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText("4242 4242 4242 4242").assertIsDisplayed()
        compose.onNodeWithText("123").assertIsDisplayed()
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("4242 4242 4242 4242").assertDoesNotExist()
    }

    @Test fun openingTheVaultDropsIconsOfSitesWithoutALogin() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, VaultDatabase::class.java).build()
        try {
            db.dao().insertEntry(VaultEntry(type = EntryType.PASSWORD, title = "Example", primaryValue = "alex.rivera",
                secondaryValue = "synthetic-password", tertiaryValue = "https://accounts.example.com/login"))
            db.dao().insertEntry(VaultEntry(type = EntryType.NOTE, title = "gone.example is only in a note",
                tertiaryValue = "https://gone.example"))
            listOf("example.com", "gone.example").forEach {
                db.syncedSiteIcons().save(SyncedSiteIcon(it, byteArrayOf(1), "2026-11-22T00:00:00Z", 1L, "pc"))
            }
            val kept = SiteIconPurge.purge(db)
            assertEquals(listOf("example.com"), kept.map { it.site })
            assertEquals(listOf("example.com"), db.syncedSiteIcons().all().map { it.site })
        } finally { db.close() }
    }

    @Test fun clearMostUsedForgetsTheCountsIncludingWritesStillQueued() {
        ItemUsageStore.clear(app)
        repeat(3) { ItemUsageStore.record(app, "login-a") }
        var cleared = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    AutofillPreference(onUsageCleared = { cleared = true })
                }
            }
        }
        compose.onNodeWithText("Clear Most used").performScrollTo().performClick()
        compose.waitUntil(5_000) { cleared }
        assertEquals(0.0, ItemUsageStore.snapshot(app).score("login-a"), 0.0)
        ItemUsageStore.clear(app)
    }
}
