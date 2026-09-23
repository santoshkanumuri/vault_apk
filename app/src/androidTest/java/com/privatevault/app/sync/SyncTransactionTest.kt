package com.privatevault.app.sync

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultSettings
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncTransactionTest {
    private lateinit var database: VaultDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            VaultDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun commitsVaultEditOperationAndHeadTogether() = runBlocking {
        val entry = VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "Saved")
        val operation = operation("mutation-1", 1, entry.id)
        val head = head(operation)

        database.syncDao().saveEntryAndOperation(entry, operation, head)

        assertEquals("Saved", database.dao().entry(entry.id)?.entry?.title)
        assertEquals(listOf(operation), database.syncDao().operations())
        assertEquals(head, database.syncDao().deviceHead("vault-a", "device-a"))
    }

    @Test
    fun rollsBackVaultEditWhenOperationSequenceConflicts() = runBlocking {
        val original = VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "Original")
        database.dao().insertEntry(original)
        database.syncDao().insertOperation(operation("mutation-existing", 1, "entry-other"))

        val changed = original.copy(title = "Must roll back")
        val conflicting = operation("mutation-new", 1, original.id)
        val failure = runCatching {
            database.syncDao().saveEntryAndOperation(changed, conflicting, head(conflicting))
        }.exceptionOrNull()

        assertTrue(failure is SQLiteConstraintException)
        assertEquals("Original", database.dao().entry(original.id)?.entry?.title)
        assertEquals(1, database.syncDao().operations().size)
        assertEquals(null, database.syncDao().deviceHead("vault-a", "device-a"))
    }

    @Test
    fun localWriterEncryptsSignsAndChainsEntryChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
        val writer = LocalEntryChangeWriter(database, identityStore)
        val key = ByteArray(32) { (it + 1).toByte() }

        writer.save(VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "First"), emptySet(), key)
        writer.save(VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "Second"), emptySet(), key)

        val operations = database.syncDao().operations()
        val identity = identityStore.getOrCreate()
        assertEquals(listOf(1L, 2L), operations.map { it.sequence })
        assertEquals(operations.first().hash, operations.last().previousHash)
        assertTrue(operations.none { "First" in it.payloadCiphertext || "Second" in it.payloadCiphertext })
        operations.forEach { operation ->
            val change = operation.toChangeRecord()
            val signature = java.util.Base64.getUrlDecoder().decode(change.deviceSignature)
            assertTrue(DeviceIdentityCrypto.verify(identity.publicKey, change.signingBytes(), signature))
            change.validate()
        }
        assertEquals(2L, database.syncDao().recordState("entry", "entry-a")?.revision)
        identityStore.clear()
    }

    private fun operation(mutationId: String, sequence: Long, entityId: String) =
        SyncOperationEntity(
            mutationId = mutationId,
            formatVersion = 1,
            vaultId = "vault-a",
            deviceId = "device-a",
            sequence = sequence,
            previousHash = GENESIS_HASH,
            entityType = "entry",
            entityId = entityId,
            kind = "upsert",
            baseRevision = 0,
            recordVersionJson = "{\"counters\":{\"device-a\":$sequence}}",
            occurredAtUtc = "2026-09-21T12:00:00Z",
            payloadCiphertext = "ciphertext-test-only",
            payloadNonce = "nonce-test-only",
            deviceSignature = "signature-test-only",
            hash = "hash-$mutationId",
        )

    private fun head(operation: SyncOperationEntity) = SyncDeviceHeadEntity(
        vaultId = operation.vaultId,
        deviceId = operation.deviceId,
        sequence = operation.sequence,
        hash = operation.hash,
    )

    private fun SyncOperationEntity.toChangeRecord() = SyncChangeRecord(
        formatVersion, vaultId, deviceId, sequence, previousHash, mutationId, entityType, entityId,
        if (kind == "upsert") ChangeKind.UPSERT else ChangeKind.DELETE,
        baseRevision, Gson().fromJson(recordVersionJson, RecordVersion::class.java), occurredAtUtc,
        payloadCiphertext, payloadNonce, deviceSignature, hash,
    )
}
