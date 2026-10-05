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

/**
 * Detaches a device whose own verified history holds a signed REMOVE of it. Items stay; sync state
 * of the old group (including unresolved conflicts, which keep this device's version) is dropped.
 */
internal suspend fun VaultDatabase.leaveRemovedSyncGroup(identityStore: AndroidDeviceIdentityStore): String =
    startIndependentSyncGroup(identityStore, null, removed = true)

/**
 * The manager removes another device with a signed REMOVE (raising the key epoch), cancelling a
 * transfer offered to that same device first. Returns the new events so the caller can append
 * them to the transport mirror too.
 */
internal suspend fun VaultDatabase.removeSyncMember(identityStore: AndroidDeviceIdentityStore,
    deviceId: String): List<SyncMembershipEventEntity> = withTransaction {
    val identity = identityStore.getOrCreate()
    val vaultId = requireNotNull(dao().settings()).vaultId
    val sync = syncDao()
    val history = sync.membershipEvents(vaultId).map { it.toEvent() }
    val verified = SyncMembershipManager.verify(history)
    require(verified.managerDeviceId == identity.deviceId) { "Only the managing device can remove another device" }
    require(deviceId != identity.deviceId) { "Transfer the managing role before this device leaves" }
    require(verified.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name }) {
        "That device is not active in this sync group"
    }
    val pending = verified.pendingTransferDeviceId
    require(pending == null || pending == deviceId) { "Finish or cancel the managing-role transfer first" }
    val added = mutableListOf<SyncMembershipEvent>()
    if (pending != null) added += SyncMembershipEvent.sign(vaultId, history.size + 1L, verified.head,
        MembershipAction.CANCEL_TRANSFER, identity.deviceId, deviceId, "", verified.keyEpoch, identityStore::sign)
    added += SyncMembershipEvent.sign(vaultId, history.size + added.size + 1L, (history + added).last().hash,
        MembershipAction.REMOVE, identity.deviceId, deviceId, "", verified.keyEpoch + 1, identityStore::sign)
    val after = SyncMembershipManager.verify(history + added)
    added.forEach { sync.insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
    after.members.forEach { member ->
        val label = sync.membership(vaultId, member.deviceId)?.displayName
        sync.upsertMembership(member.copy(displayName = label ?: member.displayName))
    }
    added.map { SyncMembershipEventEntity.from(it) }
}

/**
 * Signs the leave request for the current group before this device leaves it locally, so it can be
 * retried later with only the old epoch's transport secret.
 */
internal suspend fun VaultDatabase.prepareLeaveNotice(identityStore: AndroidDeviceIdentityStore,
    addresses: List<String>, port: Int, now: Long = System.currentTimeMillis()): PendingLeaveNotice = withTransaction {
    val identity = identityStore.getOrCreate()
    val vaultId = requireNotNull(dao().settings()).vaultId
    val sync = syncDao()
    val verified = SyncMembershipManager.verify(sync.membershipEvents(vaultId).map { it.toEvent() })
    require(verified.members.any { it.deviceId == identity.deviceId && it.status == MemberStatus.ACTIVE.name }) {
        "This device is not in an active shared sync group"
    }
    require(verified.managerDeviceId != identity.deviceId) {
        "The managing device cannot leave. Transfer the managing role first."
    }
    val group = requireNotNull(sync.vaultState()) { "Sync group is not ready" }
    val request = LeaveRequest(vaultId, identity.deviceId, verified.head, verified.keyEpoch, now)
    PendingLeaveNotice(vaultId, identity.deviceId, verified.head, verified.keyEpoch, now,
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(identityStore.sign(request.signingBytes())),
        TransportEpochs.secret(group.transportSecret, vaultId, verified.keyEpoch), addresses.take(8), port)
}

private suspend fun VaultDatabase.startIndependentSyncGroup(identityStore: AndroidDeviceIdentityStore,
    onlyPeerDeviceId: String?, removed: Boolean = false): String = withTransaction {
    val identity = identityStore.getOrCreate()
    val settings = requireNotNull(dao().settings())
    val oldVaultId = settings.vaultId
    val sync = syncDao()
    val verified = SyncMembershipManager.verify(sync.membershipEvents(oldVaultId).map { it.toEvent() })
    val activeIds = verified.members.filter { it.status == MemberStatus.ACTIVE.name }
        .mapTo(mutableSetOf()) { it.deviceId }
    if (removed) require(verified.events.any {
        it.action == MembershipAction.REMOVE && it.subjectDeviceId == identity.deviceId
    }) { "This device was not removed from the sync group" }
    else require(identity.deviceId in activeIds && activeIds.size >= 2) {
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
    require(removed || sync.conflicts().none { it.resolvedAtUtc.isEmpty() }) {
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
