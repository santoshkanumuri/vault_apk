package com.privatevault.app.sync

import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.VaultDatabase

/** Reads bounded ciphertext batches after an authenticated peer supplies its frontier. */
class SyncTransferPlanner(private val database: VaultDatabase) {
    suspend fun frontier(): SyncFrontier {
        val vaultId = requireVaultId()
        return SyncFrontier(database.syncDao().deviceHeads(vaultId).associate { it.deviceId to it.sequence })
    }

    suspend fun pendingFor(peerDeviceId: String, peerFrontier: SyncFrontier,
        limit: Int = 100): List<SyncOperationEntity> {
        peerFrontier.validate()
        require(limit in 1..100) { "Invalid sync batch size" }
        val vaultId = requireVaultId()
        val dao = database.syncDao()
        val member = dao.membership(vaultId, peerDeviceId)?.toMembership()
        require(member?.status == MemberStatus.ACTIVE && member.keyEpoch == 1L) {
            "Device is not an active vault member"
        }
        val pending = ArrayList<SyncOperationEntity>(limit)
        for (head in dao.deviceHeads(vaultId)) {
            val peerSequence = peerFrontier.counters[head.deviceId] ?: 0L
            if (peerSequence >= head.sequence) continue
            val batch = dao.operationsAfter(vaultId, head.deviceId, peerSequence, limit - pending.size)
            check(batch.isNotEmpty() && batch.first().sequence == peerSequence + 1L) {
                "Sync history is incomplete"
            }
            pending.addAll(batch)
            if (pending.size == limit) break
        }
        return pending
    }

    private suspend fun requireVaultId(): String =
        requireNotNull(database.dao().settings()?.vaultId?.takeIf(String::isNotBlank)) {
            "Vault ID is missing"
        }
}
