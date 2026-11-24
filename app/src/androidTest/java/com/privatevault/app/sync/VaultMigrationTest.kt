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
            database.execSQL("INSERT INTO passkeys VALUES ('credential-1', 'github.com', 'handle', 'me', 'Me', 'private', 'public', 1234)")
            database.version = 9
        }
    }

    @After
    fun cleanUp() {
        migrated?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migratesVersionNineToLatestAndPreservesVaultData() = runBlocking {
        val database = Room.databaseBuilder(context, VaultDatabase::class.java, databaseName)
            .addMigrations(VaultDatabase.MIGRATION_9_10, VaultDatabase.MIGRATION_10_11, VaultDatabase.MIGRATION_11_12,
                VaultDatabase.MIGRATION_12_13, VaultDatabase.MIGRATION_13_14, VaultDatabase.MIGRATION_14_15,
                VaultDatabase.MIGRATION_15_16, VaultDatabase.MIGRATION_16_17,
                VaultDatabase.MIGRATION_17_18, VaultDatabase.MIGRATION_18_19, VaultDatabase.MIGRATION_19_20,
                VaultDatabase.MIGRATION_20_21, VaultDatabase.MIGRATION_21_22, VaultDatabase.MIGRATION_22_23,
                VaultDatabase.MIGRATION_23_24, VaultDatabase.MIGRATION_24_25, VaultDatabase.MIGRATION_25_26,
                VaultDatabase.MIGRATION_26_27)
            .allowMainThreadQueries()
            .build()
        migrated = database

        assertEquals("Preserved", database.dao().entry("entry-before-migration")?.entry?.title)
        assertTrue(database.syncDao().operations().isEmpty())
        assertTrue(database.syncDao().archivedOperations().isEmpty())
        assertTrue(database.syncDao().membershipEvents("vault-a").isEmpty())
        assertEquals(10_000L, database.dao().settings()?.backgroundTimeoutMs)
        assertEquals(60_000L, database.dao().settings()?.inactivityTimeoutMs)
        assertEquals(86_400_000L, database.dao().settings()?.masterPasswordIntervalMs)
        assertEquals(null, database.syncDao().membership("vault-a", "device-a"))

        // Version 26: no keys of later epochs and no key packages yet; one of each saves and reads back.
        assertTrue(database.syncDao().epochKeys().isEmpty())
        database.syncDao().saveEpochKey(com.privatevault.app.data.SyncEpochKeyEntity("vault-a", 2, "k", "s"))
        database.syncDao().saveKeyPackages(com.privatevault.app.data.SyncKeyPackageEntity("vault-a", 2, "{}"))
        assertEquals(2L, database.syncDao().epochKeys().single().epoch)
        assertEquals("{}", database.syncDao().keyPackages("vault-a").single().setJson)

        // Version 25: no lost managing device and no lineage yet; one of each saves and reads back.
        assertEquals(null, database.syncDao().lostManager("vault-a"))
        assertTrue(database.syncDao().lineage().isEmpty())
        database.syncDao().saveLostManager(com.privatevault.app.data.SyncLostManagerEntity("vault-a", "phone", 3, 7, "ab", 1))
        assertEquals(7L, database.syncDao().lostManager("vault-a")?.headSequence)
        database.syncDao().insertLineage(listOf(com.privatevault.app.data.SyncLineageEntity("old", "entry", "e1", "{}", true)))
        // Version 27: a lineage row keeps the content digest of the item when this device left.
        assertEquals("", database.syncDao().lineage().single().contentDigest)
        database.syncDao().insertLineage(listOf(com.privatevault.app.data.SyncLineageEntity("old", "entry", "e2", "{}", false, "ab12")))
        assertEquals("ab12", database.syncDao().lineage().single { it.entityId == "e2" }.contentDigest)
        assertEquals(true, database.syncDao().lineage().single { it.entityId == "e1" }.deleted)
        database.syncDao().clearLostManager()
        assertEquals(null, database.syncDao().lostManager("vault-a"))

        // Version 24: an empty store for sign-in methods; one saves, reads back by key and id, and hides when pending delete.
        val signIns = database.signInMethods()
        assertEquals(0, signIns.count())
        val octo = com.privatevault.app.data.SignInMethod("example.com", "github", "octocat", "0e541a44e256d5b075321c762a2bcb86",
            "OctoCat", 1, 2, 3, "2026-11-20T10:00:00Z", "pc")
        signIns.save(octo)
        assertEquals(octo, signIns.byKey("example.com", "github", "octocat"))
        assertEquals(octo, signIns.byEntity("0e541a44e256d5b075321c762a2bcb86"))
        signIns.save(octo.copy(pendingDelete = true))
        assertEquals(0, signIns.count())
        assertEquals(1, signIns.pendingDeletes(10).size)

        // Version 23: no enrollment checkpoint or compaction floor yet; one saves and reads back.
        assertEquals(null, database.syncDao().checkpoint("vault-a"))
        database.syncDao().saveCheckpoint(com.privatevault.app.data.SyncCheckpointEntity("vault-a", "", "{}", 5))
        assertEquals("{}", database.syncDao().checkpoint("vault-a")?.floorJson)

        // Version 22: an empty store for website icons other devices share.
        assertEquals(0, database.syncedSiteIcons().count())

        // Version 21: a passkey saved before the ledger starts with one line at its own creation time.
        val history = database.passkeyHistory()
        val line = history.recent(10).single()
        assertEquals(listOf("credential-1", "github.com", "me", "ADDED_BEFORE_HISTORY", "1234"),
            listOf(line.passkeyId, line.rpId, line.username, line.kind, line.at.toString()))
        assertTrue(history.origins().isEmpty())
        assertEquals("Me", history.passkeyInfos().single().displayName)
    }
}
