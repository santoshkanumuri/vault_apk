package com.privatevault.app.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.sync.SyncEntryCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Previous passwords and Recently deleted on a device: every entry write and delete goes through
 * the DAO, so local edits, sync, autofill and imports all keep them. Same cases as the Windows
 * tests in `vault.rs` and `incoming_apply.rs`.
 */
class RecentlyDeletedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
    private val dao = database.dao()

    @After fun close() = database.close()

    private fun login(password: String) = VaultEntry(id = "login-a", type = EntryType.PASSWORD, title = "Mail",
        primaryValue = "me@example.com", secondaryValue = password, tertiaryValue = "https://mail.example.org", notes = "keep me")

    @Test fun aChangedPasswordIsKeptNewestFirstUpToTen() = runBlocking {
        dao.insertEntry(login("first"))
        assertTrue("a new login has no history", dao.passwordHistory("login-a").isEmpty())
        dao.insertEntry(login("first").copy(title = "Renamed"))
        assertTrue("no new password, nothing kept", dao.passwordHistory("login-a").isEmpty())
        dao.updateEntry(login("second"))
        assertEquals(listOf("first"), dao.passwordHistory("login-a").map { it.password })
        (3..13).forEach { dao.insertEntry(login("password $it")) }
        val history = dao.passwordHistory("login-a").map { it.password }
        assertEquals(PasswordHistoryEntry.LIMIT, history.size)
        assertEquals("password 12", history.first())
        assertEquals("password 3", history.last())

        // Cards keep no password history.
        dao.insertEntry(VaultEntry(id = "card-a", type = EntryType.CARD, title = "Travel", secondaryValue = "A Person"))
        dao.insertEntry(VaultEntry(id = "card-a", type = EntryType.CARD, title = "Travel", secondaryValue = "B Person"))
        assertTrue(dao.passwordHistory("card-a").isEmpty())
        dao.clearPasswordHistory("login-a")
        assertTrue(dao.passwordHistory("login-a").isEmpty())
    }

    @Test fun aDeleteKeepsACopyWithItsFoldersAndMarksDeletesFromAnotherDevice() = runBlocking {
        dao.insertGroup(VaultGroup(id = "work", name = "Work", folderType = EntryType.PASSWORD))
        dao.insertEntry(login("old"))
        dao.link(EntryGroupCrossRef("login-a", "work"))
        dao.insertEntry(login("new"))
        dao.deleteEntryAndLinks(login("new"))
        dao.insertEntry(VaultEntry(id = "note-a", type = EntryType.NOTE, title = "Wi-Fi", notes = "router: synthetic"))
        dao.deleteEntryAndLinks(VaultEntry(id = "note-a", type = EntryType.NOTE, title = "Wi-Fi"), elsewhere = true)

        val trash = dao.trash().sortedBy { it.title }
        assertEquals(listOf("Mail", "Wi-Fi"), trash.map { it.title })
        assertEquals(listOf(false, true), trash.map { it.deletedElsewhere })
        val mail = trash[0]
        assertEquals("work", mail.groupIds)
        assertEquals(30L * 86_400_000L, mail.expiresAt - mail.deletedAt)
        // The copy is the full entry in the sync format, and reads back as it was.
        val copy = SyncEntryCodec.parseTrash(mail.entryJson)
        assertEquals(listOf("login-a", "new", "keep me"), listOf(copy.id, copy.secondaryValue, copy.notes))
        // Its previous passwords stay with the copy until a restore moves them.
        assertEquals(listOf("old"), dao.passwordHistory("login-a").map { it.password })
        dao.moveHistory("login-a", "login-b")
        assertEquals(listOf("old"), dao.passwordHistory("login-b").map { it.password })
        assertNull(dao.entryRow("login-a"))
    }

    @Test fun oldTrashIsPurgedAndForgottenItemsLoseTheirHistory() = runBlocking {
        dao.insertEntry(login("old"))
        dao.insertEntry(login("new"))
        dao.deleteEntryAndLinks(login("new"))
        val now = System.currentTimeMillis()
        dao.purgeTrash(now + 29L * 86_400_000L)
        assertEquals("29 days later it is still there", 1, dao.trash().size)
        assertEquals(1, dao.passwordHistory("login-a").size)
        dao.purgeTrash(now + 31L * 86_400_000L)
        assertTrue(dao.trash().isEmpty())
        assertTrue("the history went with it", dao.passwordHistory("login-a").isEmpty())

        dao.insertEntry(login("again"))
        dao.insertEntry(login("again 2"))
        dao.deleteEntryAndLinks(login("again 2"))
        dao.forgetTrashed(dao.trash().single().id)
        assertTrue(dao.trash().isEmpty())
        assertTrue(dao.passwordHistory("login-a").isEmpty())
        dao.forgetTrashed("missing")
    }

    @Test fun deleteForAnEntryThatIsNotHereKeepsNothing() = runBlocking {
        dao.deleteEntryAndLinks(login("never saved"))
        assertTrue(dao.trash().isEmpty())
    }
}
