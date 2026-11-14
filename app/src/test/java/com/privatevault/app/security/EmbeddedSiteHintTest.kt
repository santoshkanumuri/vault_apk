package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

/** The site an app's WebView claims to show only orders the picker's hints. It never authorizes a fill. */
class EmbeddedSiteHintTest {
    private val app = "com.example.social"
    private val certificate = "certificate"

    private fun login(title: String, site: String = "", id: String = title, signatures: String = "") =
        VaultEntry(id = id, type = EntryType.PASSWORD, title = title, primaryValue = "user@example.com",
            secondaryValue = "secret", tertiaryValue = site, autofillSignatures = signatures)

    @Test fun loginsForTheClaimedSiteRankFirst() {
        val entries = listOf(
            login("Social", "https://social.example"),            // app-name keyword match
            login("Bank", "https://www.bank.example"),            // same registrable domain as the claim
            login("Bank alt", "https://login.bank.example"),
            login("Bank lookalike", "https://bank.example.attacker.test"),
            login("Mail", "https://mail.example"),
        )
        val ranked = possibleLoginMatches(entries, app, "Social", null, embeddedSite = "https://accounts.bank.example").map { it.title }
        assertEquals(listOf("Bank", "Bank alt", "Social"), ranked)
        // Without a claim, only the app name counts, as before.
        assertEquals(listOf("Social"), possibleLoginMatches(entries, app, "Social", null).map { it.title })
    }

    @Test fun aLoginNamedAfterTheClaimedSiteRanksFirst() {
        // A login saved from such a WebView is named after the site, with no website saved.
        val entries = listOf(login("Social", "https://social.example"), login("accounts.bank.example"), login("My bank"))
        assertEquals(listOf("accounts.bank.example", "Social"),
            possibleLoginMatches(entries, app, "Social", null, embeddedSite = "https://bank.example").map { it.title })
    }

    @Test fun theClaimIsIgnoredForWebsitesAndInvalidSites() {
        val entries = listOf(login("Bank", "https://bank.example"), login("GitHub", "https://github.com"))
        // A website request ranks by its own exact origin; a hint cannot add other sites.
        assertEquals(listOf("GitHub"), possibleLoginMatches(entries, "com.android.chrome", null, "https://github.com",
            embeddedSite = "https://bank.example").map { it.title })
        assertTrue(possibleLoginMatches(entries, app, null, null, embeddedSite = "https://127.0.0.1").isEmpty())
        assertTrue(possibleLoginMatches(entries, app, null, null, embeddedSite = "not a site").isEmpty())
        // Logins already authorized for the app are not repeated as hints.
        assertTrue(possibleLoginMatches(entries, app, null, null, embeddedSite = "https://bank.example") { it.title == "Bank" }.isEmpty())
    }

    @Test fun aLoginSavedForTheClaimedSiteIsNeitherAuthorizedNorSameSite() {
        val saved = login("Bank", "https://bank.example").copy(autofillOrigins = "https://bank.example")
        // The embedded request: no origin, the app's own identity. The claimed site plays no part in any check.
        assertFalse(loginAuthorizedForDestination(saved, app, certificate, null))
        assertNull(sameSiteSavedOrigin(saved, app, certificate, null, embeddedWebView = true))
        assertFalse(pickerFillAllowed(saved, app, certificate, null, true, PickerFillGrant.LINKED))
        assertFalse(pickerFillAllowed(saved, app, certificate, null, true, PickerFillGrant.SAME_SITE_ONCE))
        assertTrue(authorizedPasswordSuggestions(listOf(saved), app, certificate, null).isEmpty())
        // Only the confirmation dialog can fill it, once, for the entry it shows.
        assertFalse(pickerFillAllowed(saved, app, certificate, null, true, PickerFillGrant.CONFIRMED_ONCE))
        assertTrue(pickerFillAllowed(saved, app, certificate, null, true, PickerFillGrant.CONFIRMED_ONCE, confirmedEntryId = saved.id))
        // Linking binds the app's signing identity, never the claimed site.
        val linked = linkLoginToDestination(saved, app, certificate, null)
        assertEquals(saved.autofillOrigins, linked.autofillOrigins)
        assertEquals("$app=$certificate", linked.autofillSignatures)
    }
}
