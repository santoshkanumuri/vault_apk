package com.privatevault.app

import com.privatevault.app.data.SignInMethod
import com.privatevault.app.security.httpsOrigin
import com.privatevault.app.security.registrableDomain
import java.util.Locale

/**
 * Filters, grouping and site matching for the Sign-ins screen and a login's detail screen. The same rules as the
 * Windows page (`src/lib/signIns.ts`). Pure, so JVM tests cover it.
 */
internal object SignInMethodsLogic {
    /** The account filter's value for methods whose account is not known. */
    const val NO_ACCOUNT = "\u0000none"

    data class SiteGroup(val site: String, val methods: List<SignInMethod>, val lastUsedAt: Long)

    /** "https://Login.Example.com/path" -> "login.example.com"; plain words stay as typed, in lower case. */
    fun searchHost(text: String): String {
        var value = text.trim().lowercase(Locale.ROOT)
        value = value.replace(Regex("^[a-z][a-z0-9+.-]*://"), "").split('/', '?', '#').first()
            .replace(Regex(":\\d*$"), "").trimEnd('.')
        return value.removePrefix("www.")
    }

    /** The search matches the site, any page of it ("login.example.com" finds example.com), or part of its name. */
    fun siteMatches(site: String, query: String): Boolean {
        val host = searchHost(query)
        return host.isEmpty() || site == host || host.endsWith(".$site") || site.contains(host)
    }

    /** Groups by site, most recently used site first; each group's methods most recent first. */
    fun groupBySite(items: List<SignInMethod>, provider: String = "", account: String = "", query: String = ""): List<SiteGroup> {
        val wanted = account.trim().lowercase(Locale.ROOT)
        return items.asSequence()
            .filter { provider.isEmpty() || it.provider == provider }
            .filter { account.isEmpty() || if (account == NO_ACCOUNT) it.account.isEmpty() else it.account.lowercase(Locale.ROOT) == wanted }
            .filter { query.isBlank() || siteMatches(it.site, query) }
            .groupBy { it.site }
            .map { (site, methods) ->
                val sorted = methods.sortedWith(compareByDescending<SignInMethod> { it.lastUsedAt }.thenBy { it.account })
                SiteGroup(site, sorted, sorted.first().lastUsedAt)
            }
            .sortedWith(compareByDescending<SiteGroup> { it.lastUsedAt }.thenBy { it.site })
    }

    /** The accounts in use, for the account filter: distinct ignoring case, sorted. */
    fun accountsOf(items: List<SignInMethod>): List<String> = items.filter { it.account.isNotEmpty() }
        .distinctBy { it.account.lowercase(Locale.ROOT) }.map { it.account }.sortedBy { it.lowercase(Locale.ROOT) }

    /** The providers in use, in the fixed provider order. */
    fun providersOf(items: List<SignInMethod>): List<String> {
        val used = items.mapTo(HashSet()) { it.provider }
        return SignInMarks.ALL.keys.filter { it in used }
    }

    /**
     * The registrable domain of a login's saved website, as Windows reads it (`sign_ins::site_of_login_url`): a bare
     * host ("seedr.cc") or an http address counts as that host's HTTPS site. Null when there is no usable site.
     */
    fun siteOfLoginUrl(url: String): String? {
        val text = url.trim()
        if (text.isEmpty()) return null
        val scheme = text.substringBefore("://", "")
        val https = when {
            !text.contains("://") -> "https://$text"
            scheme.equals("http", ignoreCase = true) -> "https://" + text.substringAfter("://")
            else -> text
        }
        val origin = httpsOrigin(originPart(https)) ?: return null
        return registrableDomain(origin)
    }

    /** "https://host:port/path?x" -> "https://host:port". */
    private fun originPart(url: String): String {
        val rest = url.substringAfter("://")
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        return url.substringBefore("://") + "://" + rest.substring(0, end)
    }

    /** The methods for a login's site (same registrable domain), most recently used first. */
    fun forLogin(items: List<SignInMethod>, loginUrl: String): List<SignInMethod> {
        val site = siteOfLoginUrl(loginUrl) ?: return emptyList()
        return items.filter { it.site == site }.sortedByDescending { it.lastUsedAt }
    }

    /** "GitHub · octocat", or the provider alone when the account is not known. */
    fun label(method: SignInMethod): String =
        SignInMarks.name(method.provider) + if (method.account.isEmpty()) "" else " · ${method.account}"
}
