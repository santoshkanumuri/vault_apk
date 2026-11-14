package com.privatevault.app.security

import com.privatevault.app.data.VaultEntry
import java.util.Locale

/** How a saved login relates to the destination asking. Sets its rank and its one-tap action in the picker. */
internal enum class LoginMatchTier { EXACT, SAME_SITE, OTHER }

private val titleWordBreak = Regex("[^\\p{L}\\p{N}]+")

/**
 * Picker search over saved logins. Matches [query] (case-insensitive substring) in the title, username, saved
 * website and linked origins. Passwords and notes are never searched. A blank query keeps every entry.
 * Order: exact-authorized, same-site, title/username prefix (or a title word prefix), other matches;
 * then favorites, most recently opened, title, username.
 */
internal fun rankLoginSearch(
    entries: List<VaultEntry>,
    query: String,
    tier: (VaultEntry) -> LoginMatchTier,
): List<VaultEntry> {
    val q = query.trim().lowercase(Locale.ROOT)
    fun has(text: String) = text.lowercase(Locale.ROOT).contains(q)
    fun prefix(entry: VaultEntry): Boolean {
        val title = entry.title.trim().lowercase(Locale.ROOT)
        return title.startsWith(q) || entry.primaryValue.trim().lowercase(Locale.ROOT).startsWith(q) ||
            title.split(titleWordBreak).any { it.isNotEmpty() && it.startsWith(q) }
    }
    return entries.asSequence()
        .filter { q.isEmpty() || has(it.title) || has(it.primaryValue) || has(it.tertiaryValue) || has(it.autofillOrigins) }
        .map { entry ->
            val bucket = when (tier(entry)) {
                LoginMatchTier.EXACT -> 0
                LoginMatchTier.SAME_SITE -> 1
                LoginMatchTier.OTHER -> if (q.isNotEmpty() && prefix(entry)) 2 else 3
            }
            entry to bucket
        }
        .sortedWith(compareBy<Pair<VaultEntry, Int>> { it.second }
            .thenByDescending { it.first.favorite }
            .thenByDescending { it.first.lastOpenedAt }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.primaryValue }
            .thenBy { it.first.id })
        .map { it.first }
        .toList()
}
