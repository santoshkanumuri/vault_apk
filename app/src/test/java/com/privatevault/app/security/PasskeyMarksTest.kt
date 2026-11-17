package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.PasskeySummary
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyMarksTest {
    private fun login(username: String, website: String, origins: String = "") = VaultEntry(type = EntryType.PASSWORD,
        title = website, primaryValue = username, secondaryValue = "secret", tertiaryValue = website, autofillOrigins = origins)

    @Test fun marksOnlyTheSameAccountOnASiteTheRpIdCovers() {
        val github = PasskeySummary("p1", "github.com", "dev@example.com", 1)
        val sameAccount = login("Dev@Example.com ", "https://github.com/login")
        val subdomain = login("dev@example.com", "https://gist.github.com")
        val bareHost = login("dev@example.com", "github.com")
        val linkedOrigin = login("dev@example.com", "", origins = "https://github.com")
        val otherAccount = login("work@example.com", "https://github.com")
        val otherSite = login("dev@example.com", "https://gitlab.com")
        val lookalike = login("dev@example.com", "https://notgithub.com")
        val plainHttp = login("dev@example.com", "http://github.com")
        val all = listOf(sameAccount, subdomain, bareHost, linkedOrigin, otherAccount, otherSite, lookalike, plainHttp)

        assertTrue(loginsWithPasskeys(all, emptyList()).isEmpty())
        assertEquals(setOf(sameAccount.id, subdomain.id, bareHost.id, linkedOrigin.id), loginsWithPasskeys(all, listOf(github)))
    }

    @Test fun anRpIdMustBeTheHostOrAParentAndNeverAPublicSuffix() {
        val entry = login("alice", "https://example.com")
        assertFalse(passkeyMatchesLogin("login.example.com", "alice", entry))
        assertFalse(passkeyMatchesLogin("com", "alice", entry))
        assertFalse(passkeyMatchesLogin("github.io", "alice", login("alice", "https://alice.github.io")))
        assertTrue(passkeyMatchesLogin("alice.github.io", "alice", login("alice", "https://alice.github.io")))
    }

    @Test fun noUsernameNoWebsiteOrAnotherTypeGivesNoMark() {
        assertFalse(passkeyMatchesLogin("example.com", "alice", login(" ", "https://example.com")))
        assertFalse(passkeyMatchesLogin("example.com", "alice", login("alice", "")))
        assertFalse(passkeyMatchesLogin("example.com", "alice",
            VaultEntry(type = EntryType.NOTE, title = "Note", primaryValue = "alice", tertiaryValue = "https://example.com")))
    }
}
