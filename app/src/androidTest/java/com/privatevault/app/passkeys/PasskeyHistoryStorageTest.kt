package com.privatevault.app.passkeys

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.PasskeyEventKind
import com.privatevault.app.data.PasskeyOrigins
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.data.logPasskeyEvents
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The ledger stays within its limits, and the request history survives a restart sealed on disk. */
class PasskeyHistoryStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passkey = VaultPasskey("id", "github.com", "handle", "me", "Me", "private", "public", 1)

    @Test fun signInsAreCappedSoChangesAreNeverPushedOut() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        try {
            database.logPasskeyEvents(listOf(passkey), PasskeyEventKind.CREATED, at = 1)
            repeat(PasskeyOrigins.MAX_USE_EVENTS + 40) { database.logPasskeyEvents(listOf(passkey), PasskeyEventKind.USED, at = 2L + it) }
            val history = database.passkeyHistory()
            assertEquals(PasskeyOrigins.MAX_USE_EVENTS + 1, history.count())
            assertEquals("CREATED", history.recent(PasskeyOrigins.MAX_EVENTS).last().kind)
            // The newest sign-ins are the ones kept.
            assertEquals(PasskeyOrigins.MAX_USE_EVENTS + 41L, history.recent(1).single().at)
            history.clear()
            assertEquals(0, history.count())
        } finally { database.close() }
    }

    @Test fun requestHistorySurvivesARestartAndClears() {
        PasskeyRequestLog.clear(context)
        PasskeyRequestLog.awaitDisk()
        val record = PasskeyRequestRecord(1_790_000_000_000, false, true, "Chrome", "com.android.chrome", "github.com", true)
        repeat(PasskeyRequestLog.CAPACITY + 5) { PasskeyRequestLog.add(context, record.copy(at = record.at + it)) }
        PasskeyRequestLog.awaitDisk()
        PasskeyRequestLog.forgetMemory()
        assertTrue(PasskeyRequestLog.recent.value.isEmpty())
        PasskeyRequestLog.load(context)
        PasskeyRequestLog.awaitDisk()
        val loaded = PasskeyRequestLog.recent.value
        assertEquals(PasskeyRequestLog.CAPACITY, loaded.size)
        assertEquals(record.at + PasskeyRequestLog.CAPACITY + 4, loaded.first().at)
        // Sealed: the site is not readable in the file.
        assertFalse(String(File(context.noBackupFilesDir, "passkey-requests").readBytes(), Charsets.ISO_8859_1).contains("github.com"))
        PasskeyRequestLog.clear(context)
        PasskeyRequestLog.awaitDisk()
        assertFalse(File(context.noBackupFilesDir, "passkey-requests").exists())
    }
}
