package com.privatevault.app.autofill

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.VaultCodesActivity
import org.junit.Assert.*
import org.junit.Test

class AutofillBoundaryTest {
    @Test fun pendingPasswordsAreSingleUseAndWipedOnExpiryOrCancellation() {
        fun request(expiry: Long = SystemClock.elapsedRealtime() + 1000) = LoginSaveRequest("browser", "identity", "https://example.com", "user", "dummy-secret".toCharArray(), expiry)
        val cancelled = request()
        PendingLoginSaves.remove(PendingLoginSaves.put(cancelled))
        assertTrue(cancelled.password.all { it == '\u0000' })
        val expired = request(SystemClock.elapsedRealtime() - 1)
        assertNull(PendingLoginSaves.take(PendingLoginSaves.put(expired)))
        assertTrue(expired.password.all { it == '\u0000' })
        val active = request()
        val token = PendingLoginSaves.put(active)
        assertSame(active, PendingLoginSaves.take(token))
        assertNull(PendingLoginSaves.take(token))
        active.clear()
        val abandoned = request(SystemClock.elapsedRealtime() + 100)
        PendingLoginSaves.put(abandoned)
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (abandoned.password.any { it != '\u0000' } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(abandoned.password.all { it == '\u0000' })
    }
    @Test fun requestsAreSingleUseExpireAndCanBeCancelled() {
        val request = LoginFillRequest("com.example", "identity", null, null, null, SystemClock.elapsedRealtime() + 10_000)
        val token = PendingLoginFills.put(request)
        assertEquals(request, PendingLoginFills.take(token))
        assertNull(PendingLoginFills.take(token))
        val cancelled = PendingLoginFills.put(request)
        PendingLoginFills.remove(cancelled)
        assertNull(PendingLoginFills.take(cancelled))
        assertNull(PendingLoginFills.take(PendingLoginFills.put(request.copy(expiresAt = SystemClock.elapsedRealtime() - 1))))
        assertNull(PendingLoginFills.take("forged-token"))
    }

    @Test fun onlySystemCanBindServiceAndInvalidTokenCannotOpenPicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = context.packageManager.getServiceInfo(ComponentName(context, VaultAutofillService::class.java), 0)
        assertEquals("android.permission.BIND_AUTOFILL_SERVICE", service.permission)
        ActivityScenario.launch<VaultCodesActivity>(Intent(context, VaultCodesActivity::class.java).putExtra("login_fill_token", "forged-token")).use {
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, it.state)
        }
    }
}
