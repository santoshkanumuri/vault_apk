package com.privatevault.app.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import com.privatevault.app.security.EncryptedPhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

class SyncGroupResetTest {
    @Test fun secondaryCanLeaveThreeDeviceGroupWithoutErasingItsVault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val root = File(context.cacheDir, "sync-leave-${UUID.randomUUID()}")
        val locked = LockedSyncStore(context, root)
        try {
            val local = identities.getOrCreate()
            val managerSeed = ByteArray(32) { 7 }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val managerKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(managerSeed))
            val thirdKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { 8 }))
            val signManager: (ByteArray) -> ByteArray = { DeviceIdentityCrypto.sign(managerSeed, it) }
            val genesis = SyncMembershipEvent.sign("shared-vault", 1, GENESIS_HASH,
                MembershipAction.GENESIS, "manager", "manager", managerKey, 1, signManager)
            val localAdmission = SyncMembershipEvent.sign("shared-vault", 2, genesis.hash,
                MembershipAction.ADD, "manager", local.deviceId, local.publicKeyBase64Url, 1, signManager)
            val thirdAdmission = SyncMembershipEvent.sign("shared-vault", 3, localAdmission.hash,
                MembershipAction.ADD, "manager", "third", thirdKey, 1, signManager)
            database.dao().saveSettings(VaultSettings(vaultId = "shared-vault"))
            database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "shared-vault",
                contentKey = encoder.encodeToString(ByteArray(32) { 3 }),
                transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
            listOf(genesis, localAdmission).forEach {
                database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it))
            }
            SyncMembershipManager.verify(listOf(genesis, localAdmission)).members.forEach {
                database.syncDao().upsertMembership(it)
            }
            assertTrue(runCatching { database.removeOnlyPairedDevice(identities, "manager") }.isFailure)
            database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(thirdAdmission))
            database.syncDao().upsertMembership(
                SyncMembershipManager.verify(listOf(genesis, localAdmission, thirdAdmission)).members.last())
            database.dao().insertEntry(VaultEntry(id = "kept", type = EntryType.NOTE, title = "Keep me"))
            locked.publish(database)

            val newVaultId = database.leaveSyncGroup(identities)
            locked.publish(database)

            assertNotEquals("shared-vault", newVaultId)
            assertEquals("Keep me", database.dao().entry("kept")?.entry?.title)
            assertEquals(newVaultId, locked.snapshot()?.vaultId)
            assertEquals(listOf(local.deviceId), database.syncDao().memberships(newVaultId).map { it.deviceId })
            assertEquals(local.deviceId, SyncMembershipManager.verify(
                database.syncDao().membershipEvents(newVaultId).map { it.toEvent() }).managerDeviceId)
            database.prepareSyncGroup(identities)
            assertEquals(newVaultId, database.syncDao().vaultState()?.vaultId)
        } finally {
            database.close()
            identities.clear()
            root.deleteRecursively()
        }
    }

    @Test fun removingOnlyPeerRotatesBothKeysAndKeepsLocalVault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val root = File(context.cacheDir, "sync-reset-${UUID.randomUUID()}")
        val locked = LockedSyncStore(context, root)
        val photos = EncryptedPhotoStore(context)
        val photoName = "reset-${UUID.randomUUID()}.vaultphoto"
        try {
            val local = identities.getOrCreate()
            val remoteId = "removed-device"
            val remoteKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                DeviceIdentityCrypto.publicKey(ByteArray(32) { 6 }))
            val genesis = SyncMembershipEvent.sign("old-vault", 1, GENESIS_HASH,
                MembershipAction.GENESIS, local.deviceId, local.deviceId,
                local.publicKeyBase64Url, 1, identities::sign)
            val admission = SyncMembershipEvent.sign("old-vault", 2, genesis.hash,
                MembershipAction.ADD, local.deviceId, remoteId, remoteKey, 1, identities::sign)
            database.dao().saveSettings(VaultSettings(vaultId = "old-vault", watchSyncEnabled = true))
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val oldContent = encoder.encodeToString(ByteArray(32) { 3 })
            val oldTransport = encoder.encodeToString(ByteArray(32) { 4 })
            database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "old-vault",
                contentKey = oldContent, transportSecret = oldTransport))
            database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(genesis))
            database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(admission))
            listOf(local.deviceId to local.publicKeyBase64Url, remoteId to remoteKey)
                .forEachIndexed { index, (id, key) ->
                    database.syncDao().upsertMembership(SyncMembershipEntity("old-vault", id,
                        "Device", key, MemberStatus.ACTIVE.name, local.deviceId, index + 1L, 1))
                }
            LocalEntryChangeWriter(database, identities).save(
                VaultEntry(id = "kept", type = EntryType.NOTE, title = "Keep me"),
                emptySet(), ByteArray(32) { 9 })
            photos.encrypt("photo content".byteInputStream(), photoName, ByteArray(32) { 9 })
            database.dao().insertPhoto(VaultPhoto(id = "kept-photo", entryId = "kept",
                encryptedFileName = photoName))
            locked.publish(database)
            val newVaultId = database.removeOnlyPairedDevice(identities, remoteId)
            // A crash before transport cleanup still recovers from the committed new vault ID.
            locked.publish(database)
            assertNotEquals("old-vault", newVaultId)
            assertEquals("Keep me", database.dao().entry("kept")?.entry?.title)
            assertEquals("kept-photo", database.dao().photo("kept-photo")?.id)
            assertArrayEquals("photo content".toByteArray(),
                photos.decryptedBytes(photoName, ByteArray(32) { 9 }))
            assertEquals(newVaultId, database.dao().settings()?.vaultId)
            assertFalse(requireNotNull(database.dao().settings()).watchSyncEnabled)
            assertTrue(database.syncDao().operations().isEmpty())
            val state = requireNotNull(database.syncDao().vaultState())
            assertNotEquals(oldContent, state.contentKey)
            assertNotEquals(oldTransport, state.transportSecret)
            assertNull(database.syncDao().membership(newVaultId, remoteId))
            assertEquals(listOf(MembershipAction.GENESIS),
                database.syncDao().membershipEvents(newVaultId).map { it.toEvent().action })
            assertEquals(newVaultId, locked.snapshot()?.vaultId)
            assertTrue(File(root, "outgoing").listFiles().isNullOrEmpty())
        } finally {
            database.close()
            identities.clear()
            photos.delete(photoName)
            root.deleteRecursively()
        }
    }
}
