package com.privatevault.app.sync

import androidx.room.withTransaction
import com.google.gson.Gson
import com.privatevault.app.data.SyncLegacyOperationEntity
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.VaultDatabase
import java.security.SecureRandom
import java.util.Base64

/** Establishes a shared key only after unpaired local history is detached. */
internal suspend fun VaultDatabase.prepareSyncGroup(identityStore: AndroidDeviceIdentityStore) = withTransaction {
    val sync = syncDao()
    val identity = identityStore.getOrCreate()
    val localDeviceId = identity.deviceId
    val vaultId = requireNotNull(dao().settings()?.vaultId?.takeIf(String::isNotBlank)) {
        "Vault ID is missing"
    }
    val existing = sync.vaultState()
    if (existing != null) {
        require(existing.vaultId == vaultId && existing.keyEpoch == 1L &&
            existing.transportSecret.isNotBlank()) { "Invalid sync key state" }
        require(sync.membershipEvents(vaultId).isNotEmpty()) {
            "Existing device sync needs recovery before another phone can join"
        }
        return@withTransaction
    }
    require(sync.peers(vaultId).isEmpty() && sync.memberships(vaultId).none {
        it.deviceId != localDeviceId && it.status == MemberStatus.ACTIVE.name
    }) { "Existing device sync needs recovery before another phone can join" }
    val operations = sync.operations()
    require(operations.all { it.vaultId == vaultId && it.deviceId == localDeviceId }) {
        "Existing device sync needs recovery before another phone can join"
    }
    val gson = Gson()
    operations.forEach { sync.archiveOperation(SyncLegacyOperationEntity(it.mutationId, vaultId, gson.toJson(it))) }
    dao().clearSyncOperations()
    dao().clearSyncDeviceHeads()
    dao().clearSyncRecordStates()
    dao().clearSyncTombstones()
    dao().clearSyncAttachments()
    dao().clearSyncConflicts()
    dao().clearSyncAcknowledgements()
    require(sync.membership(vaultId, localDeviceId)?.identityPublicKey == identity.publicKeyBase64Url) {
        "Local device identity does not match this vault"
    }
    val genesis = SyncMembershipEvent.sign(vaultId, 1L, GENESIS_HASH, MembershipAction.GENESIS,
        localDeviceId, localDeviceId, identity.publicKeyBase64Url, 1L, identityStore::sign)
    sync.insertMembershipEvent(SyncMembershipEventEntity.from(genesis))
    val key = ByteArray(32).also(SecureRandom()::nextBytes)
    val transport = ByteArray(32).also(SecureRandom()::nextBytes)
    try {
        sync.saveVaultState(SyncVaultStateEntity(vaultId = vaultId,
            contentKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key),
            transportSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(transport)))
    } finally { key.fill(0); transport.fill(0) }
}
