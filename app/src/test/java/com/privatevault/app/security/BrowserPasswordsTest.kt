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
    @Test fun importsBitwardenCsvAndSkipsNonLoginItems() {
        val csv = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
            ",,login,Mail,personal,,,https://mail.example.com,user@example.com,secret,\n" +
            ",,note,Recovery,private note,,,,,,"
        val entry = readBrowserPasswords(csv.reader()).single()
        assertEquals("Mail", entry.title)
        assertEquals("https://mail.example.com", entry.tertiaryValue)
        assertEquals("user@example.com", entry.primaryValue)
        assertEquals("secret", entry.secondaryValue)
        assertEquals("personal", entry.notes)
    }
    @Test fun acceptsBitwardenLoginUrlAndSpacesAroundHeaders() {
        val csv = "folder, favorite,type,name,notes,fields, reprompt, login_url, login_username,login_password,login_totp\n" +
            ",,login,Mail,personal,,,https://mail.example.com,user@example.com,secret,"
        val entry = readBrowserPasswords(csv.reader()).single()
        assertEquals("https://mail.example.com", entry.tertiaryValue)
        assertEquals("user@example.com", entry.primaryValue)
    }
    @Test fun skipsPasswordlessBrowserRowsInsteadOfRejectingTheFile() {
        val csv = "name,url,username,password,note\n" +
            "Passkey only,https://passkey.example,user,,\n" +
            "Mail,https://mail.example,user,secret,"
        val entry = readBrowserPasswords(csv.reader()).single()
        assertEquals("Mail", entry.title)
        assertEquals("secret", entry.secondaryValue)
    }
    @Test fun importsFirefoxSafariAndOnePasswordStyleHeaders() {
        val firefox = readBrowserPasswords("url,username,password,httpRealm\nhttps://example.com,user,secret,".reader()).single()
        assertEquals("https://example.com", firefox.title)

        val onePassword = readBrowserPasswords("Title,Website,Username,Password,Notes\nExample,https://example.com,user,secret,memo".reader()).single()
        assertEquals("Example", onePassword.title)
        assertEquals("memo", onePassword.notes)
    }
    @Test fun importsLastPassAndKeePassStyleHeaders() {
        val lastPass = readBrowserPasswords("url,username,password,extra,name,grouping,fav\nhttps://example.com,user,secret,memo,Example,,0".reader()).single()
        assertEquals("Example", lastPass.title)
        assertEquals("memo", lastPass.notes)

        val keepass = readBrowserPasswords("Account,Login Name,Password,Web Site,Comments\nExample,user,secret,https://example.com,memo".reader()).single()
        assertEquals("user", keepass.primaryValue)
        assertEquals("https://example.com", keepass.tertiaryValue)
    }
    @Test fun importsUnencryptedBitwardenJsonLogins() {
        val json = """{
            "encrypted": false,
            "items": [
              {"type": 1, "name": "Mail", "notes": "personal", "login": {
                "username": "user@example.com", "password": " secret ",
                "uris": [{"uri": "https://mail.example.com/login"}]
              }},
              {"type": 2, "name": "Secure note", "secureNote": {"type": 0}}
            ]
        }"""
        val entry = readBrowserPasswords(json.reader()).single()
        assertEquals("Mail", entry.title)
        assertEquals("user@example.com", entry.primaryValue)
        assertEquals(" secret ", entry.secondaryValue)
        assertEquals("https://mail.example.com/login", entry.tertiaryValue)
        assertEquals("personal", entry.notes)
    }
    @Test fun rejectsEncryptedOrMalformedJsonWithoutEchoingSecrets() {
        listOf(
            """{"encrypted":true,"data":"secret-ciphertext"}""",
            """{"items":[{"type":1,"login":{"password":"secret"}}]}"""
        ).forEach {
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
