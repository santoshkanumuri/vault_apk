package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class LoginSecurityTest {
    @Test fun embeddedAppNeedsItsOwnCertificateLinkEvenWhenWebsiteMatches() {
        val entry = VaultEntry(type = EntryType.PASSWORD, title = "Website login", secondaryValue = "secret",
            tertiaryValue = "https://fill.dev/login", autofillOrigins = "https://fill.dev")
        assertFalse(loginAuthorizedForDestination(entry, "com.example.embedded", "certificate", null))
        val linked = linkLoginToDestination(entry, "com.example.embedded", "certificate", null)
        assertTrue(loginAuthorizedForDestination(linked, "com.example.embedded", "certificate", null))
        assertFalse(loginAuthorizedForDestination(linked, "com.example.embedded", "replacement-certificate", null))
        assertEquals(entry.tertiaryValue, linked.tertiaryValue)
        assertEquals(entry.autofillOrigins, linked.autofillOrigins)
    }
    @Test fun requiresExactPackageAndSigningIdentityAndLoginType() {
        val entry = VaultEntry(type = EntryType.PASSWORD, title = "Bank", autofillSignatures = "com.example.bank=certificate")
        assertTrue(loginAuthorized(entry, "com.example.bank", "certificate"))
        assertFalse(loginAuthorized(entry, "com.example.bank.fake", "certificate"))
        assertFalse(loginAuthorized(entry, "com.example.bank", "different"))
        assertFalse(loginAuthorized(entry, "com.example.bank", ""))
        assertFalse(loginAuthorized(entry.copy(type = EntryType.AUTHENTICATOR), "com.example.bank", "certificate"))
        assertFalse(loginAuthorized(entry.copy(autofillSignatures = "", linkedApps = "com.example.bank"), "com.example.bank", "certificate"))
    }
    @Test fun generatedPasswordsMeetLengthAndUseAllowedCharacters() {
        val passwords = List(100) { generateLoginPassword() }
        assertTrue(passwords.all { it.length == 24 && it.matches(Regex("[A-Za-z0-9!@#%*_=+?\\-]+")) })
        assertEquals(passwords.size, passwords.toSet().size)
        assertThrows(IllegalArgumentException::class.java) { generateLoginPassword(4) }
    }
    @Test fun browserRequiresVerifiedCertificateAndExactHttpsOrigin() {
        val identity = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        val entry = VaultEntry(type = EntryType.PASSWORD, title = "Bank", tertiaryValue = "https://example.com/login")
        assertTrue(loginAuthorizedForDestination(entry, "com.android.chrome", identity, "https://example.com"))
        assertFalse(loginAuthorizedForDestination(entry, "com.android.chrome", "fake-certificate", "https://example.com"))
        assertFalse(loginAuthorizedForDestination(entry, "com.fake.browser", identity, "https://example.com"))
        assertFalse(loginAuthorizedForDestination(entry, "com.android.chrome", identity, "https://example.com.attacker.test"))
        assertFalse(loginAuthorizedForDestination(entry, "com.android.chrome", identity, "https://login.example.com"))
        assertFalse(loginAuthorizedForDestination(entry, "com.android.chrome", identity, "http://example.com"))
        assertFalse(loginAuthorizedForDestination(entry.copy(type = EntryType.AUTHENTICATOR), "com.android.chrome", identity, "https://example.com"))
    }
    @Test fun linksExistingLoginWithoutReplacingItsPrimaryWebsite() {
        val identity = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        val entry = VaultEntry(type = EntryType.PASSWORD, title = "Bank", secondaryValue = "secret", tertiaryValue = "https://example.com/login")
        val browserLinked = linkLoginToDestination(entry, "com.android.chrome", identity, "https://accounts.example.net")
        val appLinked = linkLoginToDestination(browserLinked, "com.example.bank", "certificate", null)

        assertEquals(entry.tertiaryValue, appLinked.tertiaryValue)
        assertTrue(loginAuthorizedForDestination(appLinked, "com.android.chrome", identity, "https://accounts.example.net"))
        assertTrue(loginAuthorized(appLinked, "com.example.bank", "certificate"))
        assertFalse(loginAuthorizedForDestination(appLinked, "com.android.chrome", identity, "https://example.net"))
    }

    private val chrome = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    private fun site(website: String, origins: String = "", id: String = website) =
        VaultEntry(id = id, type = EntryType.PASSWORD, title = "Login", primaryValue = "me", secondaryValue = "secret",
            tertiaryValue = website, autofillOrigins = origins)
    private fun sameSite(entry: VaultEntry, origin: String?, packageName: String = "com.android.chrome", identity: String = chrome,
        embedded: Boolean = false) = sameSiteSavedOrigin(entry, packageName, identity, origin, embedded)

    @Test fun sameSiteUsesTheFullPublicSuffixList() {
        assertEquals("https://www.example.co.uk", sameSite(site("https://www.example.co.uk/login"), "https://login.example.co.uk"))
        assertEquals("https://example.com", sameSite(site("example.com"), "https://accounts.example.com"))
        assertEquals("https://example.com:8443", sameSite(site("https://example.com:8443"), "https://example.com"))
        assertEquals("https://id.example.com", sameSite(site("", origins = "https://id.example.com"), "https://www.example.com"))
        // Private section: separate customers of shared hosting are separate sites.
        assertNull(sameSite(site("https://a.github.io"), "https://b.github.io"))
        assertNull(sameSite(site("https://shop.vercel.app"), "https://phish.vercel.app"))
        assertNull(sameSite(site("https://www.example.co.uk"), "https://www.other.co.uk"))
        assertNull(sameSite(site("https://example.com"), "https://example.com.attacker.test"))
        assertNull(sameSite(site("https://example.com"), "https://example.co.uk"))
        assertNull(sameSite(site("https://192.168.1.2"), "https://192.168.1.3"))
    }

    @Test fun sameSiteExcludesHttpUntrustedAppsEmbeddedViewsAndExactLinks() {
        val entry = site("https://www.example.com")
        assertNull(sameSite(entry, "http://login.example.com"))
        assertNull(sameSite(entry, "https://login.example.com/path"))
        assertNull(sameSite(site("http://www.example.com"), "https://login.example.com"))
        assertNull(sameSite(entry, "https://login.example.com", identity = "fake-certificate"))
        assertNull(sameSite(entry, "https://login.example.com", packageName = "com.fake.browser"))
        assertNull(sameSite(entry, "https://login.example.com", embedded = true))
        assertNull(sameSite(entry, null, packageName = "com.example.app", identity = "certificate"))
        // Already linked to this exact origin: that is the exact tier, not same site.
        assertNull(sameSite(entry, "https://www.example.com"))
        assertNull(sameSite(site("https://www.example.com", origins = "https://login.example.com"), "https://login.example.com"))
        assertNull(sameSite(entry.copy(secondaryValue = ""), "https://login.example.com"))
        assertNull(sameSite(entry.copy(type = EntryType.AUTHENTICATOR), "https://login.example.com"))
    }

    @Test fun pickerFillReChecksEachGrant() {
        val entry = site("https://www.example.com", id = "picked")
        fun allowed(grant: PickerFillGrant, origin: String? = "https://login.example.com", confirmed: String? = null,
            packageName: String = "com.android.chrome", identity: String = chrome, embedded: Boolean = false, login: VaultEntry = entry) =
            pickerFillAllowed(login, packageName, identity, origin, embedded, grant, confirmed)
        // Exact authorization never widens to the same site.
        assertFalse(allowed(PickerFillGrant.LINKED))
        assertTrue(allowed(PickerFillGrant.LINKED, origin = "https://www.example.com"))
        // Same-site one-tap fill re-proves the same-site condition.
        assertTrue(allowed(PickerFillGrant.SAME_SITE_ONCE))
        assertFalse(allowed(PickerFillGrant.SAME_SITE_ONCE, origin = "https://other.example.net"))
        assertFalse(allowed(PickerFillGrant.SAME_SITE_ONCE, embedded = true))
        assertFalse(allowed(PickerFillGrant.SAME_SITE_ONCE, identity = "fake-certificate"))
        assertFalse(allowed(PickerFillGrant.SAME_SITE_ONCE, origin = null, packageName = "com.example.app", identity = "certificate"))
        assertFalse(allowed(PickerFillGrant.SAME_SITE_ONCE, origin = "https://b.github.io", login = site("https://a.github.io")))
        // "Fill once" needs the dialog to still show this exact entry, and a verified browser for a website.
        assertFalse(allowed(PickerFillGrant.CONFIRMED_ONCE, origin = "https://other.example.net"))
        assertFalse(allowed(PickerFillGrant.CONFIRMED_ONCE, origin = "https://other.example.net", confirmed = "another"))
        assertTrue(allowed(PickerFillGrant.CONFIRMED_ONCE, origin = "https://other.example.net", confirmed = "picked"))
        assertFalse(allowed(PickerFillGrant.CONFIRMED_ONCE, origin = "https://other.example.net", confirmed = "picked", identity = "fake"))
        assertFalse(allowed(PickerFillGrant.CONFIRMED_ONCE, origin = "http://other.example.net", confirmed = "picked"))
        assertTrue(allowed(PickerFillGrant.CONFIRMED_ONCE, origin = null, confirmed = "picked", packageName = "com.example.app", identity = "certificate"))
        assertFalse(allowed(PickerFillGrant.CONFIRMED_ONCE, origin = null, confirmed = "picked", packageName = "com.example.app",
            identity = "certificate", login = entry.copy(secondaryValue = "")))
    }

    @Test fun acceptsAConfirmedDialogSelectionWithoutActivityWindowFocus() {
        assertTrue(trustedFillFocus(hasWindowFocus = false, confirmedDialogAction = true, generatedPassword = false))
        assertTrue(trustedFillFocus(hasWindowFocus = false, confirmedDialogAction = false, generatedPassword = true))
        assertFalse(trustedFillFocus(hasWindowFocus = false, confirmedDialogAction = false, generatedPassword = false))
    }

    @Test fun authenticatedSuggestionsContainOnlyAuthorizedAccountsAndNeverLabelWithThePassword() {
        val identity = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        val authorized = VaultEntry(type = EntryType.PASSWORD, title = "Bank", primaryValue = "person@example.com",
            secondaryValue = "do-not-show-this", tertiaryValue = "https://bank.example/login", favorite = true)
        val unrelated = authorized.copy(id = "unrelated", title = "Other", tertiaryValue = "https://other.example")

        val suggestions = authorizedPasswordSuggestions(
            listOf(unrelated, authorized), "com.android.chrome", identity, "https://bank.example")
        val label = loginSuggestionLabel(suggestions.single())

        assertEquals("person@example.com", label.title)
        assertTrue(label.subtitle.contains("••••••••"))
        assertTrue(label.contentDescription.contains("Password hidden"))
        assertFalse(listOf(label.title, label.subtitle, label.contentDescription).any { it.contains("do-not-show-this") })
    }
}
