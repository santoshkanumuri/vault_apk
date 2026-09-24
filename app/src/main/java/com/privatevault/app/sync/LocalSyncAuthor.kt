package com.privatevault.app.sync

import com.privatevault.app.data.VaultDatabase

/** Call inside the same transaction that commits the local operation. */
internal suspend fun VaultDatabase.requireLocalSyncAuthor(vaultId: String, deviceId: String) {
    val group = syncDao().vaultState() ?: return
    require(group.vaultId == vaultId) { "Sync group does not match this vault" }
    require(syncDao().membership(vaultId, deviceId)?.status == MemberStatus.ACTIVE.name) {
        "This device is no longer a member of the sync group"
    }
}
