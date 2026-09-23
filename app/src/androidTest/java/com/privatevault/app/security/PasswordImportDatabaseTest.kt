package com.privatevault.app.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.LoginImportAction
import com.privatevault.app.data.LoginImportMatch
import com.privatevault.app.data.LoginImportRequest
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PasswordImportDatabaseTest {
    private class VaultContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name)
    }

    @Test fun explicitDecisionsOnlyReplaceThePasswordField() = withDatabase { database ->
        val folder = VaultGroup(name = "Work", folderType = EntryType.PASSWORD)
        database.dao().insertGroup(folder)
        val saved = VaultEntry(type = EntryType.PASSWORD, title = "Saved title", primaryValue = "user@example.com",
            secondaryValue = "Saved password", tertiaryValue = "https://example.com/login", notes = "Saved notes",
            linkedApps = "com.example.app", autofillSignatures = "signature", linkedAuthenticatorId = "")
        database.dao().saveEntry(saved, setOf(folder.id))
        val before = database.dao().entry(saved.id)!!
        val match = listOf(LoginImportMatch.from(before.entry))
        val incoming = saved.copy(id = UUID.randomUUID().toString(), title = "Imported title",
            secondaryValue = "Imported password", notes = "Imported notes")

        val kept = database.dao().importLogins(listOf(LoginImportRequest(incoming, LoginImportAction.KEEP_SAVED, match, saved.id)))
        assertEquals(1, kept.skippedConflicts)
        assertEquals(before, database.dao().entry(saved.id))

        val replaced = database.dao().importLogins(listOf(LoginImportRequest(incoming, LoginImportAction.USE_IMPORTED, match, saved.id)))
        assertEquals(1, replaced.updated)
        val after = database.dao().entry(saved.id)!!
        assertEquals("Imported password", after.entry.secondaryValue)
        assertEquals(before.entry.title, after.entry.title)
        assertEquals(before.entry.notes, after.entry.notes)
        assertEquals(before.entry.linkedApps, after.entry.linkedApps)
        assertEquals(before.entry.autofillSignatures, after.entry.autofillSignatures)
        assertEquals(before.entry.autofillOrigins, after.entry.autofillOrigins)
        assertEquals(before.groups, after.groups)

        val exact = incoming.copy(id = UUID.randomUUID().toString())
        val exactMatch = listOf(LoginImportMatch.from(after.entry))
        assertEquals(1, database.dao().importLogins(listOf(
            LoginImportRequest(exact, LoginImportAction.SKIP_EXACT, exactMatch, saved.id))).skippedExact)

        val newLogin = incoming.copy(id = UUID.randomUUID().toString(), title = "New", primaryValue = "new-user",
            tertiaryValue = "https://new.example/login")
        assertEquals(1, database.dao().importLogins(listOf(
            LoginImportRequest(newLogin, LoginImportAction.ADD, emptyList()))).added)
    }

    @Test fun stalePreviewRollsBackEveryWrite() = withDatabase { database ->
        val saved = VaultEntry(type = EntryType.PASSWORD, title = "Saved", primaryValue = "user",
            secondaryValue = "Saved password", tertiaryValue = "https://example.com/login")
        database.dao().insertEntry(saved)
        val previewed = database.dao().entry(saved.id)!!.entry
        val staleMatch = listOf(LoginImportMatch.from(previewed))
        database.dao().updateEntry(previewed.copy(secondaryValue = "Changed locally"))
        val before = database.dao().backupSnapshot()
        val newLogin = saved.copy(id = UUID.randomUUID().toString(), primaryValue = "new-user",
            tertiaryValue = "https://new.example/login")
        val staleConflict = saved.copy(id = UUID.randomUUID().toString(), secondaryValue = "Imported password")

        val result = runCatching { database.dao().importLogins(listOf(
            LoginImportRequest(newLogin, LoginImportAction.ADD, emptyList()),
            LoginImportRequest(staleConflict, LoginImportAction.USE_IMPORTED, staleMatch, saved.id)
        )) }

        assertTrue(result.isFailure)
        assertEquals(before, database.dao().backupSnapshot())
    }

    @Test fun ambiguousMatchesCannotBeOverwritten() = withDatabase { database ->
        val first = VaultEntry(type = EntryType.PASSWORD, title = "First", primaryValue = "user",
            secondaryValue = "First password", tertiaryValue = "https://example.com/login")
        val second = first.copy(id = UUID.randomUUID().toString(), title = "Second", secondaryValue = "Second password")
        database.dao().insertEntry(first)
        database.dao().insertEntry(second)
        val matches = database.dao().loginAndCodeEntries().map(LoginImportMatch::from)
        val incoming = first.copy(id = UUID.randomUUID().toString(), secondaryValue = "Imported password")

        assertEquals(1, database.dao().importLogins(listOf(
            LoginImportRequest(incoming, LoginImportAction.SKIP_AMBIGUOUS, matches))).skippedConflicts)
        val beforeUnsafeAttempt = database.dao().backupSnapshot()
        assertTrue(runCatching { database.dao().importLogins(listOf(
            LoginImportRequest(incoming, LoginImportAction.USE_IMPORTED, matches))) }.isFailure)
        assertEquals(beforeUnsafeAttempt, database.dao().backupSnapshot())
    }

    @Test fun exactDuplicateCleanupKeepsTheOldestCopy() = withDatabase { database ->
        val folder = VaultGroup(id = "finance", name = "Finance", folderType = EntryType.PASSWORD)
        database.dao().insertGroup(folder)
        val keeper = VaultEntry(id = "keeper", type = EntryType.PASSWORD, title = "Bank", primaryValue = "user",
            secondaryValue = "secret", tertiaryValue = "https://bank.example", notes = "Personal",
            createdAt = 10, updatedAt = 20, sortOrder = 30)
        val duplicate = keeper.copy(id = "duplicate", createdAt = 40, updatedAt = 50, sortOrder = 60)
        database.dao().saveEntryExact(keeper, setOf(folder.id))
        database.dao().saveEntryExact(duplicate, setOf(folder.id))

        assertEquals(1, database.dao().deleteExactPasswordDuplicates(setOf(duplicate.id)))

        assertNotNull(database.dao().entry(keeper.id))
        assertNull(database.dao().entry(duplicate.id))
    }

    @Test fun exactDuplicateCleanupRejectsAStaleReview() = withDatabase { database ->
        val keeper = VaultEntry(id = "keeper", type = EntryType.PASSWORD, title = "Bank", primaryValue = "user",
            secondaryValue = "secret", tertiaryValue = "https://bank.example", createdAt = 10)
        val duplicate = keeper.copy(id = "duplicate", createdAt = 20)
        database.dao().insertEntry(keeper)
        database.dao().insertEntry(duplicate)
        database.dao().updateEntry(duplicate.copy(notes = "Changed after review"))

        assertTrue(runCatching { database.dao().deleteExactPasswordDuplicates(setOf(duplicate.id)) }.isFailure)
        assertNotNull(database.dao().entry(keeper.id))
        assertNotNull(database.dao().entry(duplicate.id))
    }

    private fun withDatabase(block: suspend (VaultDatabase) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "test-password-import-${UUID.randomUUID()}").apply { mkdirs() }
        val database = VaultDatabase.open(VaultContext(base, root), ByteArray(32) { 7 })
        try {
            block(database)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
