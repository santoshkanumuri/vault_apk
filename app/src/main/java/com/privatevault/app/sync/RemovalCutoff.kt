package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEventEntity

/**
 * The last change of a removed device that the group still accepts.
 *
 * A REMOVE event used to carry an empty `subjectPublicKey`, and every peer then refused all of the
 * removed device's changes. A peer that had not received some of them before the removal never got
 * them. The managing device now writes `cutoff1:<sequence>:<hash>` there: the removed device's
 * change head that the manager held when it signed the removal. Peers accept and relay that
 * device's changes up to and including the cutoff, and never anything after it.
 *
 * Older builds reject a REMOVE with a cutoff, so the manager writes one only when every other
 * active device announced [FEATURE] in the sync handshake, and changes of a removed device are
 * relayed only to peers that announced it.
 */
internal object RemovalCutoff {
    const val FEATURE = "removal-cutoff-v1"
    /** What this build announces in the sync handshake `features` list. */
    val FEATURES: List<String> = listOf(FEATURE)
    private const val PREFIX = "cutoff1:"
    private val HASH = Regex("[a-f0-9]{64}")
    private val SEQUENCE = Regex("[1-9][0-9]{0,18}")

    fun encode(head: SyncChainHead): String {
        require(head.sequence >= 1 && head.hash.matches(HASH))
        return "$PREFIX${head.sequence}:${head.hash}"
    }

    /** Null for an empty field; throws for anything else that is not a valid cutoff. */
    fun parse(value: String): SyncChainHead? {
        if (value.isEmpty()) return null
        require(value.startsWith(PREFIX)) { "Invalid removal cutoff" }
        val parts = value.removePrefix(PREFIX).split(':')
        require(parts.size == 2 && parts[0].matches(SEQUENCE) && parts[1].matches(HASH)) { "Invalid removal cutoff" }
        return SyncChainHead(parts[0].toLong(), parts[1])
    }

    fun isValidField(value: String): Boolean = runCatching { parse(value) }.isSuccess

    /** Removed device ID to its cutoff, from a verified history. */
    fun cutoffs(events: List<SyncMembershipEvent>): Map<String, SyncChainHead> = events
        .filter { it.action == MembershipAction.REMOVE }
        .mapNotNull { event -> runCatching { parse(event.subjectPublicKey) }.getOrNull()?.let { event.subjectDeviceId to it } }
        .toMap()

    fun cutoffsOf(events: List<SyncMembershipEventEntity>): Map<String, SyncChainHead> =
        cutoffs(events.map { it.toEvent() })

    /** True when a change at [sequence] with [hash] by a removed device is within its [cutoff]. */
    fun allows(cutoff: SyncChainHead?, sequence: Long, hash: String): Boolean =
        cutoff != null && sequence in 1..cutoff.sequence && (sequence != cutoff.sequence || hash == cutoff.hash)
}
