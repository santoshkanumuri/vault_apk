package com.privatevault.app.passkeys

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class CredentialProviderBoundaryTest {
    @Test
    fun providerIsSystemBoundAndAdvertisesPasswordsAndPasskeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, VaultCredentialService::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertEquals("android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE", service.permission)

        val capabilities = buildSet {
            context.resources.getXml(R.xml.credential_provider).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "capability") {
                        add(requireNotNull(parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")))
                    }
                    parser.next()
                }
            }
        }
        assertTrue("android.credentials.TYPE_PASSWORD_CREDENTIAL" in capabilities)
        assertTrue("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL" in capabilities)
    }
}
