package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import java.util.Locale

/**
 * Heuristic hints for the account picker. A match here is only ever a suggestion: filling still goes
 * through the explicit confirmation dialog, which names the exact destination.
 */

private val appStopWords = setOf(
    "com", "org", "net", "io", "app", "apps", "android", "mobile", "client", "mediaclient", "prod",
    "production", "release", "beta", "alpha", "debug", "lite", "free", "pro", "main", "www", "the",
    "and", "for", "official", "droid", "inc", "ltd", "llc",
)

private val nonWord = Regex("[^\\p{L}\\p{N}]+")

private class Registrable(val label: String, val domain: String)

/** Uses the full Public Suffix List. A host that is itself a public suffix (co.uk, github.io) has no site name. */
private fun registrable(origin: String): Registrable? {
    val domain = registrableDomain(origin) ?: return null
    return Registrable(domain.substringBefore('.'), domain)
}

private fun words(text: String): Set<String> =
    text.lowercase(Locale.ROOT).split(nonWord).filter { it.isNotEmpty() }.toSet()

/** The site name of an https origin, e.g. "github" for https://accounts.github.com. Null for IPs and single labels. */
internal fun siteKeyword(origin: String): String? = registrable(origin)?.label

/** Distinctive lowercase words from an app's package name and label. */
internal fun appKeywords(packageName: String, appLabel: String?): Set<String> =
    words("$packageName ${appLabel.orEmpty()}")
        .filter { it.length >= 3 && it !in appStopWords && !it.all(Char::isDigit) }.toSet()

/** A saved login's website as an https origin. Accepts a bare host such as "github.com". */
private fun savedSiteOrigin(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    httpsOrigin(text)?.let { return it }
    val bare = when {
        text.startsWith("http://", ignoreCase = true) -> text.substring(7)
        "://" in text -> return null
        else -> text
    }
    return httpsOrigin("https://$bare")
}

private fun savedSites(entry: VaultEntry): List<String> =
    (listOf(entry.tertiaryValue) + entry.autofillOrigins.lines()).mapNotNull(::savedSiteOrigin)

private fun websiteScore(entry: VaultEntry, destination: Registrable): Int {
    val keyword = destination.label.filter(Char::isLetterOrDigit)
    var best = 0
    for (site in savedSites(entry)) {
        val saved = registrable(site) ?: continue
        if (saved.domain == destination.domain) return 3
        if (saved.label == destination.label && keyword.length >= 3) best = maxOf(best, 2)
    }
    if (best == 0 && keyword.length >= 3 &&
        (keyword in words(entry.title) || keyword in words(entry.tertiaryValue))) best = 1
    return best
}

private fun appScore(entry: VaultEntry, keywords: Set<String>): Int {
    if (keywords.isEmpty()) return 0
    if (savedSites(entry).any { site -> siteKeyword(site)?.let { it in keywords } == true }) return 2
    return if (words(entry.title).any { it in keywords }) 1 else 0
}

/** Ranks above every app-name score: a saved site with the registrable domain an app's WebView claims to show. */
private const val EMBEDDED_SITE_SCORE = 10

/**
 * A saved site of [entry] has the same registrable domain as [site], an embedded WebView's claimed https site. A title
 * that is a host counts too: a login saved from such a WebView is named after the site, never linked to it.
 */
private fun embeddedSiteScore(entry: VaultEntry, site: Registrable?): Int =
    if (site != null && (savedSites(entry) + listOfNotNull(savedSiteOrigin(entry.title)))
            .any { registrable(it)?.domain == site.domain }) EMBEDDED_SITE_SCORE else 0

/**
 * Saved logins that look related to the destination but are not authorized for it, best first.
 * [isAuthorized] keeps the real trust check (signing identity or exact origin) with the caller.
 * [embeddedSite] is the site an app's WebView claims to show (only when [origin] is null). The app chose it, so it
 * only orders this list: logins saved for that site come first, then app-name matches. It authorizes nothing.
 */
internal fun possibleLoginMatches(
    entries: List<VaultEntry>,
    packageName: String,
    appLabel: String?,
    origin: String?,
    limit: Int = 8,
    embeddedSite: String? = null,
    isAuthorized: (VaultEntry) -> Boolean = { false },
): List<VaultEntry> {
    val website = origin?.let { registrable(it) ?: return emptyList() }
    val keywords = if (origin == null) appKeywords(packageName, appLabel) else emptySet()
    val claimed = if (origin == null) embeddedSite?.let { runCatching { registrable(it) }.getOrNull() } else null
    return entries.asSequence()
        .filter { it.type == EntryType.PASSWORD && it.secondaryValue.isNotEmpty() && !isAuthorized(it) }
        .map { entry -> entry to runCatching { if (website != null) websiteScore(entry, website)
            else maxOf(embeddedSiteScore(entry, claimed), appScore(entry, keywords)) }.getOrDefault(0) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<VaultEntry, Int>> { it.second }
            .thenByDescending { it.first.favorite }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.primaryValue })
        .take(limit.coerceAtLeast(0))
        .map { it.first }
        .toList()
}

/** The name for a new saved login: the user's choice when given (trimmed, at most 200 characters), otherwise [default]. */
internal fun newLoginTitle(requested: String?, default: String): String =
    requested?.trim()?.take(200)?.trimEnd()?.takeIf { it.isNotEmpty() } ?: default
