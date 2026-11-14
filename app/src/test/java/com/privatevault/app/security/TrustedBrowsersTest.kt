package com.privatevault.app.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedBrowsersTest {
    private val chromeHash = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    private val braveHash = "9c2db70513515fdbfbbc585b3edf3d7123d4dc67c94ffd306361c1d79bbf18ac"
    private fun first(pkg: String) = trustedBrowserCertificates.getValue(pkg).first()

    @Test fun everyListedPackageAcceptsEachListedFingerprint() {
        assertTrue(trustedBrowserCertificates.isNotEmpty())
        trustedBrowserCertificates.forEach { (pkg, fingerprints) ->
            assertTrue(pkg, fingerprints.isNotEmpty())
            fingerprints.forEach { assertTrue("$pkg $it", trustedBrowser(pkg, it)) }
        }
    }

    @Test fun chromeAndBraveKeepTheirOriginalFingerprints() {
        assertEquals(setOf(chromeHash), trustedBrowserCertificates["com.android.chrome"])
        assertEquals(setOf(braveHash), trustedBrowserCertificates["com.brave.browser"])
    }

    @Test fun requiredBrowsersArePresent() {
        listOf(
            "com.android.chrome", "com.brave.browser", "com.microsoft.emmx", "org.mozilla.firefox",
            "com.sec.android.app.sbrowser", "com.vivaldi.browser", "com.opera.browser",
            "com.duckduckgo.mobile.android",
        ).forEach { assertTrue(it, it in trustedBrowserCertificates) }
    }

    @Test fun rejectsWrongEmptyAndOtherPackageFingerprints() {
        trustedBrowserCertificates.forEach { (pkg, fingerprints) ->
            val good = fingerprints.first()
            assertFalse(trustedBrowser(pkg, ""))
            assertFalse(trustedBrowser(pkg, "0".repeat(64)))
            assertFalse(trustedBrowser(pkg, good.dropLast(1) + if (good.last() == '0') '1' else '0'))
            assertFalse(trustedBrowser(pkg, " $good"))
        }
        assertFalse(trustedBrowser("com.android.chrome", braveHash))
        assertFalse(trustedBrowser("com.brave.browser", chromeHash))
    }

    @Test fun rejectsMultiSignerIdentitiesEvenWithACorrectHash() {
        trustedBrowserCertificates.forEach { (pkg, fingerprints) ->
            val good = fingerprints.first()
            assertFalse(trustedBrowser(pkg, "$good,${"1".repeat(64)}"))
            assertFalse(trustedBrowser(pkg, "${"1".repeat(64)},$good"))
            assertFalse(trustedBrowser(pkg, "$good,$good"))
        }
    }

    @Test fun rejectsUppercaseAndColonSeparatedForms() {
        trustedBrowserCertificates.forEach { (pkg, fingerprints) ->
            fingerprints.forEach {
                assertFalse(trustedBrowser(pkg, it.uppercase()))
                assertFalse(trustedBrowser(pkg, it.chunked(2).joinToString(":").uppercase()))
                assertFalse(trustedBrowser(pkg, it.chunked(2).joinToString(":")))
            }
        }
    }

    @Test fun rejectsPrereleasePackagesWithStableFingerprints() {
        listOf(
            "com.chrome.beta" to chromeHash,
            "com.chrome.dev" to chromeHash,
            "com.chrome.canary" to chromeHash,
            "com.brave.browser_beta" to braveHash,
            "com.brave.browser_nightly" to braveHash,
            "com.microsoft.emmx.beta" to first("com.microsoft.emmx"),
            "com.microsoft.emmx.canary" to first("com.microsoft.emmx"),
            "org.mozilla.firefox_beta" to first("org.mozilla.firefox"),
            "com.sec.android.app.sbrowser.beta" to first("com.sec.android.app.sbrowser"),
            "com.opera.browser.beta" to first("com.opera.browser"),
            "com.vivaldi.browser.snapshot" to first("com.vivaldi.browser"),
            "com.duckduckgo.mobile.android.debug" to first("com.duckduckgo.mobile.android"),
        ).forEach { (pkg, hash) -> assertFalse(pkg, trustedBrowser(pkg, hash)) }
    }

    @Test fun rejectsUnknownPackage() {
        assertFalse(trustedBrowser("com.example.browser", chromeHash))
        assertFalse(trustedBrowser("", chromeHash))
    }

    @Test fun everyTableEntryIsAReleaseEntryInTheSourceFile() {
        val file = listOf(File("../docs/trusted-browsers-source.json"), File("docs/trusted-browsers-source.json"))
            .first { it.isFile }
        val source = parseSource(file.readText())
        trustedBrowserCertificates.forEach { (pkg, fingerprints) ->
            val entries = source[pkg] ?: error("$pkg missing from source")
            val expected = entries.filter { it.first == "release" }
                .map { it.second.replace(":", "").lowercase() }.toSet()
            assertEquals(pkg, expected, fingerprints)
        }
    }

    /** Minimal parse of the known shape: a package_name followed by build and fingerprint pairs. */
    private fun parseSource(text: String): Map<String, List<Pair<String, String>>> {
        val result = linkedMapOf<String, MutableList<Pair<String, String>>>()
        val token = Regex("\"(package_name|build|cert_fingerprint_sha256)\"\\s*:\\s*\"([^\"]*)\"")
        var pkg: String? = null
        var build: String? = null
        token.findAll(text).forEach { m ->
            val value = m.groupValues[2]
            when (m.groupValues[1]) {
                "package_name" -> { pkg = value; result.getOrPut(value) { mutableListOf() } }
                "build" -> build = value
                else -> result.getValue(pkg!!).add(build!! to value)
            }
        }
        return result
    }
}
