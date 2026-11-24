package com.privatevault.app.security

import com.privatevault.app.QuickCategory
import com.privatevault.app.autofill.KeyboardSuggestionStamp
import com.privatevault.app.autofill.PICKER_DATASET_ID
import com.privatevault.app.autofill.keyboardPickKey
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.quickCopyMatches
import org.junit.Assert.*
import org.junit.Test

class ItemUsageTest {
    private val day = 24L * 60 * 60 * 1000
    private val start = 1_790_000_000_000L

    private fun login(title: String, site: String = "", favorite: Boolean = false, user: String = "") =
        VaultEntry(id = title, type = EntryType.PASSWORD, title = title, primaryValue = user, secondaryValue = "secret",
            tertiaryValue = site, favorite = favorite)

    /** [counts]: id to uses, all at [at]. */
    private fun usage(vararg counts: Pair<String, Int>, at: Long = start, now: Long = start): UsageSnapshot {
        var uses = emptyMap<String, ItemUse>()
        counts.forEach { (id, times) -> repeat(times) { uses = ItemUsage.record(uses, id, at) } }
        return UsageSnapshot(uses, now)
    }

    @Test fun recordCountsUsesAndOlderUsesWeighLess() {
        var uses = ItemUsage.record(emptyMap(), "a", start)
        uses = ItemUsage.record(uses, "a", start)
        assertEquals(2, uses.getValue("a").count)
        assertEquals(2.0, UsageSnapshot(uses, start).score("a"), 1e-9)
        // One half-life later the same two uses weigh one.
        assertEquals(1.0, UsageSnapshot(uses, start + ItemUsage.HALF_LIFE_MS).score("a"), 1e-9)
        assertEquals(0.0, UsageSnapshot(uses, start).score("never"), 0.0)
        assertSame(uses, ItemUsage.record(uses, " ", start))
    }

    @Test fun anAccountUsedOftenLongAgoFallsBehindOneUsedThisWeek() {
        var uses = emptyMap<String, ItemUse>()
        repeat(10) { uses = ItemUsage.record(uses, "old", start) }
        repeat(3) { uses = ItemUsage.record(uses, "new", start + 120 * day) }
        val now = UsageSnapshot(uses, start + 121 * day)
        assertTrue(now.score("new") > now.score("old"))
        assertEquals("new", now.mostUsed(listOf("old", "new")))
    }

    @Test fun theBadgeNeedsTwoItemsTwoUsesAndAClearLead() {
        assertNull("a lone item says nothing", usage("a" to 5).mostUsed(listOf("a")))
        assertNull("one tap never earns it", usage("a" to 1).mostUsed(listOf("a", "b")))
        assertNull("a tie has no winner", usage("a" to 3, "b" to 3).mostUsed(listOf("a", "b")))
        assertNull("nothing used", usage().mostUsed(listOf("a", "b")))
        assertEquals("b", usage("a" to 2, "b" to 3).mostUsed(listOf("a", "b", "c")))
        assertEquals("a", usage("a" to 2).mostUsed(listOf("a", "a", "b")))
    }

    @Test fun sortedByUseKeepsTheOrderOfUnusedItems() {
        val ranked = listOf("d", "c", "b", "a").sortedByUse(usage("a" to 3, "b" to 1)) { it }
        assertEquals(listOf("a", "b", "d", "c"), ranked)
    }

    @Test fun theRecordKeepsOnlyTheMostUsedIds() {
        var uses = emptyMap<String, ItemUse>()
        repeat(ItemUsage.MAX_ITEMS) { uses = ItemUsage.record(uses, "id-$it", start) }
        uses = ItemUsage.record(uses, "id-0", start)
        uses = ItemUsage.record(uses, "newest", start + day)
        assertEquals(ItemUsage.MAX_ITEMS, uses.size)
        assertTrue("id-0" in uses && "newest" in uses)
    }

    @Test fun oneBadgePerSiteAcrossSubdomainsAndNames() {
        val entries = listOf(
            login("Gmail", "https://mail.google.com"), login("Google work", "accounts.google.com"),
            login("Bank", "https://bank.example"), login("Bank old", "https://bank.example/login"),
            login("Router"), login("router"), login("Solo", "https://solo.example"),
        )
        val uses = usage("Google work" to 4, "Gmail" to 2, "Bank old" to 2, "Router" to 2, "Solo" to 9)
        // Google: one site for both subdomains. Bank: the only used one wins. Router: grouped by name. Solo: alone.
        assertEquals(setOf("Google work", "Bank old", "Router"), mostUsedPerSite(entries, uses))
        assertEquals("google.com", loginSiteKey(entries[1]))
        assertEquals(loginSiteKey(entries[4]), loginSiteKey(entries[5]))
    }

    @Test fun suggestionsPutFavoritesFirstThenTheMostUsed() {
        val identity = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        val entries = listOf(
            login("Alpha", "https://bank.example", user = "a"), login("Bravo", "https://bank.example", user = "b"),
            login("Charlie", "https://bank.example", user = "c"), login("Delta", "https://bank.example", favorite = true),
        )
        val ranked = authorizedPasswordSuggestions(entries, "com.android.chrome", identity, "https://bank.example",
            usage("Charlie" to 3, "Bravo" to 1)).map { it.title }
        assertEquals(listOf("Delta", "Charlie", "Bravo", "Alpha"), ranked)
        // Without usage the old order stays: favorites, then name.
        assertEquals(listOf("Delta", "Alpha", "Bravo", "Charlie"),
            authorizedPasswordSuggestions(entries, "com.android.chrome", identity, "https://bank.example").map { it.title })
    }

    @Test fun searchAndQuickCopyRankTheMostUsedFirst() {
        val entries = listOf(login("Alpha", "https://x.test"), login("Bravo", "https://x.test"), login("Echo", "https://x.test", favorite = true))
        val uses = usage("Bravo" to 2)
        assertEquals(listOf("Echo", "Bravo", "Alpha"), rankLoginSearch(entries, "x.test", uses) { LoginMatchTier.OTHER }.map { it.title })
        assertEquals(listOf("Bravo", "Alpha", "Echo"), quickCopyMatches(entries, QuickCategory.PASSWORDS, "", uses).map { it.title })
        assertEquals(listOf("Bravo", "Alpha", "Echo"), quickCopyMatches(entries, QuickCategory.PASSWORDS, "x.test", uses).map { it.title })
    }

    @Test fun aKeyboardPickHasOneKeyAndThePickerEntryHasNone() {
        val stamp = KeyboardSuggestionStamp("com.android.chrome", "identity", "https://bank.example", false, 42L, "process")
        assertEquals("process:42:login-1", keyboardPickKey("login-1", stamp))
        assertEquals(keyboardPickKey("login-1", stamp), keyboardPickKey("login-1", stamp))
        assertNotEquals(keyboardPickKey("login-1", stamp), keyboardPickKey("login-1", stamp.copy(at = 43L)))
        assertNull(keyboardPickKey(PICKER_DATASET_ID, stamp))
        assertNull(keyboardPickKey(null, stamp))
        assertNull(keyboardPickKey(" ", stamp))
    }
}
