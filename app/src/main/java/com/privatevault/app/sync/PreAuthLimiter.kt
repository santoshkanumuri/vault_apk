package com.privatevault.app.sync

/**
 * Security review L-06: limits connections that have not proved group membership yet, so one
 * device on the Wi-Fi can't fill the listener or keep sync busy. Each source address gets at most
 * [perSource] unproved connections at once, all sources together [total], and a source whose
 * connections failed [maxFailures] times within [windowMillis] waits [blockMillis] before the next.
 * Times are monotonic milliseconds. Thread-safe.
 */
internal class PreAuthLimiter(
    private val total: Int = 8,
    private val perSource: Int = 2,
    private val maxFailures: Int = 5,
    private val windowMillis: Long = 120_000,
    private val blockMillis: Long = 120_000,
    /** M7: slots only a known device's address may take, so strangers can't fill every slot. */
    private val reservedForKnown: Int = 2,
) {
    private val active = HashMap<String, Int>()
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val blockedUntil = HashMap<String, Long>()

    /**
     * True when a new connection from [source] ([sourceKey] of its address) may start its check now;
     * call [finish] afterwards. [known]: the address a paired device last proved membership from.
     */
    @Synchronized fun tryStart(source: String, now: Long, known: Boolean = false): Boolean {
        blockedUntil[source]?.let { if (now < it) return false else blockedUntil.remove(source) }
        val mine = active[source] ?: 0
        val limit = if (known) total else total - reservedForKnown
        if (mine >= perSource || active.values.sum() >= limit) return false
        active[source] = mine + 1
        return true
    }

    /** Ends a check that [tryStart] allowed. [proved] is false when the device didn't prove membership. */
    @Synchronized fun finish(source: String, proved: Boolean, now: Long) {
        when (val left = (active[source] ?: 1) - 1) {
            in Int.MIN_VALUE..0 -> active.remove(source)
            else -> active[source] = left
        }
        val recent = failures.getOrPut(source) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() >= windowMillis) recent.removeFirst()
        if (proved) { recent.clear(); failures.remove(source); return }
        recent.addLast(now)
        if (recent.size >= maxFailures) {
            blockedUntil[source] = now + blockMillis
            recent.clear()
        }
        // Keep the maps small on a busy network.
        if (failures.size > 256) failures.keys.retainAll(active.keys + blockedUntil.keys)
        if (blockedUntil.size > 256) blockedUntil.values.removeAll { it <= now }
    }
}

/**
 * M7: the [PreAuthLimiter] source for [address]. An IPv6 host can hold a whole /64, so its addresses
 * count as one source; an IPv4 address is its own.
 */
internal fun sourceKey(address: java.net.InetAddress): String =
    if (address is java.net.Inet6Address && !address.isLoopbackAddress)
        address.address.copyOf(8).joinToString("") { "%02x".format(it) } + "/64"
    else address.hostAddress.orEmpty()
