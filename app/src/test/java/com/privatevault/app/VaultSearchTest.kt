package com.privatevault.app

import com.privatevault.app.data.*
import org.junit.Assert.*
import org.junit.Test

class VaultSearchTest {
    @Test fun searchesFolderAndAccountWithoutIndexingSecrets() {
        val folder = VaultGroup(name = "Travel", folderType = EntryType.PASSWORD)
        val login = EntryWithDetails(VaultEntry(type = EntryType.PASSWORD, title = "Mail", primaryValue = "reader@example.com",
            secondaryValue = "secret-password", tertiaryValue = "https://mail.example.com", notes = "Personal account"), emptyList(), listOf(folder))
        val searchable = searchableText(login)
        assertTrue(searchable.contains("Travel"))
        assertTrue(searchable.contains("reader@example.com"))
        assertTrue(searchable.contains("Personal account"))
        assertFalse(searchable.contains("secret-password"))
        val question = EntryWithDetails(VaultEntry(type = EntryType.QUESTION, title = "Bank",
            primaryValue = "First school?", secondaryValue = "secret-answer"), emptyList(), emptyList())
        assertTrue(searchableText(question).contains("First school?"))
        assertFalse(searchableText(question).contains("secret-answer"))
        val card = EntryWithDetails(VaultEntry(type = EntryType.CARD, title = "Trip card",
            primaryValue = "4111111111111234", fourthValue = "765"), emptyList(), emptyList())
        assertTrue(searchableText(card).contains("1234"))
        assertFalse(searchableText(card).contains("4111111111111234"))
        assertFalse(searchableText(card).contains("765"))
    }
}
