package com.privatevault.app.sync

import android.database.sqlite.SQLiteConstraintException
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultSettings
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.passkeys.PasskeyCrypto
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
    fun entryWaitsForGroupFromAnotherAuthor() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val otherContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("dependency-test-$name", mode)
        }
        val firstIdentity = AndroidDeviceIdentityStore(base)
        val secondIdentity = AndroidDeviceIdentityStore(otherContext)
        firstIdentity.clear(); secondIdentity.clear()
        val second = Room.inMemoryDatabaseBuilder(otherContext, VaultDatabase::class.java).build()
        val receiver = Room.inMemoryDatabaseBuilder(base, VaultDatabase::class.java).build()
        try {
            val firstDevice = firstIdentity.getOrCreate()
            val secondDevice = secondIdentity.getOrCreate()
            val content = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32) { 3 })
            val transport = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32) { 4 })
            listOf(database, second, receiver).forEach { db ->
                db.dao().saveSettings(VaultSettings(vaultId = "vault"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                    contentKey = content, transportSecret = transport))
                listOf(firstDevice, secondDevice).forEachIndexed { index, member ->
                    db.syncDao().upsertMembership(SyncMembershipEntity("vault", member.deviceId,
                        "Device", member.publicKeyBase64Url, MemberStatus.ACTIVE.name,
                        firstDevice.deviceId, index + 1L, 1))
                }
            }
            val group = VaultGroup(id = "late-group", name = "Late")
            second.dao().insertGroup(group)
            val vaultKey = ByteArray(32) { 9 }
            LocalGroupChangeWriter(database, firstIdentity).save(group, vaultKey)
            LocalEntryChangeWriter(second, secondIdentity).save(
                VaultEntry(id = "linked", type = EntryType.NOTE, title = "Linked"),
                setOf(group.id), vaultKey)
            val entryChange = second.syncDao().operations().single()
            val groupChange = database.syncDao().operations().single()
            assertTrue(runCatching {
                IncomingEntryChangeApplier(receiver).apply(entryChange, vaultKey)
            }.exceptionOrNull() is MissingRecordDependencyException)
            assertEquals(IncomingResult.APPLIED,
                IncomingEntryChangeApplier(receiver).apply(groupChange, vaultKey))
            assertEquals(IncomingResult.APPLIED,
                IncomingEntryChangeApplier(receiver).apply(entryChange, vaultKey))
            assertEquals(listOf(group.id), receiver.dao().entry("linked")?.groups?.map { it.id })
        } finally {
            second.close(); receiver.close(); firstIdentity.clear(); secondIdentity.clear()
        }
    }

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

    @Test
    fun removedDeviceCannotAuthorNewGroupChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        try {
            val identity = identityStore.getOrCreate()
            database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault-a",
                contentKey = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteArray(32) { 5 })))
            database.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault-a",
                identity.deviceId, "This device", identity.publicKeyBase64Url, MemberStatus.REVOKED,
                identity.deviceId, 1, 1)))
            val failure = runCatching {
                LocalEntryChangeWriter(database, identityStore).save(
                    VaultEntry(id = "blocked", type = EntryType.NOTE, title = "Blocked"),
                    emptySet(), ByteArray(32) { 3 })
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(null, database.dao().entry("blocked"))
            assertTrue(database.syncDao().operations().isEmpty())
        } finally { identityStore.clear() }
    }

    @Test
    fun editingAuthenticatorAcceptsStoredDecimalVersionCounter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            val key = ByteArray(32) { (it + 1).toByte() }
            val writer = LocalEntryChangeWriter(database, identityStore)
            val entry = VaultEntry(id = "auth-a", type = EntryType.AUTHENTICATOR,
                title = "Original", primaryValue = "account", secondaryValue = "JBSWY3DPEHPK3PXP")
            writer.save(entry, emptySet(), key)
            val deviceId = identityStore.getOrCreate().deviceId
            database.syncDao().upsertRecordState(SyncRecordStateEntity("entry", entry.id, 1,
                "{\"counters\":{\"$deviceId\":1.0}}"))

            writer.save(entry.copy(title = "Edited service", primaryValue = "edited account"), emptySet(), key)

            assertEquals("Edited service", database.dao().entry(entry.id)?.entry?.title)
            assertEquals("edited account", database.dao().entry(entry.id)?.entry?.primaryValue)
            assertEquals(2L, database.syncDao().operations().last().sequence)
        } finally {
            identityStore.clear()
        }
    }

    @Test
    fun localWriterRecordsDeleteAndTombstoneAtomically() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
        val writer = LocalEntryChangeWriter(database, identityStore)
        val key = ByteArray(32) { (it + 1).toByte() }
        val entry = VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "Saved")

        writer.save(entry, emptySet(), key)
        writer.delete(entry, key)

        val operations = database.syncDao().operations()
        val tombstone = database.syncDao().tombstone("entry", entry.id)
        assertEquals(null, database.dao().entry(entry.id))
        assertEquals(listOf("upsert", "delete"), operations.map { it.kind })
        assertEquals(operations.first().hash, operations.last().previousHash)
        assertEquals(operations.last().hash, tombstone?.changeHash)
        assertEquals(2L, database.syncDao().recordState("entry", entry.id)?.revision)
        identityStore.clear()
    }

    @Test
    fun failedDeleteOperationLeavesEntryAndNoTombstone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        val identity = identityStore.getOrCreate()
        database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
        val entry = VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "Keep")
        database.dao().insertEntry(entry)
        database.syncDao().insertOperation(operation("existing", 1, "other").copy(deviceId = identity.deviceId))

        val failure = runCatching { LocalEntryChangeWriter(database, identityStore).delete(entry, ByteArray(32)) }.exceptionOrNull()

        assertTrue(failure is SQLiteConstraintException)
        assertEquals("Keep", database.dao().entry(entry.id)?.entry?.title)
        assertEquals(null, database.syncDao().tombstone("entry", entry.id))
        identityStore.clear()
    }

    @Test
    fun signedChangesApplyToAnotherVaultAndRejectReplayTampering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        val identity = identityStore.getOrCreate()
        val receiver = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            receiver.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            val key = ByteArray(32) { (it + 1).toByte() }
            val member = DeviceMembership("vault-a", identity.deviceId, "Source", identity.publicKeyBase64Url,
                MemberStatus.ACTIVE, identity.deviceId, 1, 1)
            receiver.syncDao().upsertMembership(SyncMembershipEntity.from(member))
            val entry = VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "From phone A")
            val writer = LocalEntryChangeWriter(database, identityStore)
            val applier = IncomingEntryChangeApplier(receiver)

            writer.save(entry, emptySet(), key)
            val upsert = database.syncDao().operations().single()
            assertEquals(IncomingResult.APPLIED, applier.apply(upsert, key))
            assertEquals("From phone A", receiver.dao().entry(entry.id)?.entry?.title)
            assertEquals(IncomingResult.REPLAY, applier.apply(upsert, key))
            assertTrue(runCatching { applier.apply(upsert.copy(hash = "tampered"), key) }.isFailure)

            writer.delete(entry, key)
            val delete = database.syncDao().operations().last()
            assertEquals(IncomingResult.APPLIED, applier.apply(delete, key))
            assertEquals(null, receiver.dao().entry(entry.id))
            assertEquals(delete.hash, receiver.syncDao().tombstone("entry", entry.id)?.changeHash)
        } finally {
            receiver.close()
            identityStore.clear()
        }
    }

    @Test
    fun groupAndLinkedEntryApplyToAnotherVaultInOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        val identity = identityStore.getOrCreate()
        val receiver = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            receiver.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            val key = ByteArray(32) { (it + 1).toByte() }
            val member = DeviceMembership("vault-a", identity.deviceId, "Source", identity.publicKeyBase64Url,
                MemberStatus.ACTIVE, identity.deviceId, 1, 1)
            receiver.syncDao().upsertMembership(SyncMembershipEntity.from(member))
            val group = VaultGroup(id = "group-a", name = "Personal")
            val entry = VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "Linked")
            LocalGroupChangeWriter(database, identityStore).save(group, key)
            LocalEntryChangeWriter(database, identityStore).save(entry, setOf(group.id), key)

            val applier = IncomingEntryChangeApplier(receiver)
            database.syncDao().operations().sortedBy { it.sequence }.forEach {
                assertEquals(IncomingResult.APPLIED, applier.apply(it, key))
            }
            assertEquals(listOf(group.id), receiver.dao().entry(entry.id)?.groups?.map { it.id })
        } finally {
            receiver.close()
            identityStore.clear()
        }
    }

    @Test
    fun receiverRejectsUnknownAndRevokedMembers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        val identity = identityStore.getOrCreate()
        val receiver = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            receiver.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            val key = ByteArray(32) { (it + 1).toByte() }
            LocalEntryChangeWriter(database, identityStore).save(
                VaultEntry(id = "entry-a", type = EntryType.NOTE, title = "Blocked"), emptySet(), key)
            val operation = database.syncDao().operations().single()
            val applier = IncomingEntryChangeApplier(receiver)
            assertTrue(runCatching { applier.apply(operation, key) }.isFailure)

            val revoked = DeviceMembership("vault-a", identity.deviceId, "Source", identity.publicKeyBase64Url,
                MemberStatus.REVOKED, identity.deviceId, 2, 1)
            receiver.syncDao().upsertMembership(SyncMembershipEntity.from(revoked))
            assertTrue(runCatching { applier.apply(operation, key) }.isFailure)
            assertTrue(receiver.syncDao().operations().isEmpty())
            assertEquals(null, receiver.dao().entry("entry-a"))
        } finally {
            receiver.close()
            identityStore.clear()
        }
    }

    @Test
    fun passkeyPrivateMaterialTransfersOnlyInsideSignedCiphertext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        val identity = identityStore.getOrCreate()
        val receiver = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            receiver.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            receiver.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault-a",
                identity.deviceId, "Source", identity.publicKeyBase64Url, MemberStatus.ACTIVE,
                identity.deviceId, 1, 1)))
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val passkey = VaultPasskey(PasskeyCrypto.encode(ByteArray(32) { 1 }), "example.com",
                PasskeyCrypto.encode(ByteArray(32) { 2 }), "user", "User",
                PasskeyCrypto.encode(pair.private.encoded), PasskeyCrypto.encode(pair.public.encoded), 1_700_000_000_000)
            val key = ByteArray(32) { (it + 1).toByte() }
            val writer = LocalPasskeyChangeWriter(database, identityStore)
            writer.save(passkey, key)
            val upsert = database.syncDao().operations().single()
            assertTrue(passkey.privateKey !in upsert.payloadCiphertext)
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(receiver).apply(upsert, key))
            assertEquals(passkey, receiver.dao().allPasskeys().single())

            writer.delete(passkey, key)
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(receiver)
                .apply(database.syncDao().operations().last(), key))
            assertTrue(receiver.dao().allPasskeys().isEmpty())
        } finally {
            receiver.close()
            identityStore.clear()
        }
    }

    @Test
    fun capturedImportWritesEntryAndSignedOperationTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
        val key = ByteArray(32) { (it + 1).toByte() }
        val entry = VaultEntry(id = "entry-a", type = EntryType.PASSWORD, title = "Imported")

        database.captureEntryUpserts(identityStore, key) { insertEntry(entry) }

        assertEquals("Imported", database.dao().entry(entry.id)?.entry?.title)
        assertEquals(listOf(entry.id), database.syncDao().operations().map { it.entityId })
        identityStore.clear()
    }

    @Test
    fun transferPlannerSendsOnlyMissingCiphertextInBoundedBatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
            val key = ByteArray(32) { (it + 1).toByte() }
            val writer = LocalEntryChangeWriter(database, identityStore)
            repeat(3) { index ->
                writer.save(VaultEntry(id = "entry-$index", type = EntryType.NOTE, title = "Item $index"), emptySet(), key)
            }
            val planner = SyncTransferPlanner(database)
            val deviceId = identityStore.getOrCreate().deviceId
            val peerId = "paired-device"
            database.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault-a", peerId,
                "Peer", identityStore.getOrCreate().publicKeyBase64Url, MemberStatus.ACTIVE, deviceId, 1, 1)))
            assertEquals(3L, planner.frontier().counters[deviceId])
            assertTrue(runCatching { planner.pendingFor("unknown", SyncFrontier(emptyMap())) }.isFailure)
            val first = planner.pendingFor(peerId, SyncFrontier(emptyMap()), 2)
            assertEquals(listOf(1L, 2L), first.map { it.sequence })
            assertEquals(listOf(3L), planner.pendingFor(peerId, SyncFrontier(mapOf(deviceId to 2L))).map { it.sequence })
            assertTrue(planner.pendingFor(peerId, planner.frontier()).isEmpty())
            assertTrue(first.none { "Item" in it.payloadCiphertext })
        } finally {
            identityStore.clear()
        }
    }

    @Test
    fun replacingVaultRowsClearsPreviousSyncHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identityStore = AndroidDeviceIdentityStore(context)
        identityStore.clear()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "old-vault"))
            LocalEntryChangeWriter(database, identityStore).save(
                VaultEntry(id = "old-entry", type = EntryType.NOTE, title = "Before restore"),
                emptySet(), ByteArray(32) { 1 })
            val identity = identityStore.getOrCreate()
            database.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership(
                "old-vault", identity.deviceId, "This device", identity.publicKeyBase64Url,
                MemberStatus.ACTIVE, identity.deviceId, 1, 1)))

            database.dao().replaceAll(listOf(VaultEntry(id = "restored-entry", type = EntryType.NOTE,
                title = "Restored")), emptyList(), emptyList(), emptyList(),
                VaultSettings(vaultId = "new-vault"))

            assertTrue(database.syncDao().operations().isEmpty())
            assertEquals(null, database.syncDao().membership("old-vault", identity.deviceId))
            assertEquals(null, database.dao().entry("old-entry"))
            assertEquals("Restored", database.dao().entry("restored-entry")?.entry?.title)
        } finally {
            identityStore.clear()
        }
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
