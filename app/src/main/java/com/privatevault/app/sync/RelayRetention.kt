package com.privatevault.app.sync

/**
 * Which held changes the locked relay folder (`outgoing/` of [LockedSyncStore]) keeps, and which
 * of them it sends. Pure functions, so they are unit-tested without the Keystore.
 *
 * A change stays only while somebody may still need it from this device: a peer that has not
 * applied it, or Google Drive that does not hold it yet. Every author always holds its own chain,
 * so a dropped change can still come from its author, from the managing device or from the drive.
 */
internal object RelayRetention {
    /** One relay file reduced to what the rules need. */
    data class Held(val deviceId: String, val sequence: Long, val hash: String)

    /**
     * What retention needs to know about the group. [cloudCovered] is null without Drive sync;
     * otherwise it maps an author to the last change the drive holds without a gap.
     */
    data class Context(val localDeviceId: String, val activePeers: Set<String>,
        val applied: Map<String, Map<String, Long>>, val cutoffs: Map<String, SyncChainHead>,
        val activeAuthors: Set<String>, val cloudCovered: Map<String, Long>?)

    /**
     * True when [change] stays in the relay folder:
     * - it is this device's own change and no other device is active, or
     * - Drive sync is on and the drive does not hold it yet, or
     * - an active peer other than its author has not applied it.
     * A change of a removed author beyond its cutoff never stays: no peer accepts it.
     *
     * The rule is monotone in the sequence: when it keeps a change, it keeps every later change of
     * the same author. So the folder always holds a gap-free tail of each author's chain.
     */
    fun keep(change: Held, context: Context): Boolean {
        val author = change.deviceId
        if (author != context.localDeviceId && author !in context.activeAuthors &&
            !RemovalCutoff.allows(context.cutoffs[author], change.sequence, change.hash)) return false
        if (author == context.localDeviceId && context.activePeers.isEmpty()) return true
        if (context.cloudCovered != null && change.sequence > (context.cloudCovered[author] ?: 0L)) return true
        return context.activePeers.any { peer ->
            peer != author && (context.applied[peer]?.get(author) ?: 0L) < change.sequence
        }
    }

    /**
     * The changes to send to a peer at [frontier], in (author, sequence) order, at most [limit].
     * Each author's run starts right after the peer's frontier and stops at the first missing
     * sequence: a receiver refuses anything that does not continue its chain, and a gap is normal
     * below a checkpoint or a compacted floor. [held] must be sorted by (author, sequence).
     */
    fun gapFree(held: List<ChainLink>, frontier: Map<String, Long>, allowed: (ChainLink) -> Boolean,
        limit: Int): List<ChainLink> {
        val result = ArrayList<ChainLink>()
        var author: String? = null
        var expected = 0L
        var stopped = false
        for (link in held) {
            if (result.size >= limit) break
            if (link.deviceId != author) {
                author = link.deviceId
                expected = (frontier[author] ?: 0L) + 1
                stopped = false
            }
            if (stopped || link.sequence < expected) continue
            if (link.sequence != expected || !allowed(link)) { stopped = true; continue }
            result += link
            expected++
        }
        return result
    }

    /**
     * Per author, the sequence up to which the relay folder already reflects the database:
     * the highest held file, or the raised [floor] when every file below it was dropped.
     * A publish loads only the database changes above it.
     */
    fun relayTop(held: List<Held>, floor: Map<String, Long>): Map<String, Long> {
        val top = floor.toMutableMap()
        held.forEach { top[it.deviceId] = maxOf(top[it.deviceId] ?: 0L, it.sequence) }
        return top
    }

    /**
     * The new floor per author after a publish dropped [dropped]: the highest dropped sequence,
     * never lower than the previous [floor].
     */
    fun raiseFloor(floor: Map<String, Long>, dropped: List<Held>): Map<String, Long> {
        val result = floor.toMutableMap()
        dropped.forEach { result[it.deviceId] = maxOf(result[it.deviceId] ?: 0L, it.sequence) }
        return result
    }
}
