package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class AccountMatchingTest {
    private fun login(title: String, site: String = "", password: String = "secret", id: String = title, favorite: Boolean = false) =
        VaultEntry(id = id, type = EntryType.PASSWORD, title = title, primaryValue = "user@example.com",
            secondaryValue = password, tertiaryValue = site, favorite = favorite)

    @Test fun siteKeywordUsesRegistrableName() {
        assertEquals("github", siteKeyword("https://accounts.github.com"))
        assertEquals("github", siteKeyword("https://github.com"))
        assertEquals("amazon", siteKeyword("https://www.amazon.co.uk"))
        assertEquals("example", siteKeyword("https://shop.example.com.au/login"))
        assertEquals("mybank", siteKeyword("https://online.mybank.com.br"))
        assertEquals("example", siteKeyword("https://example.com:8443"))
    }

    @Test fun siteKeywordRejectsAddressesAndSingleLabels() {
        assertNull(siteKeyword("https://127.0.0.1"))
        assertNull(siteKeyword("https://192.168.1.20:8080"))
        assertNull(siteKeyword("https://localhost"))
        assertNull(siteKeyword("https://intranet"))
        assertNull(siteKeyword("https://co.uk"))
        assertNull(siteKeyword("http://github.com"))
        assertNull(siteKeyword("not a url"))
        assertNull(siteKeyword(""))
    }

    @Test fun appKeywordsDropGenericWords() {
        assertEquals(setOf("netflix"), appKeywords("com.netflix.mediaclient", "Netflix"))
        assertEquals(setOf("example", "bank", "secure"), appKeywords("org.example.bank.release", "Secure Bank"))
        assertTrue(appKeywords("com.a.b", null).isEmpty())
        assertTrue(appKeywords("com.android.app", "The App").isEmpty())
    }

    @Test fun websiteMatchesSameRegistrableDomain() {
        val entries = listOf(
            login("GitHub", "https://github.com/login"),
            login("Gist", "https://gist.github.com"),
            login("Bare host", "github.com"),
            login("Other", "https://gitlab.com"),
        )
        val titles = possibleLoginMatches(entries, "com.android.chrome", "Chrome", "https://github.com").map { it.title }
        assertEquals(setOf("GitHub", "Gist", "Bare host"), titles.toSet())
        assertFalse("Other" in titles)
    }

    @Test fun sharedHostingSitesAreSeparateServices() {
        assertEquals("evil", siteKeyword("https://evil.github.io"))
        assertTrue(possibleLoginMatches(listOf(login("Docs", "https://docs.github.io")), "com.android.chrome", null,
            "https://evil.github.io").isEmpty())
        assertTrue(possibleLoginMatches(listOf(login("Shop", "https://shop.vercel.app")), "com.android.chrome", null,
            "https://phish.vercel.app").isEmpty())
    }

    @Test fun websiteMatchesSameNameOnAnotherCountrySite() {
        val entries = listOf(login("Shop", "https://www.amazon.com"), login("Radio", "https://www.bbc.co.uk"))
        assertEquals(listOf("Shop"),
            possibleLoginMatches(entries, "com.android.chrome", null, "https://www.amazon.co.uk").map { it.title })
        assertEquals(emptyList<String>(),
            possibleLoginMatches(listOf(login("Shop", "https://www.amazon.co.uk")), "com.android.chrome", null, "https://www.bbc.co.uk").map { it.title })
    }

    @Test fun websiteMatchesWholeWordTitleOnly() {
        val entries = listOf(
            login("Amazon Prime"), login("amazonas travel"), login("My amazon account", "https://example.org"),
        )
        val titles = possibleLoginMatches(entries, "com.android.chrome", null, "https://www.amazon.com").map { it.title }
        assertEquals(setOf("Amazon Prime", "My amazon account"), titles.toSet())
    }

    @Test fun betterMatchesComeFirstThenFavoritesThenTitle() {
        val entries = listOf(
            login("Zed", "https://shop.example.com"),
            login("Same name", "https://example.co.uk"),
            login("Title only example"),
            login("Alpha", "https://example.com"),
            login("Beta", "https://example.com", favorite = true),
        )
        val titles = possibleLoginMatches(entries, "com.android.chrome", null, "https://example.com").map { it.title }
        assertEquals(listOf("Beta", "Alpha", "Zed", "Same name", "Title only example"), titles)
    }

    @Test fun nativeAppMatchesByNameAndSite() {
        val entries = listOf(
            login("Netflix"), login("Streaming", "https://www.netflix.com/login"), login("Hulu", "https://hulu.com"),
        )
        val titles = possibleLoginMatches(entries, "com.netflix.mediaclient", "Netflix", null).map { it.title }
        assertEquals(listOf("Streaming", "Netflix"), titles)
    }

    @Test fun excludesAuthorizedEntriesAndEntriesWithoutPassword() {
        val authorized = login("Authorized", "https://github.com", id = "auth")
        val entries = listOf(authorized, login("Empty", "https://github.com", password = ""), login("Fresh", "https://github.com"),
            VaultEntry(type = EntryType.AUTHENTICATOR, title = "GitHub code", secondaryValue = "JBSWY3DPEHPK3PXP", tertiaryValue = "https://github.com"))
        val titles = possibleLoginMatches(entries, "com.android.chrome", null, "https://github.com") { it.id == "auth" }.map { it.title }
        assertEquals(listOf("Fresh"), titles)
    }

    @Test fun ignoresVeryShortKeywords() {
        val entries = listOf(login("Ab", "https://ab.example.org"), login("X", "https://x.co.uk"))
        assertTrue(possibleLoginMatches(entries, "com.android.chrome", null, "https://ab.com").isEmpty())
        assertTrue(possibleLoginMatches(entries, "com.android.chrome", null, "https://x.com").isEmpty())
        assertTrue(possibleLoginMatches(listOf(login("Ab")), "com.ab.app", "Ab", null).isEmpty())
        // The same registrable domain is still an exact, long-enough signal.
        assertEquals(1, possibleLoginMatches(listOf(login("Short", "https://login.x.com")), "com.android.chrome", null, "https://x.com").size)
    }

    @Test fun malformedAddressesDoNotThrow() {
        val entries = listOf(
            login("Broken", "ht!tp://%%%"), login("Spaces", "https://exa mple.com"), login("Port", "https://example.com:99999"),
            login("Creds", "https://user:pw@example.com"), login("Backslash", "https:\\\\example.com"), login("Blank", "   "),
        )
        // Unparseable sites never authorize or crash; at most their text hints at the site name.
        val hinted = possibleLoginMatches(entries, "com.android.chrome", null, "https://example.com").map { it.title }
        assertTrue(hinted.toSet().all { it in setOf("Port", "Creds", "Backslash") })
        assertTrue(possibleLoginMatches(entries, "com.android.chrome", null, "not an origin").isEmpty())
        assertTrue(possibleLoginMatches(entries, "com.android.chrome", null, "https://127.0.0.1").isEmpty())
        assertTrue(possibleLoginMatches(entries, "", null, null).isEmpty())
    }

    @Test fun newLoginTitleUsesTheUsersNameOrTheDefault() {
        assertEquals("fill.dev", newLoginTitle(null, "fill.dev"))
        assertEquals("fill.dev", newLoginTitle("", "fill.dev"))
        assertEquals("fill.dev", newLoginTitle("   ", "fill.dev"))
        assertEquals("Work mail", newLoginTitle("  Work mail  ", "fill.dev"))
        assertEquals(200, newLoginTitle("x".repeat(500), "fill.dev").length)
        assertEquals("a", newLoginTitle("a" + " ".repeat(300), "fill.dev"))
    }

    @Test fun limitIsApplied() {
        val entries = (1..20).map { login("Site $it", "https://github.com/$it", id = "id$it") }
        assertEquals(8, possibleLoginMatches(entries, "com.android.chrome", null, "https://github.com").size)
        assertEquals(3, possibleLoginMatches(entries, "com.android.chrome", null, "https://github.com", limit = 3).size)
    }
}
