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
) {
    private val active = HashMap<String, Int>()
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val blockedUntil = HashMap<String, Long>()

    /** True when a new connection from [source] may start its check now; call [finish] afterwards. */
    @Synchronized fun tryStart(source: String, now: Long): Boolean {
        blockedUntil[source]?.let { if (now < it) return false else blockedUntil.remove(source) }
        val mine = active[source] ?: 0
        if (mine >= perSource || active.values.sum() >= total) return false
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
