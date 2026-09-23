package com.privatevault.app.security

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatorImportDatabaseTest {
    @Test fun importSkipsExistingCodesAndRollsBackInvalidBatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val account = TotpSetup("Example", "alice", "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
        try {
            val first = database.dao().importAuthenticatorAccounts(listOf(account, account))
            assertEquals(1, first.added)
            assertEquals(1, first.alreadySaved)
            assertEquals(0, database.dao().importAuthenticatorAccounts(listOf(account)).added)
            // Use a valid key with different bytes for the same account.
            val changed = TotpSetup("Example", "alice", "KRUGS4ZANFZSAYJA", "SHA1", 6, 30)
            assertEquals(1, database.dao().importAuthenticatorAccounts(listOf(changed)).conflicts)
            val invalid = TotpSetup("Broken", "bob", "bad", "SHA1", 6, 30)
            assertTrue(runCatching { database.dao().importAuthenticatorAccounts(listOf(account.copyFor("carol"), invalid)) }.isFailure)
            val saved = database.dao().allEntries().filter { it.entry.type == EntryType.AUTHENTICATOR }
            assertEquals(listOf("alice"), saved.map { it.entry.primaryValue })
        } finally { database.close() }
    }

    private fun TotpSetup.copyFor(account: String) = TotpSetup(issuer, account, secret, algorithm, digits, period)
}
