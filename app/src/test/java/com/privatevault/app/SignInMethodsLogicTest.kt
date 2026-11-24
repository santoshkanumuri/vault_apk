package com.privatevault.app

import com.privatevault.app.autofill.SignInReminders
import com.privatevault.app.data.SignInMethod
import com.privatevault.app.sync.SignInMethodSyncRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sign-ins screen, a login's section and the Autofill reminder: the same rules as Windows `src/lib/signIns.ts`. */
class SignInMethodsLogicTest {
    private fun method(site: String, provider: String, account: String, last: Long, pending: Boolean = false) =
        SignInMethod(site, provider, account.lowercase(), "id-$site-$provider-$account", account, 1, last, 1,
            "2026-11-20T10:00:00Z", "pc", pending)

    private val items = listOf(
        method("example.com", "github", "octocat", 30),
        method("example.com", "google", "me@example.com", 50),
        method("bbc.co.uk", "apple", "", 40),
        method("other.example", "github", "OctoCat", 10),
    )

    @Test fun groupsBySiteMostRecentFirstWithEveryFilter() {
        val all = SignInMethodsLogic.groupBySite(items)
        assertEquals(listOf("example.com", "bbc.co.uk", "other.example"), all.map { it.site })
        assertEquals(listOf("google", "github"), all.first().methods.map { it.provider })
        assertEquals(listOf("example.com", "other.example"), SignInMethodsLogic.groupBySite(items, provider = "github").map { it.site })
        // The account filter ignores case; "not known" finds the methods without one.
        assertEquals(listOf("example.com", "other.example"), SignInMethodsLogic.groupBySite(items, account = "OCTOCAT").map { it.site })
        assertEquals(listOf("bbc.co.uk"), SignInMethodsLogic.groupBySite(items, account = SignInMethodsLogic.NO_ACCOUNT).map { it.site })
        // The search matches any page of a site, or part of its name.
        assertEquals(listOf("example.com"), SignInMethodsLogic.groupBySite(items, query = "https://Login.Example.com/path").map { it.site })
        assertEquals(listOf("bbc.co.uk"), SignInMethodsLogic.groupBySite(items, query = "bbc").map { it.site })
        assertTrue(SignInMethodsLogic.groupBySite(items, query = "nothing.test").isEmpty())
        assertEquals(listOf("me@example.com", "octocat"), SignInMethodsLogic.accountsOf(items))
        assertEquals(listOf("apple", "github", "google"), SignInMethodsLogic.providersOf(items).sorted())
        assertEquals("the fixed provider order", listOf("google", "apple", "github"), SignInMethodsLogic.providersOf(items))
    }

    @Test fun aLoginsSiteIsItsRegistrableDomainLikeOnWindows() {
        assertEquals("example.com", SignInMethodsLogic.siteOfLoginUrl("https://login.example.com/path"))
        assertEquals("example.com", SignInMethodsLogic.siteOfLoginUrl("www.example.com/login"))
        assertEquals("example.com", SignInMethodsLogic.siteOfLoginUrl("http://app.example.com"))
        assertEquals("bbc.co.uk", SignInMethodsLogic.siteOfLoginUrl("https://account.bbc.co.uk:443"))
        assertEquals("alice.github.io", SignInMethodsLogic.siteOfLoginUrl("https://alice.github.io"))
        assertNull(SignInMethodsLogic.siteOfLoginUrl(""))
        assertNull(SignInMethodsLogic.siteOfLoginUrl("ftp://example.com"))
        assertNull(SignInMethodsLogic.siteOfLoginUrl("https://192.168.1.10"))
        assertEquals(listOf("google", "github"),
            SignInMethodsLogic.forLogin(items, "https://www.example.com/login").map { it.provider })
        assertTrue(SignInMethodsLogic.forLogin(items, "https://example.org").isEmpty())
    }

    @Test fun theReminderShowsTheLatestMethodAndFillsOnlyAnEmptyFocusedAccountField() {
        val reminder = SignInReminders.pick(items + method("example.com", "x", "@jack", 99, pending = true), setOf("example.com"))
        assertEquals("the most recent one; a delete waiting here is never shown", "google", reminder?.provider)
        assertEquals("Last time: Google · me@example.com", SignInReminders.title(requireNotNull(reminder)))
        assertEquals("Last time: Apple", SignInReminders.title(items[2]))
        assertNull(SignInReminders.pick(items, emptySet()))
        assertEquals("me@example.com", SignInReminders.fillableAccount(" me@example.com ", usernameField = true, focusedEmpty = true))
        assertNull("not in the field", SignInReminders.fillableAccount("octocat", usernameField = true, focusedEmpty = false))
        assertNull("no account field", SignInReminders.fillableAccount("octocat", usernameField = false, focusedEmpty = true))
        assertNull("a display name is not a login", SignInReminders.fillableAccount("Jamie Doe", usernameField = true, focusedEmpty = true))
        assertNull("not known", SignInReminders.fillableAccount("", usernameField = true, focusedEmpty = true))
    }

    @Test fun everyProviderHasTheWindowsMarkOrLetterTile() {
        assertEquals(SignInMethodSyncRules.PROVIDERS, SignInMarks.ALL.keys.toList())
        val letters = SignInMarks.ALL.filterValues { it.path == null }.mapValues { it.value.letter }
        assertEquals(mapOf("microsoft" to "M", "linkedin" to "in", "amazon" to "a", "slack" to "S"), letters)
        SignInMarks.ALL.values.filter { it.path != null }.forEach { assertTrue(it.title, it.path!!.startsWith("M")) }
        assertEquals("GitHub", SignInMarks.name("github"))
        assertEquals("gitlab", SignInMarks.name("gitlab"))
        // Dark brand tiles take white ink, light ones near-black.
        assertEquals(0xFFFFFFFF.toInt(), signInMarkInk(0x181717))
        assertEquals(0xFFFFFFFF.toInt(), signInMarkInk(0x0866FF))
    }
}
