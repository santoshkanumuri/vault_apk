package com.privatevault.app

import com.privatevault.app.data.SiteIconPurge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file chosen while the vault locked is kept (as an address only) and continues on the right page. */
class PickerResultsTest {
    @Test fun everyResumableActionHasAPageThatContinuesIt() {
        PickerAction.entries.forEach { action ->
            assertEquals("$action", action.resumable, PickerResults.settingsPage(action) != null)
        }
        assertEquals("Backup and import", PickerResults.settingsPage(PickerAction.RESTORE_BACKUP))
        assertEquals("Backup and import", PickerResults.settingsPage(PickerAction.EXPORT_BACKUP))
        assertEquals("Backup and import", PickerResults.settingsPage(PickerAction.IMPORT_PASSWORDS))
        assertEquals("Import authenticator codes", PickerResults.settingsPage(PickerAction.AUTHENTICATOR_FILE))
        assertNull(PickerResults.settingsPage(PickerAction.PHOTO))
    }

    @Test fun theLockScreenSaysWhatHappensToTheChosenFile() {
        assertTrue(PickerResults.lockedNotice(PickerAction.RESTORE_BACKUP, true).endsWith("Unlock to continue with the file you chose."))
        assertTrue(PickerResults.lockedNotice(PickerAction.PHOTO, true).endsWith("Unlock, then add the photo again."))
        assertTrue(PickerResults.lockedNotice(PickerAction.OTHER, false).endsWith("Unlock, then try again."))
    }
}

/** Synced website icons of sites with no login or passkey are dropped when the vault opens. */
class SiteIconPurgeTest {
    @Test fun keepsIconsALoginOrPasskeyWouldShowAndDropsTheRest() {
        val icons = listOf("example.com", "example.org", "gone.example", "bbc.co.uk")
        val used = listOf("https://accounts.example.com/login", "example.org", "https://www.bbc.co.uk/", "not a url", "")
        // accounts.example.com looks up example.com too, as the icon lookup does.
        assertEquals(listOf("gone.example"), SiteIconPurge.orphans(icons, used))
        assertEquals(icons.sorted(), SiteIconPurge.orphans(icons, emptyList()))
        assertEquals(emptyList<String>(), SiteIconPurge.orphans(emptyList(), used))
    }
}
