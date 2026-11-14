package com.privatevault.app.autofill

import org.junit.Assert.*
import org.junit.Test

/** A login picked from the keyboard strip becomes a "just signed in" record only with a fresh stamp from this process. */
class KeyboardSignInTest {
    private val stamp = KeyboardSuggestionStamp("com.example.bank", "certificate", null, false, at = 10_000L)

    @Test fun aFreshStampForTheSameAppRemembersThePickedLogin() {
        val fill = keyboardSignIn("login-1", stamp, "com.example.bank", "certificate", now = 20_000L)!!
        assertEquals("login-1", fill.entryId)
        assertEquals("com.example.bank", fill.packageName); assertEquals("certificate", fill.identity)
        assertNull(fill.origin); assertFalse(fill.embeddedWebView)
        // The window is measured from now.
        assertEquals(20_000L + CODE_AFTER_LOGIN_MS, fill.expiresAt)
        // A website stamp keeps its own origin: the picker matches it to the code page later.
        val web = stamp.copy(packageName = "com.android.chrome", origin = "https://www.example.com")
        assertEquals("https://www.example.com", keyboardSignIn("login-1", web, "com.android.chrome", "certificate", 20_000L)!!.origin)
    }

    @Test fun anythingElseRemembersNothing() {
        fun signIn(id: String? = "login-1", stamp: KeyboardSuggestionStamp = this.stamp, packageName: String = "com.example.bank",
            identity: String = "certificate", now: Long = 20_000L) = keyboardSignIn(id, stamp, packageName, identity, now)
        assertNull(signIn(id = null))
        assertNull(signIn(id = ""))
        assertNull(signIn(id = PICKER_DATASET_ID))
        assertNull(signIn(packageName = "com.example.bank.fake"))
        assertNull(signIn(identity = "other-certificate"))
        assertNull(signIn(stamp = stamp.copy(identity = ""), identity = ""))
        // Written by an earlier process, or too long ago, or a clock that went backwards.
        assertNull(signIn(stamp = stamp.copy(process = "earlier-process")))
        assertNull(signIn(now = 10_000L + CODE_AFTER_LOGIN_MS + 1))
        assertNull(signIn(now = 9_999L))
        assertNotNull(signIn(now = 10_000L + CODE_AFTER_LOGIN_MS))
    }
}
