package com.privatevault.app.security

import org.junit.Assert.*
import org.junit.Test
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import java.io.ByteArrayInputStream

class BrowserPasswordsTest {
    @Test fun importsSanitizedProviderFixtures() {
        val fixtures = listOf(
            "chrome.csv", "brave.csv", "edge.csv", "firefox.csv", "safari.csv",
            "onepassword.csv", "lastpass.csv", "keepass.csv", "bitwarden.csv", "bitwarden.json"
        )
        fixtures.forEach { name ->
            val reader = requireNotNull(javaClass.getResourceAsStream("/password-import/$name"))
                .reader(Charsets.UTF_8)
            val entry = reader.use(::readBrowserPasswords).single()
            assertTrue(name, entry.tertiaryValue.startsWith("https://"))
            assertTrue(name, entry.primaryValue.isNotBlank())
            assertEquals(name, "synthetic-secret", entry.secondaryValue)
        }
    }

    @Test fun parsesBrowserCsvWithoutTrimmingSecretsAndSupportsMultilineNotes() {
        val csv = "\uFEFFname,url,username,password,note\r\nBank,https://example.com/login,user,\" a,b\"\"c \",\"first\nsecond\"\r\n"
        val entry = readBrowserPasswords(csv.reader()).single()
        assertEquals(" a,b\"c ", entry.secondaryValue)
        assertEquals("first\nsecond", entry.notes)
        assertEquals("https://example.com/login", entry.tertiaryValue)
        assertEquals("", entry.autofillSignatures)
        assertEquals("", entry.linkedAuthenticatorId)
    }

    @Test fun importsUtf16ChromeCsvWithSeparatorPreambleAndMissingOptionalTrailingCell() {
        val csv = "sep=;\r\nname;url;username;password;note\r\nBank;https://example.com;user;secret"
        val encoded = csv.toByteArray(Charsets.UTF_16LE)
        val input = ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + encoded)

        val entry = readBrowserPasswords(input).single()

        assertEquals("Bank", entry.title)
        assertEquals("https://example.com", entry.tertiaryValue)
        assertEquals("user", entry.primaryValue)
        assertEquals("secret", entry.secondaryValue)
        assertEquals("", entry.notes)
    }

    @Test fun importsTabSeparatedChromeColumns() {
        val csv = "name\turl\tusername\tpassword\tnote\nBank\thttps://example.com\tuser\tsecret\tmemo"
        assertEquals("secret", readBrowserPasswords(csv.reader()).single().secondaryValue)
    }

    @Test fun reportsPasswordlessExportsWithoutExposingRows() {
        val error = assertThrows(PasswordImportFormatException::class.java) {
            readBrowserPasswords("name,url,username,password,note\nBank,https://example.com,user,,private note".reader())
        }
        assertEquals("No non-empty passwords were found in the export.", error.message)
        assertFalse(error.message!!.contains("private note"))
    }

    @Test fun rejectsMalformedTextEncodingWithASafeMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            readBrowserPasswords(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
        assertTrue(error.message!!.startsWith("Could not read password export."))
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
    @Test fun normalizesHeaderSeparatorsWithoutGuessingRowContents() {
        val csv = "Account Name,Login-URL,User_Name,Pass Word,Extra Notes\n" +
            "Mail,https://mail.example.com,user@example.com,secret,personal"
        val entry = readBrowserPasswords(csv.reader()).single()
        assertEquals("Mail", entry.title)
        assertEquals("https://mail.example.com", entry.tertiaryValue)
        assertEquals("user@example.com", entry.primaryValue)
        assertEquals("secret", entry.secondaryValue)
        assertEquals("personal", entry.notes)
    }

    @Test fun rejectsHeadersThatCollideAfterNormalization() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            readBrowserPasswords("url,user_name,user-name,password\nhttps://example.com,first,second,secret".reader())
        }
        assertFalse(error.message!!.contains("secret"))
    }
    @Test fun requestsHeaderOnlyMappingForUnknownColumnsAndUsesExplicitSelection() {
        val csv = "label,site address,identity,credential,memo\nMail,https://mail.example.com,user@example.com,secret,personal"
        val required = assertThrows(PasswordColumnMappingRequired::class.java) {
            readBrowserPasswords(csv.reader())
        }
        assertEquals(listOf("label", "site address", "identity", "credential", "memo"), required.headers)
        assertEquals(listOf("••••", "https://••••", "••••@••••", "••••", "••••"),
            required.columns.map { it.sampleShape })
        assertFalse(required.message!!.contains("secret"))

        val entry = readBrowserPasswords(csv.reader(), PasswordColumnMapping(
            title = "label", website = "site address", username = "identity",
            password = "credential", notes = "memo"
        )).single()
        assertEquals("Mail", entry.title)
        assertEquals("https://mail.example.com", entry.tertiaryValue)
        assertEquals("user@example.com", entry.primaryValue)
        assertEquals("secret", entry.secondaryValue)
        assertEquals("personal", entry.notes)
    }

    @Test fun requestsMappingWhenAliasesCompeteForTheSameField() {
        val csv = "url,hostname,username,password\nhttps://example.com,example.com,user,secret"
        val required = assertThrows(PasswordColumnMappingRequired::class.java) {
            readBrowserPasswords(csv.reader())
        }
        assertNull(required.suggested.website)
        assertFalse(required.columns.any { it.sampleShape.contains("secret") })
    }

    @Test fun neverShowsRecognizedPasswordSamplesDuringMapping() {
        val required = assertThrows(PasswordColumnMappingRequired::class.java) {
            readBrowserPasswords("username,password\nuser,https://secret.example".reader())
        }
        assertEquals("Hidden", required.columns.single { it.header == "password" }.sampleShape)
    }

    @Test fun rejectsDuplicateOrMissingExplicitColumnSelectionsWithoutReadingSecrets() {
        val csv = "site,identity,credential\nhttps://example.com,user,secret"
        listOf(
            PasswordColumnMapping(website = "site", username = "identity", password = "identity"),
            PasswordColumnMapping(website = "site", username = "identity", password = "missing")
        ).forEach { mapping ->
            val error = assertThrows(PasswordColumnMappingRequired::class.java) {
                readBrowserPasswords(csv.reader(), mapping)
            }
            assertFalse(error.message!!.contains("secret"))
        }
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
