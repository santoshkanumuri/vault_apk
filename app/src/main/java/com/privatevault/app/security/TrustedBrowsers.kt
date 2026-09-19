package com.privatevault.app.security

/** Release certificates from Google's privileged-app list, checked 2026-09-18.
 * https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json
 * Deliberately excludes debug builds and unverified browsers. No runtime network request.
 */
internal fun trustedBrowser(packageName: String, identity: String): Boolean = when (packageName) {
    "com.android.chrome" -> identity == "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    "com.brave.browser" -> identity == "9c2db70513515fdbfbbc585b3edf3d7123d4dc67c94ffd306361c1d79bbf18ac"
    else -> false
}
