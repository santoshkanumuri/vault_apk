package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class CodeAppLinksTest {
    @Test fun matchesExactPackagesAndKeepsMultipleAccounts() {
        val first = VaultEntry(type = EntryType.AUTHENTICATOR, title = "Personal", linkedApps = "com.example.bank\ncom.example.other")
        val second = first.copy(id = "second", title = "Work", linkedApps = "com.example.bank")
        val unrelated = first.copy(id = "third", linkedApps = "com.example.bank.fake")
        assertEquals(listOf(first, second), matchingCodeEntries(listOf(first, second, unrelated), "com.example.bank"))
        assertTrue(matchingCodeEntries(listOf(first), null).isEmpty())
        assertTrue(matchingCodeEntries(listOf(first), "com.android.chrome").isEmpty())
    }

    @Test fun emptyAndDuplicateLinksAreHandled() {
        assertEquals(emptySet<String>(), linkedAppPackages(""))
        assertEquals(setOf("com.example.bank"), linkedAppPackages("com.example.bank\n\ncom.example.bank"))
    }
}
