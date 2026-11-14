package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class LoginSearchTest {
    private fun login(title: String, user: String = "", site: String = "", favorite: Boolean = false, opened: Long = 0,
        origins: String = "", password: String = "secret") =
        VaultEntry(id = title, type = EntryType.PASSWORD, title = title, primaryValue = user, secondaryValue = password,
            tertiaryValue = site, autofillOrigins = origins, favorite = favorite, lastOpenedAt = opened)

    private fun rank(entries: List<VaultEntry>, query: String, exact: Set<String> = emptySet(), sameSite: Set<String> = emptySet()) =
        rankLoginSearch(entries, query) {
            when (it.title) { in exact -> LoginMatchTier.EXACT; in sameSite -> LoginMatchTier.SAME_SITE; else -> LoginMatchTier.OTHER }
        }.map { it.title }

    @Test fun ranksExactThenSameSiteThenPrefixThenSubstring() {
        val entries = listOf(
            login("My Bank savings", site = "https://bank.example"),
            login("Bank", user = "me"),
            login("Linked", site = "https://www.bank.example"),
            login("Sister", site = "https://login.bank.example"),
            login("Mail", user = "banker@mail.test"),
            login("Unrelated", site = "https://other.example"),
            login("Online", site = "https://mybank.test"),
        )
        // Title prefix, username prefix and title word prefix share a bucket (then title order); site-only is last.
        // An exact login that does not match the text is not listed.
        assertEquals(listOf("Linked", "Sister", "Bank", "Mail", "My Bank savings", "Online"),
            rank(entries, " BANK ", exact = setOf("Linked", "Unrelated"), sameSite = setOf("Sister")))
    }

    @Test fun prefixBucketBeforeSubstringBucket() {
        val entries = listOf(login("Zeta", site = "https://shop.test"), login("Shop", user = "a"), login("Alpha shopping"))
        // "Shop" title prefix and "Alpha shopping" word prefix come before a site-only match.
        assertEquals(listOf("Alpha shopping", "Shop", "Zeta"), rank(entries, "shop"))
    }

    @Test fun tiesBreakByFavoriteThenRecentThenTitle() {
        val entries = listOf(
            login("Delta", site = "https://x.test"), login("Charlie", site = "https://x.test", opened = 5),
            login("Bravo", site = "https://x.test", opened = 9), login("Alpha", site = "https://x.test"),
            login("Echo", site = "https://x.test", favorite = true),
        )
        assertEquals(listOf("Echo", "Bravo", "Charlie", "Alpha", "Delta"), rank(entries, "x.test"))
        // Tiers still come before favorites.
        assertEquals(listOf("Delta", "Echo", "Bravo", "Charlie", "Alpha"), rank(entries, "x.test", sameSite = setOf("Delta")))
    }

    @Test fun searchesTitleUsernameSiteAndLinkedOriginsButNeverPasswordsOrNotes() {
        val entries = listOf(
            login("Work", user = "dev@corp.test", password = "hunter2-unique"),
            login("Portal", origins = "https://sso.portal.test\nhttps://id.portal.test"),
            VaultEntry(id = "notes", type = EntryType.PASSWORD, title = "Notes", secondaryValue = "x", notes = "needle-in-notes"),
        )
        assertEquals(listOf("Work"), rank(entries, "corp"))
        assertEquals(listOf("Portal"), rank(entries, "id.portal"))
        assertTrue(rank(entries, "hunter2").isEmpty())
        assertTrue(rank(entries, "needle").isEmpty())
        assertTrue(rank(entries, "nothing matches").isEmpty())
    }

    @Test fun blankQueryKeepsEveryEntryInRankOrder() {
        val entries = listOf(login("B"), login("A"), login("C", favorite = true), login("D"))
        assertEquals(listOf("D", "C", "A", "B"), rank(entries, "   ", sameSite = setOf("D")))
        assertEquals(listOf("C", "A", "B", "D"), rank(entries, ""))
    }
}
