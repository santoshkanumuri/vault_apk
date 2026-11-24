package com.privatevault.app.sync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of the cross-device scenario (spec section 7), on Room: the PC's method arrives and is stored; a
 * concurrent change merges; a delete removes it and keeps a tombstone; a later use brings it back and drops the
 * tombstone; a delete waiting on this phone is hidden and competes with incoming changes by last writer wins.
 */
class SignInMethodStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
    private val key = ByteArray(32) { 5 }

    @After fun close() = database.close()

    private val octo = SignInMethodSyncRules.Method("example.com", "github", "OctoCat", 100, 500, 7)
    private val id = SignInMethodSyncRules.entityId(key, "example.com", "github", "octocat")

    private fun change(device: String, sequence: Long, version: Map<String, Long>, at: String, delete: Boolean = false) =
        SyncChangeRecord(SYNC_FORMAT_VERSION, "vault-a", device, sequence, GENESIS_HASH, "m-$device-$sequence",
            SignInMethodSyncRules.ENTITY_TYPE, id, if (delete) ChangeKind.DELETE else ChangeKind.UPSERT, 0,
            RecordVersion(version), at, "c", "n", "s", "h-$device-$sequence")

    private fun apply(change: SyncChangeRecord, method: SignInMethodSyncRules.Method?): IncomingResult = runBlocking {
        database.withTransaction {
            val state = database.syncDao().recordState(change.entityType, change.entityId)
            val local = state?.let { RecordVersion.parse(it.recordVersionJson) } ?: RecordVersion(emptyMap())
            val relation = ConflictVersions.incomingRelation(change.recordVersion, state?.let { local })
            val row = if (method == null) SignInMethodSync.rowFor(database, key, change.entityId) else null
            SignInMethodSync.apply(database, change, method, row, state, local, relation)
        }
    }

    @Test
    fun receivesMergesDeletesAndBringsBackAMethod() = runBlocking {
        val dao = database.signInMethods()
        assertEquals(IncomingResult.APPLIED, apply(change("pc", 1, mapOf("pc" to 1L), "2026-11-20T10:00:00Z"), octo))
        val stored = requireNotNull(dao.byEntity(id))
        assertEquals(listOf("OctoCat", "octocat"), listOf(stored.account, stored.accountKey))
        assertEquals(7L, stored.useCount)

        // A laptop's concurrent, later upsert merges field by field.
        assertEquals(IncomingResult.APPLIED, apply(change("laptop", 1, mapOf("laptop" to 1L), "2026-11-20T10:00:05Z"),
            octo.copy(account = "octocat", firstUsedAt = 50, lastUsedAt = 400, useCount = 9)))
        val merged = requireNotNull(dao.byEntity(id))
        assertEquals(listOf(50L, 500L, 9L), listOf(merged.firstUsedAt, merged.lastUsedAt, merged.useCount))
        assertEquals("octocat", merged.account)
        assertEquals(RecordVersion(mapOf("pc" to 1L, "laptop" to 1L)),
            RecordVersion.parse(requireNotNull(database.syncDao().recordState(SignInMethodSyncRules.ENTITY_TYPE, id)).recordVersionJson))

        // An older change is obsolete.
        assertEquals(IncomingResult.OBSOLETE, apply(change("pc", 1, mapOf("pc" to 1L), "2026-11-20T12:00:00Z"), octo))

        // A dominating delete: the row goes, a tombstone stays.
        assertEquals(IncomingResult.APPLIED, apply(change("pc", 2, mapOf("pc" to 2L, "laptop" to 1L), "2026-11-20T11:00:00Z", delete = true), null))
        assertNull(dao.byEntity(id))
        assertNotNull(database.syncDao().tombstone(SignInMethodSyncRules.ENTITY_TYPE, id))

        // A concurrent upsert earlier than the delete stays deleted; a later one brings the method back.
        assertEquals(IncomingResult.OBSOLETE, apply(change("laptop", 2, mapOf("laptop" to 2L), "2026-11-20T10:30:00Z"), octo))
        assertNull(dao.byEntity(id))
        assertEquals(IncomingResult.APPLIED, apply(change("laptop", 3, mapOf("laptop" to 3L), "2026-11-20T12:00:00Z"), octo))
        assertNotNull(dao.byEntity(id))
        assertNull("the live record counts now", database.syncDao().tombstone(SignInMethodSyncRules.ENTITY_TYPE, id))
    }

    @Test
    fun aDeleteWaitingOnThisPhoneIsHiddenAndCompetesWithIncomingChanges() = runBlocking {
        val dao = database.signInMethods()
        apply(change("pc", 1, mapOf("pc" to 1L), "2026-11-20T10:00:00Z"), octo)
        val row = requireNotNull(dao.byEntity(id))
        dao.save(row.copy(pendingDelete = true, updatedAtUtc = "2026-11-21T10:00:00Z", updatedByDevice = "phone"))
        assertTrue("hidden everywhere", dao.all().isEmpty())
        assertEquals(1, dao.pendingDeletes(10).size)
        // The PC's use before the phone's delete: the delete still waits.
        assertEquals(IncomingResult.OBSOLETE, apply(change("pc", 2, mapOf("pc" to 2L), "2026-11-21T09:00:00Z"), octo.copy(useCount = 8)))
        assertEquals(1, dao.pendingDeletes(10).size)
        // A use after it: the method comes back and the waiting delete is dropped.
        assertEquals(IncomingResult.APPLIED, apply(change("pc", 3, mapOf("pc" to 3L), "2026-11-21T11:00:00Z"), octo.copy(useCount = 9)))
        assertEquals(9L, dao.all().single().useCount)
        assertTrue(dao.pendingDeletes(10).isEmpty())
        // A delete arriving for a row stored under another group's id is found by its recomputed id.
        dao.save(dao.all().single().copy(entityId = "stale-id-from-an-old-group"))
        assertEquals(IncomingResult.APPLIED, apply(change("pc", 4, mapOf("pc" to 4L), "2026-11-21T12:00:00Z", delete = true), null))
        assertTrue(dao.all().isEmpty())
    }
}
