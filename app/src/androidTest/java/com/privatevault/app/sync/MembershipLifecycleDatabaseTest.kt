package com.privatevault.app.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

/** Device lifecycle paths on a real Room database: the manager resets sync, and a stale mirror after a removal. */
class MembershipLifecycleDatabaseTest {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private suspend fun managedGroup(database: VaultDatabase, identities: AndroidDeviceIdentityStore): List<SyncMembershipEvent> {
        val local = identities.getOrCreate()
        val genesis = SyncMembershipEvent.sign("shared-vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
            local.deviceId, local.deviceId, local.publicKeyBase64Url, 1, identities::sign)
        val phone = SyncMembershipEvent.sign("shared-vault", 2, genesis.hash, MembershipAction.ADD,
            local.deviceId, "phone", encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { 5 })), 1, identities::sign)
        val pc = SyncMembershipEvent.sign("shared-vault", 3, phone.hash, MembershipAction.ADD,
            local.deviceId, "pc", encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { 6 })), 1, identities::sign)
        val history = listOf(genesis, phone, pc)
        database.dao().saveSettings(VaultSettings(vaultId = "shared-vault"))
        database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "shared-vault",
            contentKey = encoder.encodeToString(ByteArray(32) { 3 }),
            transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
        history.forEach { database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
        SyncMembershipManager.verify(history).members.forEach { database.syncDao().upsertMembership(it) }
        return history
    }

    @Test fun managerStartsASeparateGroupWithoutALeaveRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            val history = managedGroup(database, identities)
            val local = identities.getOrCreate().deviceId
            database.dao().insertEntry(VaultEntry(id = "kept", type = EntryType.NOTE, title = "Keep me"))
            // The ViewModel asks this first; a leave request would fail for the manager.
            assertFalse(MembershipChoices.leaveSendsRequest(history, local))
            assertTrue(runCatching { database.prepareLeaveNotice(identities, emptyList(), 0) }.isFailure)

            val newVaultId = database.leaveSyncGroup(identities)

            assertNotEquals("shared-vault", newVaultId)
            assertEquals("Keep me", database.dao().entry("kept")?.entry?.title)
            val renewed = identities.getOrCreate().deviceId
            assertNotEquals(local, renewed)
            assertEquals(listOf(renewed), database.syncDao().memberships(newVaultId).map { it.deviceId })
            assertTrue(database.syncDao().membershipEvents("shared-vault").isEmpty())
        } finally {
            database.close()
            identities.clear()
        }
    }

    @Test fun applyingAStaleMirrorKeepsTheRemovedDeviceRevoked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val root = File(context.cacheDir, "sync-stale-${UUID.randomUUID()}")
        val locked = LockedSyncStore(context, root)
        try {
            managedGroup(database, identities)
            locked.publish(database)
            // The REMOVE commits in Room; the mirror is published only afterwards.
            database.removeSyncMember(identities, "phone")
            assertEquals(MemberStatus.REVOKED.name, database.syncDao().membership("shared-vault", "phone")?.status)

            locked.applyQueued(database, ByteArray(32) { 2 })

            assertEquals(MemberStatus.REVOKED.name, database.syncDao().membership("shared-vault", "phone")?.status)
            assertEquals(4, database.syncDao().membershipEvents("shared-vault").size)
            assertEquals(MemberStatus.REVOKED.name,
                locked.snapshot()?.members?.single { it.deviceId == "phone" }?.status)
        } finally {
            database.close()
            identities.clear()
            root.deleteRecursively()
        }
    }

    // Leaving with a new identity (P3), emptying on leave (P5), and a forced leave that keeps
    // unapplied changes (P10).

    private val managerSeed = ByteArray(32) { 7 }
    private val pcSeed = ByteArray(32) { 8 }
    private val signManager: (ByteArray) -> ByteArray = { DeviceIdentityCrypto.sign(managerSeed, it) }

    /** A group managed by "manager", with this device and "pc" as members. */
    private suspend fun memberGroup(database: VaultDatabase, identities: AndroidDeviceIdentityStore): List<SyncMembershipEvent> {
        val local = identities.getOrCreate()
        val genesis = SyncMembershipEvent.sign("shared-vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
            "manager", "manager", encoder.encodeToString(DeviceIdentityCrypto.publicKey(managerSeed)), 1, signManager)
        val self = SyncMembershipEvent.sign("shared-vault", 2, genesis.hash, MembershipAction.ADD,
            "manager", local.deviceId, local.publicKeyBase64Url, 1, signManager)
        val pc = SyncMembershipEvent.sign("shared-vault", 3, self.hash, MembershipAction.ADD,
            "manager", "pc", encoder.encodeToString(DeviceIdentityCrypto.publicKey(pcSeed)), 1, signManager)
        val history = listOf(genesis, self, pc)
        database.dao().saveSettings(VaultSettings(vaultId = "shared-vault"))
        database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "shared-vault",
            contentKey = encoder.encodeToString(ByteArray(32) { 3 }),
            transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
        history.forEach { database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
        SyncMembershipManager.verify(history).members.forEach { database.syncDao().upsertMembership(it) }
        return history
    }

    @Test fun aMemberLeavesWithANewIdentityAndItsOldLeaveRequestStillVerifies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            val history = memberGroup(database, identities)
            val old = identities.getOrCreate()
            val notice = database.prepareLeaveNotice(identities, emptyList(), 0)

            val newVaultId = database.leaveSyncGroup(identities)

            val renewed = identities.getOrCreate()
            assertNotEquals(old.deviceId, renewed.deviceId)
            assertFalse(old.publicKey.contentEquals(renewed.publicKey))
            assertNull(identities.next())
            // The request was signed by the old identity and still verifies with its key.
            val signature = Base64.getUrlDecoder().decode(notice.signature)
            assertTrue(DeviceIdentityCrypto.verify(old.publicKey, notice.request().signingBytes(), signature))
            val answer = LeaveRequestHandler.handleFromDrive(notice.request(), signature, history, setOf(1L),
                "manager", notice.createdAt, signManager)
            assertTrue(answer is LeaveDecision.Left)
            // The new group belongs to the new identity.
            assertEquals(renewed.deviceId, SyncMembershipManager.verify(
                database.syncDao().membershipEvents(newVaultId).map { it.toEvent() }).managerDeviceId)
            // The old group can add the new identity (it refuses the old one).
            val removed = history + (answer as LeaveDecision.Left).append
            val rejoin = SyncMembershipEvent.sign("shared-vault", removed.size + 1L, removed.last().hash,
                MembershipAction.ADD, "manager", renewed.deviceId, renewed.publicKeyBase64Url, 2, signManager)
            assertEquals(MemberStatus.ACTIVE.name, SyncMembershipManager.verify(removed + rejoin).members
                .single { it.deviceId == renewed.deviceId }.status)
        } finally {
            database.close()
            identities.clear()
        }
    }

    @Test fun anIdentityChangeThatStoppedAfterTheLeaveFinishesOnUnlock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            database.dao().saveSettings(VaultSettings(vaultId = "solo"))
            val old = identities.getOrCreate()
            val next = identities.prepareNext()
            assertEquals(next.deviceId, identities.prepareNext().deviceId)
            // The vault does not name the prepared identity: nothing changes.
            assertFalse(database.reconcileDeviceIdentity(identities))
            assertEquals(old.deviceId, identities.getOrCreate().deviceId)
            // The leave committed a genesis by the prepared identity, then the app stopped.
            database.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("solo", next.deviceId,
                "This device", next.publicKeyBase64Url, MemberStatus.ACTIVE, next.deviceId, 1, 1)))
            assertTrue(database.reconcileDeviceIdentity(identities))
            assertEquals(next.deviceId, identities.getOrCreate().deviceId)
            assertNull(identities.next())
            val signature = identities.sign("check".toByteArray())
            assertTrue(DeviceIdentityCrypto.verify(next.publicKey, "check".toByteArray(), signature))
        } finally {
            database.close()
            identities.clear()
        }
    }

    @Test fun leavingAndEmptyingThisPhoneKeepsEntriesInRecentlyDeleted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            memberGroup(database, identities)
            database.dao().insertGroup(VaultGroup(id = "folder", name = "Work"))
            database.dao().insertEntry(VaultEntry(id = "kept", type = EntryType.NOTE, title = "Keep me"))

            database.leaveSyncGroup(identities, emptyItems = true)

            // The join check of SyncSnapshot.commit now passes.
            assertTrue(database.dao().allEntries().isEmpty())
            assertTrue(database.dao().allPasskeys().isEmpty())
            assertTrue(database.dao().allGroupsWithEntries().isEmpty())
            assertEquals(listOf("Keep me"), database.dao().trash().map { it.title })
        } finally {
            database.close()
            identities.clear()
        }
    }

    @Test fun aForcedLeaveKeepsUnappliedEntryChangesInRecentlyDeleted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val root = File(context.cacheDir, "sync-forced-${UUID.randomUUID()}")
        val locked = LockedSyncStore(context, root)
        try {
            val history = memberGroup(database, identities)
            locked.publish(database)
            // An entry from the PC in a folder that never arrived: it waits and can't apply.
            val entry = VaultEntry(id = "from-pc", type = EntryType.NOTE, title = "Written on the PC")
            val plaintext = org.json.JSONObject().put("entry", entry.toSyncJson())
                .put("groupIds", org.json.JSONArray(listOf("missing-folder"))).toString().toByteArray(Charsets.UTF_8)
            val nonce = ByteArray(12) { 1 }
            val sealed = AppearanceSyncRules.encrypt(ByteArray(32) { 3 },
                "nuvori-sync-entry-v1:shared-vault:m1:from-pc", nonce, plaintext)
            val signed = SyncChangeRecord.createSigned("shared-vault", "pc", 1, GENESIS_HASH, "m1", "entry", "from-pc",
                ChangeKind.UPSERT, 0, RecordVersion(mapOf("pc" to 1L)), "2026-11-01T10:00:00Z",
                encoder.encodeToString(sealed), encoder.encodeToString(nonce)) { DeviceIdentityCrypto.sign(pcSeed, it) }
            locked.queue(SyncOperationEntity(signed.mutationId, signed.formatVersion, signed.vaultId, signed.deviceId,
                signed.sequence, signed.previousHash, signed.entityType, signed.entityId, "upsert", signed.baseRevision,
                signed.recordVersion.toJson(), signed.occurredAtUtc, signed.payloadCiphertext, signed.payloadNonce,
                signed.deviceSignature, signed.hash))
            locked.applyQueued(database, ByteArray(32) { 2 })
            assertEquals(1, locked.queuedCount())

            assertEquals(RemovalArchive.Summary(1, 0), locked.archiveUnapplied(database, ByteArray(32) { 2 }))
            // A retry adds no second copy.
            locked.archiveUnapplied(database, ByteArray(32) { 2 })
            assertEquals(listOf("Written on the PC"), database.dao().trash().map { it.title })

            // Then the manager's REMOVE of this device is applied and the device leaves.
            val local = identities.getOrCreate().deviceId
            val remove = SyncMembershipEvent.sign("shared-vault", 4, history.last().hash, MembershipAction.REMOVE,
                "manager", local, "", 2, signManager)
            database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(remove))
            database.leaveRemovedSyncGroup(identities)
            locked.clear()
            assertEquals(listOf("Written on the PC"), database.dao().trash().map { it.title })
            assertNotEquals(local, identities.getOrCreate().deviceId)
        } finally {
            database.close()
            identities.clear()
            root.deleteRecursively()
        }
    }
}
