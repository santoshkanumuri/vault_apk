package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import java.util.Locale

/**
 * Heuristic hints for the account picker. A match here is only ever a suggestion: filling still goes
 * through the explicit "Fill and link" confirmation, which names the exact destination.
 */

// Common two-part public suffixes. Not the full Public Suffix List; a miss only weakens a suggestion.
private val multiPartSuffixes = setOf(
    "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk",
    "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
    "co.in", "net.in", "org.in", "firm.in", "gen.in", "ind.in", "ac.in", "edu.in", "gov.in",
    "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "co.kr", "or.kr", "ne.kr", "go.kr", "ac.kr",
    "co.nz", "net.nz", "org.nz", "govt.nz", "ac.nz", "co.za", "org.za", "net.za", "gov.za", "ac.za",
    "com.br", "net.br", "org.br", "gov.br", "edu.br", "com.mx", "org.mx", "gob.mx", "edu.mx", "net.mx",
    "com.sg", "org.sg", "edu.sg", "gov.sg", "net.sg", "com.tr", "org.tr", "net.tr", "gov.tr", "edu.tr",
    "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "com.hk", "org.hk", "net.hk", "edu.hk", "gov.hk",
    "com.tw", "org.tw", "net.tw", "edu.tw", "gov.tw", "com.ar", "com.co", "com.pe", "com.ve", "com.uy",
    "com.ec", "com.my", "org.my", "net.my", "edu.my", "gov.my", "com.ph", "org.ph", "net.ph", "com.vn",
    "com.pk", "com.bd", "com.ng", "com.eg", "com.sa", "com.ua", "com.pl", "co.il", "org.il", "co.id",
    "or.id", "web.id", "ac.id", "go.id", "co.th", "in.th", "ac.th", "co.ke", "co.tz", "co.ug", "co.ae",
    // Shared hosting: every customer site sits under these, so they act like public suffixes.
    "github.io", "gitlab.io", "vercel.app", "netlify.app", "pages.dev", "workers.dev", "web.app", "firebaseapp.com",
    "appspot.com", "herokuapp.com", "blogspot.com", "azurewebsites.net", "cloudfront.net", "onrender.com",
    "glitch.me", "wixsite.com", "fly.dev", "repl.co",
)

private val appStopWords = setOf(
    "com", "org", "net", "io", "app", "apps", "android", "mobile", "client", "mediaclient", "prod",
    "production", "release", "beta", "alpha", "debug", "lite", "free", "pro", "main", "www", "the",
    "and", "for", "official", "droid", "inc", "ltd", "llc",
)

private val nonWord = Regex("[^\\p{L}\\p{N}]+")

private class Registrable(val label: String, val domain: String)

private fun registrable(origin: String): Registrable? {
    val canonical = httpsOrigin(origin) ?: return null
    val labels = canonical.removePrefix("https://").substringBefore(':').split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() } || labels.all { label -> label.all(Char::isDigit) }) return null
    val suffixLabels = if ("${labels[labels.size - 2]}.${labels.last()}" in multiPartSuffixes) 2 else 1
    // The host is itself a public suffix such as co.uk: there is no site name to compare.
    if (labels.size <= suffixLabels) return null
    return Registrable(labels[labels.size - suffixLabels - 1], labels.takeLast(suffixLabels + 1).joinToString("."))
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

/**
 * Saved logins that look related to the destination but are not authorized for it, best first.
 * [isAuthorized] keeps the real trust check (signing identity or exact origin) with the caller.
 */
internal fun possibleLoginMatches(
    entries: List<VaultEntry>,
    packageName: String,
    appLabel: String?,
    origin: String?,
    limit: Int = 8,
    isAuthorized: (VaultEntry) -> Boolean = { false },
): List<VaultEntry> {
    val website = origin?.let { registrable(it) ?: return emptyList() }
    val keywords = if (origin == null) appKeywords(packageName, appLabel) else emptySet()
    return entries.asSequence()
        .filter { it.type == EntryType.PASSWORD && it.secondaryValue.isNotEmpty() && !isAuthorized(it) }
        .map { entry -> entry to runCatching { if (website != null) websiteScore(entry, website) else appScore(entry, keywords) }.getOrDefault(0) }
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
