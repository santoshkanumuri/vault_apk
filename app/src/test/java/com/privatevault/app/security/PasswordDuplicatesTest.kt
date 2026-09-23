package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordDuplicatesTest {
    @Test fun findsOnlyExactPasswordDuplicatesAndIgnoresIdsAndActivityDates() {
        val group = VaultGroup(id = "group-a", name = "Finance")
        val saved = VaultEntry(id = "saved", type = EntryType.PASSWORD, title = "Bank", primaryValue = "user",
            secondaryValue = "secret", tertiaryValue = "https://bank.example", notes = "Personal", tags = "money",
            autofillOrigins = "https://bank.example", favorite = true, createdAt = 10, updatedAt = 20,
            lastOpenedAt = 30, sortOrder = 40)
        val duplicate = saved.copy(id = "duplicate", createdAt = 50, updatedAt = 60, lastOpenedAt = 70, sortOrder = 80)
        val differentNativeLink = saved.copy(id = "native", autofillSignatures = "com.bank.app=certificate")
        val differentGroup = saved.copy(id = "other-group")
        val withPhoto = saved.copy(id = "photo")
        val entries = listOf(
            EntryWithDetails(saved, emptyList(), listOf(group)),
            EntryWithDetails(duplicate, emptyList(), listOf(group)),
            EntryWithDetails(differentNativeLink, emptyList(), listOf(group)),
            EntryWithDetails(differentGroup, emptyList(), emptyList()),
            EntryWithDetails(withPhoto, listOf(VaultPhoto(entryId = withPhoto.id, encryptedFileName = "photo.enc")), listOf(group))
        )

        val groups = exactPasswordDuplicateGroups(entries)

        assertEquals(1, groups.size)
        assertEquals("saved", groups.single().keeper.entry.id)
        assertEquals(listOf("duplicate"), groups.single().duplicates.map { it.entry.id })
    }

    @Test fun doesNotGroupEntriesWhenAnySavedFieldDiffers() {
        val saved = VaultEntry(id = "saved", type = EntryType.PASSWORD, title = "Bank", primaryValue = "user",
            secondaryValue = "secret", tertiaryValue = "https://bank.example", notes = "Personal")
        val variants = listOf(
            saved.copy(id = "title", title = "Bank app"),
            saved.copy(id = "password", secondaryValue = "other"),
            saved.copy(id = "notes", notes = "Work"),
            saved.copy(id = "tags", tags = "finance"),
            saved.copy(id = "origin", autofillOrigins = "https://login.bank.example")
        )

        assertTrue(exactPasswordDuplicateGroups(listOf(EntryWithDetails(saved, emptyList(), emptyList())) +
            variants.map { EntryWithDetails(it, emptyList(), emptyList()) }).isEmpty())
    }
}
