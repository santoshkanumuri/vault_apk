package com.privatevault.app.sync

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A forced leave keeps unapplied entry changes in Recently deleted instead of dropping them. */
class RemovalArchiveTest {
    private fun operation(entityId: String, at: String, device: String = "pc", sequence: Long = 1,
        type: String = "entry", kind: String = "upsert") = SyncOperationEntity("m-$device-$sequence-$entityId", 1, "vault",
        device, sequence, GENESIS_HASH, type, entityId, kind, 0, "{}", at, "c", "n", "s", "$device$sequence$entityId")

    private fun candidate(entityId: String, title: String, at: String, device: String = "pc", sequence: Long = 1) =
        RemovalArchive.Candidate(operation(entityId, at, device, sequence),
            VaultEntry(id = entityId, type = EntryType.PASSWORD, title = title), emptyList())

    @Test fun theNewestUnappliedVersionOfEachEntryIsKept() {
        val kept = RemovalArchive.pick(listOf(
            candidate("a", "old", "2026-01-01T00:00:00Z", sequence = 1),
            candidate("a", "new", "2026-02-01T00:00:00Z", sequence = 2),
            candidate("b", "only", "2026-01-05T00:00:00Z", device = "phone")), emptyMap())
        assertEquals(listOf("a" to "new", "b" to "only"), kept.map { it.entry.id to it.entry.title }.sortedBy { it.first })
    }

    @Test fun deletesAndSettingsAreNotCountedOnlyLostDataIs() {
        val operations = listOf(operation("g", "t", type = "group"), operation("k", "t", type = "passkey"),
            operation("p", "t", type = "photo"), operation("e", "t", kind = "delete"),
            operation("x", "t", type = "device_name"), operation("e2", "t"))
        assertEquals(3, RemovalArchive.otherChanges(operations))
    }

    @Test fun theUserIsToldWhatWasKeptAndWhatWasNot() {
        assertNull(RemovalArchive.Summary(0, 0).message())
        val both = RemovalArchive.Summary(2, 1).message()!!
        assertTrue(both, both.contains("2 items that had not finished syncing are in Recently deleted"))
        assertTrue(both, both.contains("1 other change (folders, passkeys or photos) could not be kept here"))
        assertTrue(RemovalArchive.Summary(1, 0).message()!!.startsWith("1 item that had not finished syncing is"))
    }
}
