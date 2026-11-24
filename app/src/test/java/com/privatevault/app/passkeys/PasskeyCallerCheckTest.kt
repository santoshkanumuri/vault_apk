package com.privatevault.app.passkeys

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

/** Security review H-01: a passkey request is bound to the app or website that made it. */
class PasskeyCallerCheckTest {
    private val cert = "ab".repeat(32)
    private val otherCert = "cd".repeat(32)
    private val appOrigin = "android:apk-key-hash:" + "A".repeat(43)

    private fun assetLinks(pkg: String, print: String, relation: String = PasskeyCallerCheck.ASSET_LINKS_RELATION) = """
        [{"relation": ["delegate_permission/common.handle_all_urls", "$relation"],
          "target": {"namespace": "android_app", "package_name": "$pkg",
                     "sha256_cert_fingerprints": ["${print.chunked(2).joinToString(":").uppercase()}"]}}]
    """.trimIndent()

    @After fun clear() = PasskeyCallerCheck.clear()

    @Test fun rpIdMustBeTheHostOrARegistrableParent() {
        assertTrue(PasskeyCallerCheck.rpIdCoversHost("example.com", "example.com"))
        assertTrue(PasskeyCallerCheck.rpIdCoversHost("example.com", "login.example.com"))
        assertTrue(PasskeyCallerCheck.rpIdCoversHost("example.co.uk", "id.example.co.uk"))
        assertFalse(PasskeyCallerCheck.rpIdCoversHost("bank.com", "evil.com"))
        assertFalse(PasskeyCallerCheck.rpIdCoversHost("bank.com", "notbank.com"))
        assertFalse(PasskeyCallerCheck.rpIdCoversHost("login.example.com", "example.com"))
        // Never a public suffix, ICANN or private.
        assertFalse(PasskeyCallerCheck.rpIdCoversHost("co.uk", "example.co.uk"))
        assertFalse(PasskeyCallerCheck.rpIdCoversHost("github.io", "victim.github.io"))
        assertFalse(PasskeyCallerCheck.rpIdCoversHost("com", "example.com"))
    }

    @Test fun browserOriginsPassWithoutTheNetworkOnlyUnderTheRpId() {
        assertTrue(PasskeyCallerCheck.allowedNow("example.com", "https://login.example.com", "com.android.chrome"))
        assertTrue(PasskeyCallerCheck.allowedNow("example.com", "https://example.com:8443", "com.android.chrome"))
        assertFalse(PasskeyCallerCheck.allowedNow("bank.com", "https://evil.com", "com.android.chrome"))
        assertFalse(PasskeyCallerCheck.allowedNow("bank.com", "http://bank.com", "com.android.chrome"))
    }

    @Test fun appsNeverPassWithoutAVerifiedGrant() {
        assertFalse(PasskeyCallerCheck.allowedNow("bank.com", appOrigin, "com.evil"))
    }

    @Test fun assetLinksMustNameThePackageCertificateAndRelation() {
        val json = assetLinks("com.bank.app", cert)
        assertTrue(PasskeyCallerCheck.assetLinksGrant(json, "com.bank.app", listOf(cert)))
        assertTrue(PasskeyCallerCheck.assetLinksGrant(json, "com.bank.app", listOf(cert.uppercase())))
        assertFalse(PasskeyCallerCheck.assetLinksGrant(json, "com.evil", listOf(cert)))
        assertFalse(PasskeyCallerCheck.assetLinksGrant(json, "com.bank.app", listOf(otherCert)))
        assertFalse(PasskeyCallerCheck.assetLinksGrant(assetLinks("com.bank.app", cert, "delegate_permission/common.handle_all_urls"),
            "com.bank.app", listOf(cert)))
        assertFalse(PasskeyCallerCheck.assetLinksGrant("""[{"include": "https://evil.com/.well-known/assetlinks.json"}]""",
            "com.bank.app", listOf(cert)))
        assertFalse(PasskeyCallerCheck.assetLinksGrant("not json", "com.bank.app", listOf(cert)))
        assertFalse(PasskeyCallerCheck.assetLinksGrant("""[{"relation": ["${PasskeyCallerCheck.ASSET_LINKS_RELATION}"],
            "target": {"namespace": "web", "site": "https://bank.com"}}]""", "com.bank.app", listOf(cert)))
    }

    @Test fun verifyFetchesAssetLinksOnceAndRemembersOnlyGrants() {
        val fetched = mutableListOf<URL>()
        val fetch: (URL) -> String? = { url -> fetched += url; assetLinks("com.bank.app", cert) }
        assertFalse(PasskeyCallerCheck.verify("bank.com", appOrigin, "com.evil", cert, fetch))
        assertFalse(PasskeyCallerCheck.allowedNow("bank.com", appOrigin, "com.evil"))
        assertTrue(PasskeyCallerCheck.verify("bank.com", appOrigin, "com.bank.app", cert, fetch))
        assertEquals("https://bank.com/.well-known/assetlinks.json", fetched.last().toString())
        assertTrue(PasskeyCallerCheck.allowedNow("bank.com", appOrigin, "com.bank.app"))
        // The grant is for that RP ID only.
        assertFalse(PasskeyCallerCheck.allowedNow("other.com", appOrigin, "com.bank.app"))
        val before = fetched.size
        assertTrue(PasskeyCallerCheck.verify("bank.com", appOrigin, "com.bank.app", cert, fetch))
        assertEquals(before, fetched.size)
    }

    @Test fun verifyNeverFetchesForUnfitRpIds() {
        val fetch: (URL) -> String? = { fail("fetched $it"); null }
        assertFalse(PasskeyCallerCheck.verify("github.io", appOrigin, "com.app", cert, fetch))
        assertFalse(PasskeyCallerCheck.verify("192.168.1.1", appOrigin, "com.app", cert, fetch))
        assertFalse(PasskeyCallerCheck.verify("Bank.com", appOrigin, "com.app", cert, fetch))
        assertFalse(PasskeyCallerCheck.verify("bank.com/x", appOrigin, "com.app", cert, fetch))
    }

    @Test fun relatedOriginsAreListedExactly() {
        val json = """{"origins": ["https://example.co.uk", "https://shop.example.de"]}"""
        assertTrue(PasskeyCallerCheck.relatedOriginsGrant(json, "https://example.co.uk"))
        assertTrue(PasskeyCallerCheck.relatedOriginsGrant(json, "https://shop.example.de"))
        assertFalse(PasskeyCallerCheck.relatedOriginsGrant(json, "https://evil.com"))
        assertFalse(PasskeyCallerCheck.relatedOriginsGrant(json, "https://example.de"))
        assertFalse(PasskeyCallerCheck.relatedOriginsGrant("[]", "https://example.co.uk"))
    }

    @Test fun relatedOriginsCountOnlyTheFirstFiveSites() {
        val sites = (1..6).map { "https://site$it.com" }
        val json = """{"origins": [${sites.joinToString(",") { "\"$it\"" }}]}"""
        assertTrue(PasskeyCallerCheck.relatedOriginsGrant(json, "https://site5.com"))
        assertFalse(PasskeyCallerCheck.relatedOriginsGrant(json, "https://site6.com"))
    }

    @Test fun verifyUsesRelatedOriginsForOtherSites() {
        val fetch: (URL) -> String? = { url ->
            assertEquals("https://example.com/.well-known/webauthn", url.toString())
            """{"origins": ["https://example.co.uk"]}"""
        }
        assertTrue(PasskeyCallerCheck.verify("example.com", "https://example.co.uk", "com.android.chrome", cert, fetch))
        assertTrue(PasskeyCallerCheck.allowedNow("example.com", "https://example.co.uk", "com.android.chrome"))
        assertFalse(PasskeyCallerCheck.verify("example.com", "https://evil.com", "com.android.chrome", cert, fetch))
    }
}
