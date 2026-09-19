package com.privatevault.app.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LoginMigrationTest {
    @Test fun versionSixDataSurvivesAddingLoginLinks() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "test-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val key = ByteArray(32) { 19 }
        val login = VaultEntry(type = EntryType.PASSWORD, title = "Preserve me", primaryValue = "account", secondaryValue = "dummy password")
        try {
            var db = VaultDatabase.open(context, key)
            try {
                db.dao().saveEntry(login, emptySet())
                // Recreate the actual v6 layout and version without touching a user's vault.
                db.openHelper.writableDatabase.execSQL("ALTER TABLE entries DROP COLUMN autofillSignatures")
                db.openHelper.writableDatabase.execSQL("ALTER TABLE entries DROP COLUMN linkedAuthenticatorId")
                db.openHelper.writableDatabase.execSQL("DROP TABLE passkeys")
                db.openHelper.writableDatabase.version = 6
            } finally { db.close() }
            db = VaultDatabase.open(context, key)
            try {
                val restored = requireNotNull(db.dao().entry(login.id)).entry
                assertEquals(login.primaryValue, restored.primaryValue)
                assertEquals(login.secondaryValue, restored.secondaryValue)
                assertEquals("", restored.autofillSignatures)
                assertEquals("", restored.linkedAuthenticatorId)
                assertEquals(8, db.openHelper.readableDatabase.version)
                assertTrue(db.dao().allPasskeys().isEmpty())
                assertTrue(runCatching { db.dao().saveEntry(restored.copy(linkedAuthenticatorId = "missing"), emptySet()) }.isFailure)
                assertEquals("", db.dao().entry(login.id)!!.entry.linkedAuthenticatorId)
            } finally { db.close() }
        } finally { key.fill(0); root.deleteRecursively() }
    }
}
