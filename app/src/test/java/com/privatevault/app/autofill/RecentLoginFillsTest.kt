package com.privatevault.app.autofill

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RecentLoginFillsTest {
    private fun fill(id: String, expiresAt: Long = 1_000L + CODE_AFTER_LOGIN_MS) =
        RecentLoginFill(id, "com.android.chrome", "certificate", "https://www.example.com", false, expiresAt)

    @After fun reset() { RecentLoginFills.clear(); RecentAutofillApps.clear() }

    @Test fun holdsOneLoginUntilItExpires() {
        RecentLoginFills.remember(fill("first"))
        assertEquals("first", RecentLoginFills.peek(1_000L)?.entryId)
        // Peeking does not use it up: the code screen may be opened, dismissed and opened again.
        assertEquals("first", RecentLoginFills.peek(1_000L + CODE_AFTER_LOGIN_MS - 1)?.entryId)
        assertNull(RecentLoginFills.peek(1_000L + CODE_AFTER_LOGIN_MS))
        // An expired record is gone for good, even if the clock were read earlier again.
        assertNull(RecentLoginFills.peek(1_000L))
    }

    @Test fun aNewerFillReplacesTheOlderOne() {
        RecentLoginFills.remember(fill("first"))
        RecentLoginFills.remember(fill("second"))
        assertEquals("second", RecentLoginFills.peek(1_000L)?.entryId)
    }

    @Test fun clearedOnUseButOnlyForThatLogin() {
        RecentLoginFills.remember(fill("first"))
        RecentLoginFills.consume("other")
        assertEquals("first", RecentLoginFills.peek(1_000L)?.entryId)
        RecentLoginFills.consume("first")
        assertNull(RecentLoginFills.peek(1_000L))
        RecentLoginFills.remember(fill("again"))
        RecentLoginFills.clear()
        assertNull(RecentLoginFills.peek(1_000L))
    }

    @Test fun theRecordHoldsNoSecretFields() {
        // Ids, destination and expiry only. A new field here needs a reason it is not a secret.
        assertEquals(setOf("entryId", "packageName", "identity", "origin", "embeddedWebView", "expiresAt"),
            RecentLoginFill::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }

    @Test fun recentAutofillAppLastsFiveMinutes() {
        val visit = AutofillAppVisit("com.example.bank", 10_000L)
        assertEquals("com.example.bank", recentAutofillApp(visit, 10_000L))
        assertEquals("com.example.bank", recentAutofillApp(visit, 10_000L + RECENT_AUTOFILL_APP_MS))
        assertNull(recentAutofillApp(visit, 10_000L + RECENT_AUTOFILL_APP_MS + 1))
        assertNull(recentAutofillApp(visit, 9_999L))
        assertNull(recentAutofillApp(null, 10_000L))
    }

    @Test fun recentAutofillAppSkipsBrowsersAndNuvori() {
        RecentAutofillApps.record("com.example.bank", browser = false, ownPackage = "com.privatevault.app", now = 1_000L)
        // A browser package says nothing about the site, and Nuvori never suggests itself.
        RecentAutofillApps.record("com.android.chrome", browser = true, ownPackage = "com.privatevault.app", now = 2_000L)
        RecentAutofillApps.record("com.privatevault.app", browser = false, ownPackage = "com.privatevault.app", now = 3_000L)
        assertEquals("com.example.bank", RecentAutofillApps.recent(4_000L))
        RecentAutofillApps.record("com.example.shop", browser = false, ownPackage = "com.privatevault.app", now = 5_000L)
        assertEquals("com.example.shop", RecentAutofillApps.recent(5_000L + RECENT_AUTOFILL_APP_MS))
        assertNull(RecentAutofillApps.recent(5_001L + RECENT_AUTOFILL_APP_MS))
    }
}
