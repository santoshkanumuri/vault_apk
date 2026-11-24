package com.privatevault.app.sync

/** A durable operation known to this device, reduced to its chain coordinates. */
internal data class ChainLink(val deviceId: String, val sequence: Long, val hash: String,
    val previousHash: String? = null)

/**
 * Computes what this device durably holds per author: the applied heads extended by operations that
 * were already applied but not yet reflected in the published heads (kept in the outgoing relay
 * folder) and by queued operations that continue the chain. Reporting this frontier never moves
 * backward while queued operations are being applied, which peers reject.
 */
internal object IncomingChain {
    enum class Admission { STORE, ALREADY_STORED, OLD_RESEND }

    fun received(applied: Map<String, SyncChainHead>, relayed: Collection<ChainLink>,
        queued: Collection<ChainLink>): Map<String, SyncChainHead> {
        val result = applied.toMutableMap()
        // Relayed operations were chain-checked when queued and moved in order once applied.
        relayed.groupBy { it.deviceId }.forEach { (deviceId, links) ->
            val bySequence = links.associateBy { it.sequence }
            var head = result[deviceId] ?: SyncChainHead(0, GENESIS_HASH)
            while (true) {
                val next = bySequence[head.sequence + 1] ?: break
                if (next.previousHash != null && next.previousHash != head.hash) break
                head = SyncChainHead(next.sequence, next.hash)
            }
            if (head.sequence > 0) result[deviceId] = head
        }
        queued.groupBy { it.deviceId }.forEach { (deviceId, links) ->
            val bySequence = links.groupBy { it.sequence }
            var head = result[deviceId] ?: SyncChainHead(0, GENESIS_HASH)
            while (true) {
                val next = bySequence[head.sequence + 1]?.firstOrNull { it.previousHash == head.hash } ?: break
                head = SyncChainHead(next.sequence, next.hash)
            }
            if (head.sequence > 0) result[deviceId] = head
        }
        return result
    }

    /**
     * Admits [link] only when it extends the durable chain. A resend of an operation this device
     * already holds is acknowledged without storing it again; anything else is a gap or a fork.
     *
     * A change below the received head is an old resend ([Admission.OLD_RESEND]). This device may
     * no longer hold it: it was relayed and dropped, or it lies below a checkpoint or a compacted
     * floor. It is acknowledged and never stored; it could not apply anyway, because the applier
     * accepts only head + 1. Another change at the head itself is a fork and is refused.
     */
    fun admit(link: ChainLink, received: Map<String, SyncChainHead>, knownHashes: Set<String>): Admission {
        if (link.hash in knownHashes) return Admission.ALREADY_STORED
        val head = received[link.deviceId] ?: SyncChainHead(0, GENESIS_HASH)
        if (link.sequence == head.sequence && link.hash == head.hash) return Admission.ALREADY_STORED
        if (link.sequence in 1 until head.sequence) return Admission.OLD_RESEND
        require(link.sequence == head.sequence + 1 && link.previousHash == head.hash) { "Incomplete change chain" }
        return Admission.STORE
    }
}
