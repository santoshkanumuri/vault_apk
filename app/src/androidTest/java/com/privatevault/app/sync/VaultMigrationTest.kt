package com.privatevault.app.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultMigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "sync-migration-test.db"
    private var migrated: VaultDatabase? = null

    @Before
    fun createVersionNineDatabase() {
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { database ->
            database.execSQL("CREATE TABLE entries (id TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, primaryValue TEXT NOT NULL, secondaryValue TEXT NOT NULL, tertiaryValue TEXT NOT NULL, fourthValue TEXT NOT NULL, cardKind TEXT NOT NULL, network TEXT NOT NULL, totpAlgorithm TEXT NOT NULL, totpDigits INTEGER NOT NULL, totpPeriod INTEGER NOT NULL, linkedApps TEXT NOT NULL, autofillSignatures TEXT NOT NULL, linkedAuthenticatorId TEXT NOT NULL, notes TEXT NOT NULL, color INTEGER NOT NULL, tags TEXT NOT NULL, favorite INTEGER NOT NULL, lastOpenedAt INTEGER NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            database.execSQL("CREATE TABLE vault_groups (id TEXT NOT NULL, name TEXT NOT NULL, notes TEXT NOT NULL, folderType TEXT, PRIMARY KEY(id))")
            database.execSQL("CREATE TABLE entry_group (entryId TEXT NOT NULL, groupId TEXT NOT NULL, PRIMARY KEY(entryId, groupId), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(groupId) REFERENCES vault_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX index_entry_group_entryId ON entry_group (entryId)")
            database.execSQL("CREATE INDEX index_entry_group_groupId ON entry_group (groupId)")
            database.execSQL("CREATE TABLE photos (id TEXT NOT NULL, entryId TEXT NOT NULL, encryptedFileName TEXT NOT NULL, encryptedThumbnailFileName TEXT NOT NULL, isCover INTEGER NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX index_photos_entryId ON photos (entryId)")
            database.execSQL("CREATE TABLE vault_settings (id INTEGER NOT NULL, lightMode INTEGER NOT NULL, nfcEnabled INTEGER NOT NULL, PRIMARY KEY(id))")
            database.execSQL("INSERT INTO vault_settings VALUES (1, 0, 0)")
            database.execSQL("CREATE TABLE passkeys (id TEXT NOT NULL, rpId TEXT NOT NULL, userHandle TEXT NOT NULL, username TEXT NOT NULL, displayName TEXT NOT NULL, privateKey TEXT NOT NULL, publicKey TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            database.execSQL("INSERT INTO entries VALUES ('entry-before-migration', 'NOTE', 'Preserved', '', '', '', '', 'CREDIT', '', 'SHA1', 6, 30, '', '', '', '', 0, '', 0, 0, 0, 1, 1)")
            database.version = 9
        }
    }

    @After
    fun cleanUp() {
        migrated?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migratesVersionNineToThirteenAndPreservesVaultData() = runBlocking {
        val database = Room.databaseBuilder(context, VaultDatabase::class.java, databaseName)
            .addMigrations(VaultDatabase.MIGRATION_9_10, VaultDatabase.MIGRATION_10_11, VaultDatabase.MIGRATION_11_12, VaultDatabase.MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()
        migrated = database

        assertEquals("Preserved", database.dao().entry("entry-before-migration")?.entry?.title)
        assertTrue(database.syncDao().operations().isEmpty())
        assertEquals(10_000L, database.dao().settings()?.backgroundTimeoutMs)
        assertEquals(60_000L, database.dao().settings()?.inactivityTimeoutMs)
        assertEquals(86_400_000L, database.dao().settings()?.masterPasswordIntervalMs)
    }
}
