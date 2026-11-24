package com.privatevault.app.security

import com.privatevault.app.data.VaultEntry
import java.util.Locale
import kotlin.math.pow

/**
 * How often this device used a saved login, passkey or Quick copy item. [score] is a use count that halves every
 * [ItemUsage.HALF_LIFE_MS], so an account the user moved away from slowly drops. [count] is the plain total.
 * Ids only: no titles, accounts, sites or values.
 */
internal data class ItemUse(val score: Double, val count: Int, val lastUsedAt: Long)

internal object ItemUsage {
    /** A use a month ago counts half as much as one today. */
    const val HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000
    /** The most a record holds; the least used ids go first. */
    const val MAX_ITEMS = 2000
    /** A "Most used" badge needs at least this many uses, so one tap never earns it. */
    const val BADGE_MIN_COUNT = 2

    fun decayed(score: Double, elapsedMs: Long): Double =
        if (elapsedMs <= 0) score else score * 0.5.pow(elapsedMs.toDouble() / HALF_LIFE_MS)

    /** [uses] with one more use of [id] at [now], trimmed to [MAX_ITEMS]. */
    fun record(uses: Map<String, ItemUse>, id: String, now: Long): Map<String, ItemUse> {
        if (id.isBlank()) return uses
        val previous = uses[id]
        val score = (previous?.let { decayed(it.score, now - it.lastUsedAt) } ?: 0.0) + 1.0
        val updated = uses + (id to ItemUse(score, (previous?.count ?: 0) + 1, maxOf(now, previous?.lastUsedAt ?: 0L)))
        if (updated.size <= MAX_ITEMS) return updated
        return updated.entries.sortedByDescending { decayed(it.value.score, now - it.value.lastUsedAt) }
            .take(MAX_ITEMS).associate { it.key to it.value }
    }
}

/** The usage record read once, at [now], for ranking and badges. */
internal class UsageSnapshot(private val uses: Map<String, ItemUse>, private val now: Long) {
    fun score(id: String): Double = uses[id]?.let { ItemUsage.decayed(it.score, now - it.lastUsedAt) } ?: 0.0

    /** The plain count and last use of [id], or null when this device never used it. */
    fun use(id: String): ItemUse? = uses[id]

    /**
     * The one id in [ids] that earns the "Most used" badge: used at least [ItemUsage.BADGE_MIN_COUNT] times and
     * strictly ahead of every other. Null for fewer than two ids or a tie, since a badge on a lone item says nothing.
     */
    fun mostUsed(ids: Collection<String>): String? {
        val distinct = ids.distinct()
        if (distinct.size < 2) return null
        val ranked = distinct.map { it to score(it) }.sortedByDescending { it.second }
        val (top, best) = ranked[0]
        if (best <= 0.0 || best <= ranked[1].second) return null
        return top.takeIf { (uses[it]?.count ?: 0) >= ItemUsage.BADGE_MIN_COUNT }
    }

    companion object {
        val EMPTY = UsageSnapshot(emptyMap(), 0L)
    }
}

/** Most used first; items never used keep their order. Stable, so earlier sort keys still break ties. */
internal fun <T> List<T>.sortedByUse(usage: UsageSnapshot, id: (T) -> String): List<T> =
    sortedByDescending { usage.score(id(it)) }

/**
 * Which site a saved login belongs to, for one badge per site: the registrable domain of its website
 * (`accounts.google.com` and `mail.google.com` are one site). A login without a usable website groups by its name.
 */
internal fun loginSiteKey(entry: VaultEntry): String {
    val text = entry.tertiaryValue.trim()
    val origin = httpsOrigin(text) ?: if (text.isNotEmpty() && "://" !in text) httpsOrigin("https://$text") else null
    return origin?.let { runCatching { registrableDomain(it) }.getOrNull() }
        ?: ("name:" + entry.title.trim().lowercase(Locale.ROOT))
}

/** The ids of the logins that earn "Most used": at most one per site ([loginSiteKey]), see [UsageSnapshot.mostUsed]. */
internal fun mostUsedPerSite(entries: List<VaultEntry>, usage: UsageSnapshot): Set<String> =
    entries.groupBy(::loginSiteKey).values.mapNotNull { group -> usage.mostUsed(group.map { it.id }) }.toSet()
