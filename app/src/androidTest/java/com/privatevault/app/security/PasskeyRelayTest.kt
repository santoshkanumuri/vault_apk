package com.privatevault.app.security

import android.content.pm.PackageManager
import androidx.credentials.provider.CallingAppInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.passkeys.passkeyOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Passkey sign-in on another device: a computer shows a QR code, and Google Play services asks Nuvori on behalf of the
 * computer's website. Play services is signed with a rotated key, so this runs against the installed package.
 */
class PasskeyRelayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gms = "com.google.android.gms"

    private fun signingInfo() = runCatching {
        context.packageManager.getPackageInfo(gms, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
    }.getOrNull()

    @Test fun installedPlayServicesIsTrustedThroughItsSigningHistory() {
        assumeTrue("Play services is not installed", signingInfo() != null)
        val identity = requireNotNull(appSigningIdentity(context, gms))
        val history = requireNotNull(appSigningHistory(context, gms))
        assertTrue(identity in history)
        assertTrue(trustedPasskeyCaller(gms, identity, history))
        assertFalse(trustedBrowser(gms, identity))
    }

    @Test fun playServicesRelaysTheComputersWebsiteAsTheOrigin() {
        val signing = signingInfo()
        assumeTrue("Play services is not installed", signing != null)
        val caller = CallingAppInfo(gms, signing!!, "https://login.microsoft.com")
        assertEquals("https://login.microsoft.com", passkeyOrigin(context, caller, "login.microsoft.com"))
    }
}
