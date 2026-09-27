package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.VaultDatabase
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** The two-member split creates a fresh group around this device's local vault. */
internal suspend fun VaultDatabase.removeOnlyPairedDevice(identityStore: AndroidDeviceIdentityStore,
    peerDeviceId: String): String = startIndependentSyncGroup(identityStore, peerDeviceId)

/** Keeps the local vault while detaching this device from a group of any supported size. */
internal suspend fun VaultDatabase.leaveSyncGroup(identityStore: AndroidDeviceIdentityStore): String =
    startIndependentSyncGroup(identityStore, null)

private suspend fun VaultDatabase.startIndependentSyncGroup(identityStore: AndroidDeviceIdentityStore,
    onlyPeerDeviceId: String?): String = withTransaction {
    val identity = identityStore.getOrCreate()
    val settings = requireNotNull(dao().settings())
    val oldVaultId = settings.vaultId
    val sync = syncDao()
    val verified = SyncMembershipManager.verify(sync.membershipEvents(oldVaultId).map { it.toEvent() })
    val activeIds = verified.members.filter { it.status == MemberStatus.ACTIVE.name }
        .mapTo(mutableSetOf()) { it.deviceId }
    require(identity.deviceId in activeIds && activeIds.size >= 2) {
        "This device is not in an active shared sync group"
    }
    require(onlyPeerDeviceId == null || activeIds == setOf(identity.deviceId, onlyPeerDeviceId)) {
        "Removing one device from a larger group needs key rotation across the remaining devices"
    }
    require(onlyPeerDeviceId == null || verified.managerDeviceId == identity.deviceId) {
        "Only the managing device can remove another device"
    }
    require(onlyPeerDeviceId == null || verified.pendingTransferDeviceId == null) {
        "Finish or cancel the authority transfer before removing a device"
    }
    require(sync.conflicts().none { it.resolvedAtUtc.isEmpty() }) {
        "Resolve sync conflicts before removing the device"
    }
    val old = requireNotNull(sync.vaultState())
    require(old.vaultId == oldVaultId && old.transportSecret.isNotBlank())
    val newVaultId = UUID.randomUUID().toString()
    val contentKey = ByteArray(32).also(SecureRandom()::nextBytes)
    val transportSecret = ByteArray(32).also(SecureRandom()::nextBytes)
    try {
        dao().clearSyncOperations()
        dao().clearSyncDeviceHeads()
        dao().clearSyncRecordStates()
        dao().clearSyncTombstones()
        dao().clearSyncAttachments()
        dao().clearSyncConflicts()
        dao().clearSyncAcknowledgements()
        dao().clearSyncMemberships()
        dao().clearSyncMembershipEvents()
        dao().clearSyncVaultState()
        dao().clearSyncPeers()
        dao().clearSyncLegacyOperations()
        dao().saveSettings(settings.copy(vaultId = newVaultId, watchSyncEnabled = false))
        val genesis = SyncMembershipEvent.sign(newVaultId, 1, GENESIS_HASH,
            MembershipAction.GENESIS, identity.deviceId, identity.deviceId,
            identity.publicKeyBase64Url, 1, identityStore::sign)
        sync.insertMembershipEvent(SyncMembershipEventEntity.from(genesis))
        sync.upsertMembership(SyncMembershipEntity.from(DeviceMembership(newVaultId,
            identity.deviceId, "This device", identity.publicKeyBase64Url,
            MemberStatus.ACTIVE, identity.deviceId, 1, 1)))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        sync.saveVaultState(SyncVaultStateEntity(vaultId = newVaultId,
            contentKey = encoder.encodeToString(contentKey),
            transportSecret = encoder.encodeToString(transportSecret)))
    } finally {
        contentKey.fill(0)
        transportSecret.fill(0)
    }
    newVaultId
}
