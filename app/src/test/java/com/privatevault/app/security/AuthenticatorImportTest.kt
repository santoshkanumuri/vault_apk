package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticatorImportTest {
    private val original = TotpSetup("Example", "alice", "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
    private val changed = TotpSetup("Example", "alice", "KRUGS4ZANFZSAYJA", "SHA1", 6, 30)

    @Test fun distinguishesSavedCodeFromChangedKey() {
        val saved = VaultEntry(type = EntryType.AUTHENTICATOR, title = "Example", primaryValue = "alice",
            secondaryValue = original.secret, totpAlgorithm = "SHA1", totpDigits = 6, totpPeriod = 30)
        assertEquals(listOf(AuthenticatorImportStatus.ALREADY_SAVED, AuthenticatorImportStatus.CONFLICT),
            authenticatorImportStatuses(listOf(saved), listOf(original, changed)))
    }

    @Test fun detectsDuplicatesAndConflictsWithinOneBatch() {
        assertEquals(listOf(AuthenticatorImportStatus.NEW, AuthenticatorImportStatus.ALREADY_SAVED, AuthenticatorImportStatus.CONFLICT),
            authenticatorImportStatuses(emptyList(), listOf(original, original, changed)))
    }

    @Test fun sameCodeUnderAnotherLabelIsAlreadySaved() {
        val renamed = TotpSetup("Renamed", "other label", original.secret, "SHA1", 6, 30)
        assertEquals(listOf(AuthenticatorImportStatus.NEW, AuthenticatorImportStatus.ALREADY_SAVED),
            authenticatorImportStatuses(emptyList(), listOf(original, renamed)))
    }
}
