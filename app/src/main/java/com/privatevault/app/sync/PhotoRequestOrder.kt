package com.privatevault.app.sync

/**
 * M13: which missing images a session asks a peer for. A session may ask for [limit] at most (the
 * peer refuses more), so images a peer recently said it lacks go last, the longest ago first; the
 * others are asked for in hash order, as before.
 */
internal object PhotoRequestOrder {
    fun pick(missing: Collection<String>, lackedAt: Map<String, Long>, limit: Int): List<String> =
        missing.distinct().sortedWith(compareBy({ lackedAt[it] ?: 0L }, { it })).take(limit)
}
