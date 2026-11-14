package com.privatevault.app.security

/**
 * Release signing certificates of mainstream stable browsers, from Google's privileged-app list
 * https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json (checked 2026-11-13).
 * A copy of the checked file is in docs/trusted-browsers-source.json.
 *
 * Included: stable or release packages of mainstream browsers, with every fingerprint whose build is "release".
 * Excluded: beta, dev, alpha, canary, nightly, snapshot, debug and corp packages, every "userdebug" fingerprint,
 * and niche or non-browser entries. Values are lowercase hex SHA-256 without colons, as `appSigningIdentity` returns.
 * Generated from the JSON by script, never typed by hand. No runtime network request.
 */
internal val trustedBrowserCertificates: Map<String, Set<String>> = mapOf(
    "com.android.chrome" to setOf(
        "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
    ),
    "com.brave.browser" to setOf(
        "9c2db70513515fdbfbbc585b3edf3d7123d4dc67c94ffd306361c1d79bbf18ac",
    ),
    "com.microsoft.emmx" to setOf(
        "01e1999710a82c2749b4d50c445dc85d670b6136089d0a766a73827c82a1eac9",
    ),
    "org.mozilla.firefox" to setOf(
        "a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04",
    ),
    "org.mozilla.focus" to setOf(
        "6203a473be36d64ee37f87fa500edbc79eab930610ab9b9fa4ca7d5c1f1b4ffc",
    ),
    "org.mozilla.klar" to setOf(
        "6203a473be36d64ee37f87fa500edbc79eab930610ab9b9fa4ca7d5c1f1b4ffc",
    ),
    "com.sec.android.app.sbrowser" to setOf(
        "34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42",
    ),
    "com.vivaldi.browser" to setOf(
        "e8a78544655ba8c09817f732768f5689b1662ec4b2bc5a0bc0ec138d33ca3d1e",
    ),
    "com.opera.browser" to setOf(
        "5d6afbf87f652af04647ada0df634cf22370900b164b09d50bd23aa2cb5285b8",
    ),
    "com.opera.mini.native" to setOf(
        "57acbc525f1b2ebd19196cd6f014397cc910fd18841e0ae850febc3e1e593ff2",
    ),
    "com.duckduckgo.mobile.android" to setOf(
        "bb7bb31c573c46a1da7fc5c528a6acf432108456feec50810c7f33694eb3d2d4",
    ),
    "com.naver.whale" to setOf(
        "0b8b8523bb4aeffa346e4bdd4fbf7d193450569aa14aaad4adfd94a3f7b227bb",
    ),
    "com.yandex.browser" to setOf(
        "aca405ded8b25cb2e8c6da69425d2b4307d087c1276fc06ad5942731ccc51dba",
    ),
)

/**
 * True only when [identity] equals one listed release fingerprint of [packageName].
 * A multi-signer identity (hashes joined by ",") never equals a single fingerprint, so it is rejected on purpose.
 */
internal fun trustedBrowser(packageName: String, identity: String): Boolean =
    trustedBrowserCertificates[packageName]?.contains(identity) == true
