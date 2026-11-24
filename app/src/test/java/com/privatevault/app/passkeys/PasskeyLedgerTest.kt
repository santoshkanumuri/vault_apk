package com.privatevault.app.passkeys

import com.privatevault.app.data.PasskeyEventKind
import com.privatevault.app.data.PasskeyHistoryEvent
import com.privatevault.app.data.PasskeyOrigin
import com.privatevault.app.requestTime
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class PasskeyLedgerTest {
    private fun event(kind: PasskeyEventKind, at: Long, id: String = "pk", app: String = "", detail: String = "") =
        PasskeyHistoryEvent(passkeyId = id, rpId = "github.com", username = "me", kind = kind.name, at = at,
            appPackage = if (app.isBlank()) "" else "pkg.$app", appLabel = app, detail = detail)

    @Test fun everyKindReadsAsASentence() {
        assertEquals("Created in Chrome", PasskeyLedger.describe(event(PasskeyEventKind.CREATED, 1, app = "Chrome")))
        assertEquals("Created on this device", PasskeyLedger.describe(event(PasskeyEventKind.CREATED, 1)))
        assertEquals("Imported from Bitwarden", PasskeyLedger.describe(event(PasskeyEventKind.IMPORTED, 1, detail = "Bitwarden")))
        assertEquals("Synced from Pixel 9", PasskeyLedger.describe(event(PasskeyEventKind.SYNCED_IN, 1, detail = "Pixel 9")))
        assertEquals("Copied when this device joined sync",
            PasskeyLedger.describe(event(PasskeyEventKind.SYNCED_IN, 1, detail = PasskeyLedger.JOINED_SYNC)))
        assertEquals("Restored from a backup", PasskeyLedger.describe(event(PasskeyEventKind.RESTORED, 1)))
        assertEquals("Signed in to github.com with Chrome",
            PasskeyLedger.describe(event(PasskeyEventKind.USED, 1, app = "Chrome", detail = "https://github.com")))
        assertEquals("Signed in to GitHub", PasskeyLedger.describe(event(PasskeyEventKind.USED, 1, app = "GitHub")))
        assertEquals("Deleted on this device", PasskeyLedger.describe(event(PasskeyEventKind.DELETED, 1)))
        assertEquals("Deleted on Laptop", PasskeyLedger.describe(event(PasskeyEventKind.DELETED_ON_OTHER_DEVICE, 1, detail = "Laptop")))
        assertEquals("Saved before Nuvori kept passkey history", PasskeyLedger.describe(event(PasskeyEventKind.ADDED_BEFORE_HISTORY, 1)))
        // A kind from a newer version reads safely.
        assertEquals("Changed", PasskeyLedger.describe(event(PasskeyEventKind.CREATED, 1).copy(kind = "FUTURE_KIND")))
    }

    @Test fun addedFromPrefersTheCreatingAppThenTheOldestChange() {
        // Newest first, as the page reads them.
        val events = listOf(event(PasskeyEventKind.USED, 30, app = "Chrome"), event(PasskeyEventKind.SYNCED_IN, 20, detail = "Laptop"),
            event(PasskeyEventKind.IMPORTED, 10, detail = "Bitwarden"))
        assertEquals("Imported from Bitwarden", PasskeyLedger.addedFrom(null, events))
        assertEquals("Created in Brave", PasskeyLedger.addedFrom(PasskeyOrigin("pk", "com.brave.browser", "Brave", null), events))
        assertNull(PasskeyLedger.addedFrom(null, listOf(event(PasskeyEventKind.USED, 5))))
        assertEquals(20L, PasskeyLedger.lastChange(events)?.at)
        assertEquals(listOf(30L), PasskeyLedger.signIns(events).map { it.at })
        assertEquals(1, PasskeyLedger.eventsFor("other", events + event(PasskeyEventKind.CREATED, 1, id = "other")).size)
    }

    @Test fun fingerprintIsTheSha256OfTheStoredPublicKey() {
        val key = ByteArray(91) { it.toByte() }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val expected = MessageDigest.getInstance("SHA-256").digest(key).take(16)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }.chunked(4).joinToString(" ")
        assertEquals(expected, PasskeyLedger.publicKeyFingerprint(encoded))
        assertEquals(8, expected.split(" ").size)
        assertNull(PasskeyLedger.publicKeyFingerprint("not base64 !"))
    }

    @Test fun longIdsAreShortenedShortOnesKept() {
        assertEquals("abcdefgh…uvwxyz", PasskeyLedger.shortId("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("short-id", PasskeyLedger.shortId("short-id"))
    }

    @Test fun requestTimeSaysTodayOrTheDate() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 10, 20, 15, 0) }.timeInMillis
        assertTrue(requestTime(now - 60_000, now).startsWith("Today "))
        assertFalse(requestTime(now - 3L * 24 * 60 * 60 * 1000, now).startsWith("Today"))
    }
}
