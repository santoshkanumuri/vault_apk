package com.privatevault.app.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

class SyncAuthorityTransferTest {
    @Test fun pairedRecipientSignsItsOwnAcceptance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val root = File(context.cacheDir, "sync-transfer-${UUID.randomUUID()}")
        val locked = LockedSyncStore(context, root)
        try {
            val local = identities.getOrCreate()
            val managerSeed = ByteArray(32) { 13 }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val managerKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(managerSeed))
            val signManager: (ByteArray) -> ByteArray = { DeviceIdentityCrypto.sign(managerSeed, it) }
            val genesis = SyncMembershipEvent.sign("vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
                "manager", "manager", managerKey, 1, signManager)
            val admission = SyncMembershipEvent.sign("vault", 2, genesis.hash, MembershipAction.ADD,
                "manager", local.deviceId, local.publicKeyBase64Url, 1, signManager)
            val offer = SyncMembershipEvent.sign("vault", 3, admission.hash,
                MembershipAction.OFFER_TRANSFER, "manager", local.deviceId, "", 1, signManager)
            database.dao().saveSettings(VaultSettings(vaultId = "vault"))
            database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                contentKey = encoder.encodeToString(ByteArray(32) { 3 }),
                transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
            listOf(genesis, admission).forEach {
                database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it))
            }
            SyncMembershipManager.verify(listOf(genesis, admission)).members.forEach {
                database.syncDao().upsertMembership(it)
            }
            locked.publish(database)
            locked.acceptMembershipEvents(listOf(genesis, admission, offer).map(SyncMembershipEventEntity::from))
            assertTrue(locked.hasPendingMembership(database))
            assertEquals(0, locked.applyQueued(database, ByteArray(32) { 9 }))
            assertFalse(locked.hasPendingMembership(database))

            val acceptance = database.acceptAuthorityTransfer(identities)
            val verified = SyncMembershipManager.verify(listOf(genesis, admission, offer, acceptance))
            assertTrue(verified.transferAccepted)
            assertEquals("manager", verified.managerDeviceId)
            assertTrue(runCatching { database.acceptAuthorityTransfer(identities) }.isFailure)
        } finally {
            database.close()
            identities.clear()
            root.deleteRecursively()
        }
    }

    @Test fun recipientAcceptanceIsRequiredBeforeManagerCanTransfer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identities = AndroidDeviceIdentityStore(context)
        identities.clear()
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            val local = identities.getOrCreate()
            val recipientSeed = ByteArray(32) { 12 }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val recipientKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(recipientSeed))
            val genesis = SyncMembershipEvent.sign("vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
                local.deviceId, local.deviceId, local.publicKeyBase64Url, 1, identities::sign)
            val admission = SyncMembershipEvent.sign("vault", 2, genesis.hash, MembershipAction.ADD,
                local.deviceId, "recipient", recipientKey, 1, identities::sign)
            database.dao().saveSettings(VaultSettings(vaultId = "vault"))
            database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                contentKey = encoder.encodeToString(ByteArray(32) { 3 }),
                transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
            listOf(genesis, admission).forEach {
                database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it))
            }
            SyncMembershipManager.verify(listOf(genesis, admission)).members.forEach {
                database.syncDao().upsertMembership(it)
            }

            val offer = database.offerAuthorityTransfer(identities, "recipient")
            assertEquals("recipient", SyncMembershipManager.verify(listOf(genesis, admission, offer))
                .pendingTransferDeviceId)
            assertTrue(runCatching { database.removeOnlyPairedDevice(identities, "recipient") }.isFailure)
            assertTrue(runCatching { database.completeAuthorityTransfer(identities) }.isFailure)
            val acceptance = SyncMembershipEvent.sign("vault", 4, offer.hash,
                MembershipAction.ACCEPT_TRANSFER, "recipient", "recipient", "", 1) {
                DeviceIdentityCrypto.sign(recipientSeed, it)
            }
            database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(acceptance))
            val transfer = database.completeAuthorityTransfer(identities)
            val accepted = SyncMembershipManager.verify(listOf(genesis, admission, offer,
                acceptance, transfer))
            assertEquals("recipient", accepted.managerDeviceId)
            assertNull(accepted.pendingTransferDeviceId)
            assertTrue(runCatching { database.prepareSyncGroup(identities) }.isFailure)
        } finally {
            database.close()
            identities.clear()
        }
    }
}
