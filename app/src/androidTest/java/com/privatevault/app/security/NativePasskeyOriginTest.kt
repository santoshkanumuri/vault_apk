package com.privatevault.app.security

import android.content.pm.PackageManager
import androidx.credentials.provider.CallingAppInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.passkeys.passkeyOrigin
import org.junit.Assert.*
import org.junit.Test

class NativePasskeyOriginTest {
    @Test fun derivesNativeOriginFromInstalledSigningCertificate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val signing = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!
        val caller = CallingAppInfo(context.packageName, signing, null)
        val origin = passkeyOrigin(context, caller, "example.com")
        assertTrue(origin.matches(Regex("android:apk-key-hash:[A-Za-z0-9_-]{43}")))
        assertEquals(origin, passkeyOrigin(context, caller, "another.example"))
    }
}
