package com.privatevault.app.security

import java.net.IDN
import java.util.Locale

/**
 * The Mozilla Public Suffix List (ICANN and PRIVATE sections), bundled as `nuvori/public_suffix_list.dat` so that
 * "same site" means the same here as in the Windows app (Rust `psl` crate). Read offline, once, on first use.
 * The PRIVATE section matters for security: it makes a.github.io and b.github.io different sites.
 * Rules are kept in ASCII (punycode) form, the form [httpsOrigin] gives hosts.
 */
internal class PublicSuffixList private constructor(
    private val rules: Set<String>,
    /** "ck" for the rule "*.ck". */
    private val wildcards: Set<String>,
    /** "www.ck" for the rule "!www.ck". */
    private val exceptions: Set<String>,
) {
    val size: Int get() = rules.size + wildcards.size + exceptions.size

    /** How many trailing labels of [labels] form the public suffix, per https://publicsuffix.org/list/ ("Algorithm"). */
    private fun suffixLabels(labels: List<String>): Int {
        val suffixes = labels.indices.map { labels.subList(it, labels.size).joinToString(".") }
        // An exception rule always wins; its public suffix drops the rule's leftmost label.
        suffixes.forEachIndexed { i, suffix -> if (suffix in exceptions) return labels.size - i - 1 }
        // Otherwise the matching rule with the most labels wins. A wildcard needs one real label in its place.
        suffixes.forEachIndexed { i, suffix ->
            if (suffix in rules || (i + 1 < labels.size && suffixes[i + 1] in wildcards)) return labels.size - i
        }
        return 1 // The implicit "*" rule: an unlisted top-level domain.
    }

    /** The public suffix plus one label of a lowercase ASCII host, or null when the host is itself a public suffix. */
    fun registrableDomain(asciiHost: String): String? {
        val labels = asciiHost.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        val suffix = suffixLabels(labels)
        if (labels.size <= suffix) return null
        return labels.takeLast(suffix + 1).joinToString(".")
    }

    companion object {
        private const val RESOURCE = "/nuvori/public_suffix_list.dat"

        /** Rule lines as in public_suffix_list.dat. Unknown rule shapes are skipped, never guessed. */
        fun parse(lines: Sequence<String>): PublicSuffixList {
            val rules = HashSet<String>(16_384)
            val wildcards = HashSet<String>(256)
            val exceptions = HashSet<String>(32)
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) continue
                val token = line.split(' ', '\t').first()
                when {
                    token.startsWith("!") -> asciiRule(token.substring(1))?.let(exceptions::add)
                    token.startsWith("*.") -> asciiRule(token.substring(2))?.let(wildcards::add)
                    '*' in token || '!' in token -> continue
                    else -> asciiRule(token)?.let(rules::add)
                }
            }
            return PublicSuffixList(rules, wildcards, exceptions)
        }

        private fun asciiRule(rule: String): String? = runCatching {
            val lower = rule.lowercase(Locale.ROOT)
            (if (lower.all { it.code < 0x80 }) lower else IDN.toASCII(lower, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT))
                .takeIf { it.isNotEmpty() && '*' !in it }
        }.getOrNull()

        /** Loaded on first use and kept. A missing or truncated list fails closed: no host gets a registrable domain. */
        val bundled: PublicSuffixList by lazy {
            val list = requireNotNull(PublicSuffixList::class.java.getResourceAsStream(RESOURCE)) { "Public Suffix List missing" }
                .bufferedReader(Charsets.UTF_8).useLines(::parse)
            // Without the PRIVATE section, separate customer sites on shared hosting would look like one site.
            check(list.size > 5_000 && "co.uk" in list.rules && "github.io" in list.rules && "ck" in list.wildcards)
            list
        }
    }
}

/**
 * The registrable domain ("site") of an ASCII or Unicode host, in ASCII form: "login.example.co.uk" -> "example.co.uk".
 * Null for IP addresses, single labels and hosts that are themselves a public suffix (co.uk, github.io).
 */
internal fun registrableDomainOfHost(host: String): String? = runCatching {
    // Same conversion as httpsOrigin, so a Unicode host and its punycode form give one answer.
    val ascii = IDN.toASCII(host.trim().lowercase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    val labels = ascii.split('.')
    // No top-level domain is numeric, so a numeric last label is an IPv4 address (or junk).
    if (labels.size < 2 || labels.any { it.isEmpty() } || labels.last().all(Char::isDigit)) return null
    PublicSuffixList.bundled.registrableDomain(ascii)
}.getOrNull()

/** The registrable domain of an https origin: "https://login.example.co.uk" -> "example.co.uk". Null for anything else. */
internal fun registrableDomain(origin: String): String? {
    val canonical = httpsOrigin(origin) ?: return null
    return registrableDomainOfHost(canonical.removePrefix("https://").substringBefore(':'))
}
