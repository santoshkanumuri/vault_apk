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

/**
 * Detaches this device from a group of any supported size. It keeps the local vault, or with
 * [emptyItems] it starts empty (entries go to Recently deleted), ready to join another group.
 */
internal suspend fun VaultDatabase.leaveSyncGroup(identityStore: AndroidDeviceIdentityStore,
    emptyItems: Boolean = false): String = startIndependentSyncGroup(identityStore, null, emptyItems = emptyItems)

/**
 * Detaches a device whose own verified history holds a signed REMOVE of it. Items stay; sync state
 * of the old group (including unresolved conflicts, which keep this device's version) is dropped.
 */
internal suspend fun VaultDatabase.leaveRemovedSyncGroup(identityStore: AndroidDeviceIdentityStore): String =
    startIndependentSyncGroup(identityStore, null, removed = true)

/**
 * After "Mark as lost" ([markManagerLost]): this device starts a new group and manages it. It keeps
 * its items and the lineage of the old group, so other survivors join it with their items and no
 * duplicates ([MergeJoin]). Unresolved conflicts keep this device's version, as after a removal.
 */
internal suspend fun VaultDatabase.startGroupAfterLostManager(identityStore: AndroidDeviceIdentityStore): String =
    startIndependentSyncGroup(identityStore, null, lostManager = true)

/**
 * The manager removes another device with a signed REMOVE (raising the key epoch), cancelling a
 * transfer offered to that same device first. Returns the new events so the caller can append
 * them to the transport mirror too.
 */
internal suspend fun VaultDatabase.removeSyncMember(identityStore: AndroidDeviceIdentityStore,
    deviceId: String, cutoff: String = "", statements: Map<String, KeyStatement> = emptyMap()): List<SyncMembershipEventEntity> = withTransaction {
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
        MembershipAction.REMOVE, identity.deviceId, deviceId, cutoff, verified.keyEpoch + 1, identityStore::sign)
    val after = SyncMembershipManager.verify(history + added)
    added.forEach { sync.insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
    // New keys for the new epoch when every survivor sent a signed key statement ([EpochKeyRotation]);
    // otherwise the epoch is HMAC-derived as before and the removed device could still read new data.
    EpochKeyRotation.rotate(this, identityStore, after, added.last(), statements)
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
        TransportEpochs.secret(group.transportSecret, vaultId, verified.keyEpoch,
            sync.epochKeys().filter { it.vaultId == vaultId }.associate { it.epoch to it.transportSecret }), addresses.take(8), port)
}

/**
 * Starts this device's own group. The new group's genesis is signed by a new device identity
 * ([AndroidDeviceIdentityStore.prepareNext]), which becomes the identity in use once the vault
 * transaction committed. The old group keeps the old identity (as a member or as removed) and
 * never adds it again, so the new identity is what lets this device pair with that group later. A
 * stop between the commit and [AndroidDeviceIdentityStore.promoteNext] is finished on the next
 * unlock by [reconcileDeviceIdentity]. [emptyItems] also moves every entry to Recently deleted and
 * deletes folders and passkeys, so the device can join another group from an empty vault.
 */
private suspend fun VaultDatabase.startIndependentSyncGroup(identityStore: AndroidDeviceIdentityStore,
    onlyPeerDeviceId: String?, removed: Boolean = false, emptyItems: Boolean = false, lostManager: Boolean = false): String {
    val vaultId = startIndependentSyncGroupInTransaction(identityStore, onlyPeerDeviceId, removed, emptyItems, lostManager)
    identityStore.promoteNext()
    return vaultId
}

/**
 * Finishes an identity change that stopped after [startIndependentSyncGroup] committed: when the
 * vault's group was started by the prepared next identity, that identity becomes the one in use.
 * Call it on unlock, before anything signs. True when it changed the identity.
 */
internal suspend fun VaultDatabase.reconcileDeviceIdentity(identityStore: AndroidDeviceIdentityStore): Boolean {
    val next = identityStore.next() ?: return false
    val vaultId = dao().settings()?.vaultId ?: return false
    if (syncDao().membership(vaultId, next.deviceId) == null) return false
    identityStore.promoteNext()
    return true
}

private suspend fun VaultDatabase.startIndependentSyncGroupInTransaction(identityStore: AndroidDeviceIdentityStore,
    onlyPeerDeviceId: String?, removed: Boolean, emptyItems: Boolean, lostManager: Boolean): String = withTransaction {
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
    require(!lostManager || sync.lostManager(oldVaultId) != null) { "Mark the managing device as lost first" }
    require(removed || lostManager || sync.conflicts().none { it.resolvedAtUtc.isEmpty() }) {
        "Resolve sync conflicts before removing the device"
    }
    // What this device keeps of the old group: each item's version, for a later merge join.
    val lineage = if (emptyItems) emptyList() else MergeJoin.lineageRows(oldVaultId, sync.recordStates(), sync.tombstones(),
        MergeJoin.contentDigests(this@startIndependentSyncGroupInTransaction))
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
        dao().clearSyncEpochKeys()
        dao().clearSyncKeyPackages()
        sync.clearLostManager()
        sync.clearLineage()
        if (lineage.isNotEmpty()) sync.insertLineage(lineage)
        dao().saveSettings(settings.copy(vaultId = newVaultId, watchSyncEnabled = false))
        if (emptyItems) {
            // Entries stay recoverable in Recently deleted on this device for its usual 30 days.
            // Photos and folder links go with their entries (foreign keys); the unlock sweep
            // deletes the photo files. Passkeys and folders have no Recently deleted.
            val now = System.currentTimeMillis()
            dao().allEntries().forEach { dao().keepDeleted(it.entry.id, elsewhere = false, now = now) }
            dao().clearEntries()
            dao().clearGroups()
            dao().clearPasskeys()
        }
        val next = identityStore.prepareNext()
        val genesis = SyncMembershipEvent.sign(newVaultId, 1, GENESIS_HASH,
            MembershipAction.GENESIS, next.deviceId, next.deviceId,
            next.publicKeyBase64Url, 1, identityStore::signWithNext)
        sync.insertMembershipEvent(SyncMembershipEventEntity.from(genesis))
        sync.upsertMembership(SyncMembershipEntity.from(DeviceMembership(newVaultId,
            next.deviceId, "This device", next.publicKeyBase64Url,
            MemberStatus.ACTIVE, next.deviceId, 1, 1)))
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
