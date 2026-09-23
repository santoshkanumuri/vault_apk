package com.privatevault.app.passkeys

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PasskeyImportDatabaseTest {
    private class VaultContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name)
    }

    @Test fun exactDuplicatesAreSkippedAndConflictsRollBack() = withDatabase { database ->
        val saved = createPasskey(ByteArray(32) { 1 }, "saved-user")
        database.dao().insertPasskeys(listOf(saved))
        val duplicate = saved.copy(createdAt = saved.createdAt + 1)
        assertEquals(1, database.dao().importPasskeys(listOf(duplicate)).alreadySaved)

        val addition = createPasskey(ByteArray(32) { 2 }, "new-user")
        val conflicting = saved.copy(username = "different-user")
        assertTrue(runCatching { database.dao().importPasskeys(listOf(addition, conflicting)) }.isFailure)
        assertEquals(listOf(saved), database.dao().allPasskeys())
    }

    private fun createPasskey(id: ByteArray, username: String): com.privatevault.app.data.VaultPasskey {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return com.privatevault.app.data.VaultPasskey(
            PasskeyCrypto.encode(id), "example.com", PasskeyCrypto.encode(ByteArray(32) { 4 }), username,
            username, PasskeyCrypto.encode(pair.private.encoded), PasskeyCrypto.encode(pair.public.encoded), 1_700_000_000_000
        ).also(PasskeyCrypto::validateStored)
    }

    private fun withDatabase(block: suspend (VaultDatabase) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "test-passkey-import-${UUID.randomUUID()}").apply { mkdirs() }
        val database = VaultDatabase.open(VaultContext(base, root), ByteArray(32) { 9 })
        try {
            block(database)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
