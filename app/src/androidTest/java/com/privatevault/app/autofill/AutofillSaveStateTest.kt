package com.privatevault.app.autofill

import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AutofillSaveStateTest {
    @Test fun usernameStepCanSaveWithPasswordOnlyFromSameBrowserAndWebsite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val username = View(context).autofillId
        val password = View(context).autofillId
        val identity = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        val first = LoginFillRequest("com.android.chrome", identity, username, null, null, 999999,
            "https://accounts.example.com")
        assertNotNull(first.delayedUsernameSave())
        val state = requireNotNull(first.saveClientState())
        assertEquals(username, state.previousUsernameFor("com.android.chrome", identity, first.origin))
        assertNull(state.previousUsernameFor("com.android.chrome", identity, "https://other.example.com"))
        assertNull(state.previousUsernameFor("com.brave.browser", identity, first.origin))
        assertNull(state.previousUsernameFor("com.android.chrome", "different-certificate", first.origin))

        val second = LoginFillRequest("com.android.chrome", identity, null, password, null, 999999,
            first.origin, previousUsername = state.previousUsernameFor("com.android.chrome", identity, first.origin))
        assertNotNull(second.saveInfo())
        assertEquals(username, second.saveClientState()?.previousUsernameFor("com.android.chrome", identity, first.origin))
    }
}
