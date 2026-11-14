package com.privatevault.app.security

import com.privatevault.app.autofill.RecentLoginFill
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class CodeAfterLoginTest {
    private val chrome = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    private val now = 50_000L
    private fun website(origin: String = "https://www.example.com", id: String = "login") =
        RecentLoginFill(id, "com.android.chrome", chrome, origin, false, now + 1)
    private fun app(embedded: Boolean = false) =
        RecentLoginFill("login", "com.example.bank", "certificate", null, embedded, now + 1)
    private fun matches(remembered: RecentLoginFill?, origin: String? = "https://login.example.com",
        packageName: String = "com.android.chrome", identity: String = chrome, embedded: Boolean = false, at: Long = now) =
        codeAfterLoginMatches(remembered, packageName, identity, origin, embedded, at)

    @Test fun websiteNeedsTheSameRegistrableDomainInAVerifiedBrowser() {
        assertTrue(matches(website()))
        assertTrue(matches(website(), origin = "https://www.example.com"))
        assertTrue(matches(website("https://www.example.co.uk"), origin = "https://id.example.co.uk"))
        assertFalse(matches(website(), origin = "https://example.net"))
        assertFalse(matches(website(), origin = "https://example.com.attacker.test"))
        // Private section of the Public Suffix List: separate customers of shared hosting are separate sites.
        assertFalse(matches(website("https://a.github.io"), origin = "https://b.github.io"))
        assertFalse(matches(website(), origin = "http://login.example.com"))
        assertFalse(matches(website(), origin = "https://login.example.com/path"))
        assertFalse(matches(website(), identity = "fake-certificate"))
        assertFalse(matches(website().copy(packageName = "com.fake.browser"), packageName = "com.fake.browser"))
        assertFalse(matches(website(), packageName = "com.brave.browser"))
        assertFalse(matches(website("https://192.168.1.2"), origin = "https://192.168.1.2"))
    }

    @Test fun appsAndEmbeddedWebViewsMatchOnPackageAndIdentityOnly() {
        assertTrue(matches(app(), origin = null, packageName = "com.example.bank", identity = "certificate"))
        assertFalse(matches(app(), origin = null, packageName = "com.example.bank", identity = "other-certificate"))
        assertFalse(matches(app(), origin = null, packageName = "com.example.bank.fake", identity = "certificate"))
        assertFalse(matches(app().copy(identity = ""), origin = null, packageName = "com.example.bank", identity = ""))
        // An embedded WebView is trusted as its app: the page origin it reports plays no part.
        assertTrue(matches(app(embedded = true), origin = null, packageName = "com.example.bank", identity = "certificate", embedded = true))
        assertTrue(matches(app(), origin = "https://anything.test", packageName = "com.example.bank", identity = "certificate", embedded = true))
    }

    @Test fun websiteAndAppNeverMatchEachOther() {
        assertFalse(matches(website(), origin = null))
        assertFalse(matches(website(), embedded = true))
        assertFalse(matches(app().copy(packageName = "com.android.chrome", identity = chrome)))
    }

    @Test fun expiredOrMissingIsRefused() {
        assertFalse(matches(null))
        assertFalse(matches(website(), at = now + 1))
        assertFalse(matches(website().copy(expiresAt = now)))
    }

    @Test fun codeAfterLoginGrantFillsOnlyACodeForTheRememberedLogin() {
        val entry = VaultEntry(id = "login", type = EntryType.PASSWORD, title = "Login", primaryValue = "me",
            secondaryValue = "secret", tertiaryValue = "https://www.example.com", linkedAuthenticatorId = "code")
        fun allowed(otp: Boolean = true, remembered: RecentLoginFill? = website(), login: VaultEntry = entry,
            codeExists: Boolean = true, origin: String = "https://login.example.com", at: Long = now) =
            pickerFillAllowed(login, "com.android.chrome", chrome, origin, false, PickerFillGrant.CODE_AFTER_LOGIN,
                otpRequest = otp, rememberedLogin = remembered, now = at, linkedCodeExists = codeExists)
        assertTrue(allowed())
        // Never a username or password request.
        assertFalse(allowed(otp = false))
        assertFalse(allowed(remembered = null))
        assertFalse(allowed(remembered = website(id = "another")))
        assertFalse(allowed(at = now + 1))
        assertFalse(allowed(origin = "https://example.net"))
        assertFalse(allowed(codeExists = false))
        assertFalse(allowed(login = entry.copy(linkedAuthenticatorId = "")))
        assertFalse(allowed(login = entry.copy(type = EntryType.AUTHENTICATOR)))
        // The other grants ignore the remembered login: it never widens a password fill.
        assertFalse(pickerFillAllowed(entry, "com.android.chrome", chrome, "https://other.example.net", false, PickerFillGrant.LINKED,
            otpRequest = true, rememberedLogin = website(), now = now, linkedCodeExists = true))
    }
}
