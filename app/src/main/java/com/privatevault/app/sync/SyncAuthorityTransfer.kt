package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.VaultDatabase

internal suspend fun VaultDatabase.offerAuthorityTransfer(identityStore: AndroidDeviceIdentityStore,
    targetDeviceId: String) = changeAuthority(identityStore, MembershipAction.OFFER_TRANSFER, targetDeviceId)

internal suspend fun VaultDatabase.acceptAuthorityTransfer(identityStore: AndroidDeviceIdentityStore) =
    changeAuthority(identityStore, MembershipAction.ACCEPT_TRANSFER)

internal suspend fun VaultDatabase.completeAuthorityTransfer(identityStore: AndroidDeviceIdentityStore) =
    changeAuthority(identityStore, MembershipAction.TRANSFER)

internal suspend fun VaultDatabase.cancelAuthorityTransfer(identityStore: AndroidDeviceIdentityStore) =
    changeAuthority(identityStore, MembershipAction.CANCEL_TRANSFER)

private suspend fun VaultDatabase.changeAuthority(identityStore: AndroidDeviceIdentityStore,
    action: MembershipAction, targetDeviceId: String? = null): SyncMembershipEvent = withTransaction {
    val vaultId = requireNotNull(dao().settings()).vaultId
    val sync = syncDao()
    val history = sync.membershipEvents(vaultId).map { it.toEvent() }
    val verified = SyncMembershipManager.verify(history)
    val localId = identityStore.getOrCreate().deviceId
    val target = if (action == MembershipAction.OFFER_TRANSFER) requireNotNull(targetDeviceId)
        else requireNotNull(verified.pendingTransferDeviceId) { "There is no pending authority transfer" }
    when (action) {
        MembershipAction.OFFER_TRANSFER -> require(localId == verified.managerDeviceId &&
            verified.pendingTransferDeviceId == null && target != localId &&
            verified.members.any { it.deviceId == target && it.status == MemberStatus.ACTIVE.name }) {
            "Only the manager can offer authority to an active paired device"
        }
        MembershipAction.ACCEPT_TRANSFER -> require(localId == target && !verified.transferAccepted) {
            "Only the invited device can accept this transfer"
        }
        MembershipAction.TRANSFER -> require(localId == verified.managerDeviceId && verified.transferAccepted) {
            "The invited device must accept before authority transfers"
        }
        MembershipAction.CANCEL_TRANSFER -> require(localId == verified.managerDeviceId) {
            "Only the manager can cancel this transfer"
        }
        else -> error("Unsupported authority action")
    }
    require(sync.vaultState()?.let { it.vaultId == vaultId && it.keyEpoch == verified.keyEpoch } == true)
    val event = SyncMembershipEvent.sign(vaultId, history.size + 1L, verified.head, action,
        localId, target, "", verified.keyEpoch, identityStore::sign)
    SyncMembershipManager.verify(history + event)
    sync.insertMembershipEvent(SyncMembershipEventEntity.from(event))
    event
}
