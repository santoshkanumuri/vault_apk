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

    @Test fun playServicesIsTrustedForPasskeysOnly() {
        val gms = "com.google.android.gms"
        val fingerprints = trustedPasskeyRelayCertificates.getValue(gms)
        assertEquals(4, fingerprints.size)
        fingerprints.forEach {
            assertTrue(trustedPasskeyCaller(gms, it))
            // Password and form autofill keep the browser list: Play services is not a browser.
            assertFalse(trustedBrowser(gms, it))
        }
        trustedBrowserCertificates.forEach { (pkg, browserFingerprints) ->
            browserFingerprints.forEach { assertTrue("$pkg $it", trustedPasskeyCaller(pkg, it)) }
        }
    }

    @Test fun playServicesRejectsWrongFingerprintsAndOtherPackages() {
        val gms = "com.google.android.gms"
        val good = trustedPasskeyRelayCertificates.getValue(gms).first()
        assertFalse(trustedPasskeyCaller(gms, ""))
        assertFalse(trustedPasskeyCaller(gms, braveHash))
        assertFalse(trustedPasskeyCaller(gms, good.uppercase()))
        assertFalse(trustedPasskeyCaller(gms, "$good,${"1".repeat(64)}"))
        assertFalse(trustedPasskeyCaller(gms, good.dropLast(1) + if (good.last() == '0') '1' else '0'))
        listOf("com.google.android.gms.beta", "com.google.android.gsf", "com.example.gms", "")
            .forEach { assertFalse(it, trustedPasskeyCaller(it, good)) }
    }

    // Play services 25.26 signing history (apksigner lineage): original key, 2020 rotation, 2024 rotation (current).
    private val gmsOriginal = "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00"
    private val gmsRotation2020 = "d22cc500299fb22873a01a010de1c82fbe4d061119b94814dd301dab50cb7678"
    private val gmsRotation2024 = "8fd4a0e7b8eb7c726d2dac79445b2c7931f62b7eb692f5044bdcfdfaad070a36"

    @Test fun playServicesMatchesThroughItsRotatedKeyHistory() {
        val gms = "com.google.android.gms"
        // The current key is not in Google's list; an earlier key in its proven history is.
        assertFalse(gmsRotation2024 in trustedPasskeyRelayCertificates.getValue(gms))
        assertFalse(trustedPasskeyCaller(gms, gmsRotation2024))
        assertTrue(trustedPasskeyCaller(gms, gmsRotation2024, listOf(gmsOriginal, gmsRotation2020, gmsRotation2024)))
        assertTrue(trustedPasskeyCaller(gms, gmsRotation2024, listOf(gmsRotation2020, gmsRotation2024)))
        // Password and form autofill still never trust Play services.
        assertFalse(trustedBrowser(gms, gmsRotation2024))
    }

    @Test fun historyMatchingIsOnlyForTheRelayAndNeedsTheCurrentKeyInTheHistory() {
        val gms = "com.google.android.gms"
        // A history without a listed key, or one that doesn't contain the current key, is rejected.
        assertFalse(trustedPasskeyCaller(gms, gmsRotation2024, listOf(gmsRotation2024)))
        assertFalse(trustedPasskeyCaller(gms, gmsRotation2024, listOf(gmsOriginal, gmsRotation2020)))
        assertFalse(trustedPasskeyCaller(gms, gmsRotation2024, emptyList()))
        // Another package with Play services' history is not a relay.
        assertFalse(trustedPasskeyCaller("com.example.fake", gmsRotation2024, listOf(gmsOriginal, gmsRotation2024)))
        // A browser must match by its current key: a listed key earlier in its history doesn't count.
        val other = "1".repeat(64)
        assertFalse(trustedPasskeyCaller("com.android.chrome", other, listOf(chromeHash, other)))
        assertTrue(trustedPasskeyCaller("com.android.chrome", chromeHash, listOf(chromeHash)))
    }

    @Test fun everyRelayEntryIsAReleaseEntryInTheSourceFile() {
        val file = listOf(File("../docs/trusted-browsers-source.json"), File("docs/trusted-browsers-source.json"))
            .first { it.isFile }
        val source = parseSource(file.readText())
        trustedPasskeyRelayCertificates.forEach { (pkg, fingerprints) ->
            val expected = source.getValue(pkg).filter { it.first == "release" }
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
