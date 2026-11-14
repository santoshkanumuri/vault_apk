package com.privatevault.app.security

import org.junit.Assert.*
import org.junit.Test

class PublicSuffixListTest {
    private fun list(vararg rules: String) = PublicSuffixList.parse(rules.asSequence())

    @Test fun parsesNormalWildcardAndExceptionRules() {
        val psl = list("// comment", "", "com", "uk", "co.uk", "*.ck", "!www.ck", "jp", "*.kawasaki.jp", "!city.kawasaki.jp",
            "  github.io   trailing text", "b*d.rule")
        assertEquals("example.com", psl.registrableDomain("a.b.example.com"))
        assertEquals("example.co.uk", psl.registrableDomain("login.example.co.uk"))
        assertNull(psl.registrableDomain("co.uk"))
        // Wildcard: every label under ck is a public suffix ...
        assertNull(psl.registrableDomain("test.ck"))
        assertEquals("b.test.ck", psl.registrableDomain("a.b.test.ck"))
        // ... except the exception rule, which is registrable itself.
        assertEquals("www.ck", psl.registrableDomain("www.ck"))
        assertEquals("www.ck", psl.registrableDomain("www.www.ck"))
        assertEquals("city.kawasaki.jp", psl.registrableDomain("www.city.kawasaki.jp"))
        assertNull(psl.registrableDomain("other.kawasaki.jp"))
        assertEquals("a.github.io", psl.registrableDomain("x.a.github.io"))
        // Unlisted top-level domain: the implicit "*" rule.
        assertEquals("example.example", psl.registrableDomain("www.example.example"))
        assertNull(psl.registrableDomain("example"))
        assertNull(psl.registrableDomain("a..com"))
        // Rules with a wildcard anywhere but the left are skipped, never guessed: "rule" falls to the implicit "*".
        assertEquals("b*d.rule", psl.registrableDomain("x.b*d.rule"))
        assertEquals(9, psl.size)
    }

    @Test fun bundledListKeepsEveryRuleIncludingInternationalOnes() {
        val text = javaClass.getResourceAsStream("/nuvori/public_suffix_list.dat")!!.bufferedReader().readText()
        assertTrue(text.contains("===BEGIN ICANN DOMAINS===") && text.contains("===BEGIN PRIVATE DOMAINS==="))
        val rules = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.toSet()
        // A rule lost in IDN conversion would make sites under it look like one site.
        assertEquals(rules.size, PublicSuffixList.bundled.size)
    }

    /** Vectors from the list's own test file (tests/test_psl.txt), applied to the bundled list. */
    @Test fun bundledListPassesOfficialVectors() {
        mapOf(
            "example.COM" to "example.com", "WwW.example.COM" to "example.com", "b.example.uk.com" to "example.uk.com",
            "a.b.example.uk.com" to "example.uk.com", "test.ac" to "test.ac", "mm" to null, "c.mm" to null,
            "b.c.mm" to "b.c.mm", "a.b.c.mm" to "b.c.mm", "jp" to null, "test.jp" to "test.jp", "www.test.jp" to "test.jp",
            "ac.jp" to null, "test.ac.jp" to "test.ac.jp", "kyoto.jp" to null, "test.kyoto.jp" to "test.kyoto.jp",
            "ide.kyoto.jp" to null, "b.ide.kyoto.jp" to "b.ide.kyoto.jp", "a.b.ide.kyoto.jp" to "b.ide.kyoto.jp",
            "c.kobe.jp" to null, "b.c.kobe.jp" to "b.c.kobe.jp", "a.b.c.kobe.jp" to "b.c.kobe.jp",
            "city.kobe.jp" to "city.kobe.jp", "www.city.kobe.jp" to "city.kobe.jp", "ck" to null, "test.ck" to null,
            "b.test.ck" to "b.test.ck", "a.b.test.ck" to "b.test.ck", "www.ck" to "www.ck", "www.www.ck" to "www.ck",
            "us" to null, "test.us" to "test.us", "www.test.us" to "test.us", "ak.us" to null, "test.ak.us" to "test.ak.us",
            "www.test.ak.us" to "test.ak.us", "k12.ak.us" to null, "test.k12.ak.us" to "test.k12.ak.us",
            "www.test.k12.ak.us" to "test.k12.ak.us",
            "食狮.com.cn" to "xn--85x722f.com.cn", "食狮.公司.cn" to "xn--85x722f.xn--55qx5d.cn",
            "www.食狮.公司.cn" to "xn--85x722f.xn--55qx5d.cn", "shishi.公司.cn" to "shishi.xn--55qx5d.cn", "公司.cn" to null,
            "食狮.中国" to "xn--85x722f.xn--fiqs8s", "www.食狮.中国" to "xn--85x722f.xn--fiqs8s", "中国" to null,
            "xn--85x722f.com.cn" to "xn--85x722f.com.cn", "www.xn--85x722f.xn--55qx5d.cn" to "xn--85x722f.xn--55qx5d.cn",
            "xn--55qx5d.cn" to null, "shishi.xn--fiqs8s" to "shishi.xn--fiqs8s", "xn--fiqs8s" to null,
        ).forEach { (host, expected) -> assertEquals(host, expected, registrableDomainOfHost(host)) }
    }

    @Test fun registrableDomainOfOrigins() {
        assertEquals("example.co.uk", registrableDomain("https://login.example.co.uk"))
        assertEquals("example.co.uk", registrableDomain("https://www.example.co.uk:8443/path?q=1"))
        assertEquals("example.com", registrableDomain("https://LOGIN.Example.com"))
        // Private section: shared-hosting customers are separate sites.
        assertEquals("a.github.io", registrableDomain("https://a.github.io"))
        assertEquals("b.github.io", registrableDomain("https://x.b.github.io"))
        assertEquals("shop.vercel.app", registrableDomain("https://shop.vercel.app"))
        assertEquals("site.blogspot.com", registrableDomain("https://site.blogspot.com"))
        // IDN: punycode and Unicode give one answer.
        assertEquals("xn--85x722f.xn--55qx5d.cn", registrableDomain("https://www.xn--85x722f.xn--55qx5d.cn"))
        assertEquals("xn--85x722f.xn--55qx5d.cn", registrableDomain("https://WWW.XN--85X722F.xn--55qx5d.cn"))
        // java.net.URI has no host for a raw Unicode URL, so httpsOrigin and this fail closed. Browsers send punycode origins.
        assertNull(registrableDomain("https://www.食狮.公司.cn"))
    }

    @Test fun noRegistrableDomainForAddressesSuffixesAndNonHttps() {
        listOf(
            "https://127.0.0.1", "https://192.168.1.20:8080", "https://[::1]", "https://localhost", "https://intranet",
            "https://co.uk", "https://github.io", "https://com", "https://other.kawasaki.jp", "https://test.ck",
            "http://example.com", "ftp://example.com", "https://user:pw@example.com", "not a url", "",
        ).forEach { assertNull(it, registrableDomain(it)) }
        assertNull(registrableDomainOfHost("10.0.0.1"))
        assertNull(registrableDomainOfHost("a.b..com"))
    }
}
