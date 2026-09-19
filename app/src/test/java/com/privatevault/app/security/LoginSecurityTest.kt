package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class LoginSecurityTest {
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
}
