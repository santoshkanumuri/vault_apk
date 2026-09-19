package com.privatevault.app.security

import org.junit.Assert.*
import org.junit.Test
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry

class BrowserPasswordsTest {
    @Test fun parsesBrowserCsvWithoutTrimmingSecretsAndSupportsMultilineNotes() {
        val csv = "\uFEFFname,url,username,password,note\r\nBank,https://example.com/login,user,\" a,b\"\"c \",\"first\nsecond\"\r\n"
        val entry = readBrowserPasswords(csv.reader()).single()
        assertEquals(" a,b\"c ", entry.secondaryValue)
        assertEquals("first\nsecond", entry.notes)
        assertEquals("https://example.com/login", entry.tertiaryValue)
        assertEquals("", entry.autofillSignatures)
        assertEquals("", entry.linkedAuthenticatorId)
    }
    @Test fun rejectsBrokenRowsMissingColumnsAndMalformedQuotesWithoutEchoingSecrets() {
        listOf("name,url,password\nBank,https://example.com,secret", "name,url,username,password\nBank,url,user,\"unclosed-secret", "name,url,username,password\nBank,url,user,secret,extra").forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { readBrowserPasswords(it.reader()) }
            assertFalse(error.message!!.contains("secret"))
        }
    }
    @Test fun originsAreExactAndRejectUnsafeAddresses() {
        assertEquals("https://example.com", httpsOrigin("https://EXAMPLE.com:443/login?a=b"))
        assertEquals("https://example.com:8443", httpsOrigin("https://example.com:8443"))
        assertNotEquals(httpsOrigin("https://example.com"), httpsOrigin("https://example.com.attacker.test"))
        assertNotEquals(httpsOrigin("https://example.com"), httpsOrigin("https://login.example.com"))
        listOf("http://example.com", "https://user@example.com", "https://example.com\\@evil.test", "https://example.com.", "https://example.com:99999", "https://example.com\n", "javascript:alert(1)").forEach { assertNull(httpsOrigin(it)) }
    }
    @Test fun importedAccountsUseOriginAndUsernameAndKeepTheLastCsvRow() {
        val first = VaultEntry(type = EntryType.PASSWORD, title = "First", primaryValue = "user",
            secondaryValue = "old", tertiaryValue = "https://EXAMPLE.com/login")
        val replacement = first.copy(id = "replacement", title = "Second", secondaryValue = "new",
            tertiaryValue = "https://example.com/account")
        assertTrue(sameImportedAccount(first, replacement))
        assertFalse(sameImportedLogin(first, replacement))
        assertEquals(listOf(replacement), deduplicateImportedLogins(listOf(first, replacement)))
    }
}
