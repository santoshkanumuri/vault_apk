package com.privatevault.app.autofill

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.VaultCodesActivity
import org.junit.Assert.*
import org.junit.Test

class BrowserSaveCancellationTest {
    @Test fun closingOrBackgroundingReviewWipesSubmittedPassword() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (background in listOf(false, true)) {
            val secret = "dummy-unsaved-password".toCharArray()
            val request = LoginSaveRequest("com.android.chrome", "unverified", "https://example.com", "dummy", secret, SystemClock.elapsedRealtime() + 10_000)
            val token = PendingLoginSaves.put(request)
            ActivityScenario.launch<VaultCodesActivity>(Intent(context, VaultCodesActivity::class.java).putExtra("login_save_token", token)).use { scenario ->
                assertNull(PendingLoginSaves.take(token))
                if (background) scenario.onActivity { it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            assertTrue(secret.all { it == '\u0000' })
        }
    }
}
