package com.privatevault.app.autofill

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import com.privatevault.app.security.loginAuthorizedForDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeneratedLoginTest {
    @Test fun generatedPasswordRetainsAccountAndWebsiteWithoutReplacingWorkingLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            val original = VaultEntry(type = EntryType.PASSWORD, title = "Mail",
                primaryValue = "person@example.com", secondaryValue = "working-password",
                tertiaryValue = "https://example.com/login", linkedAuthenticatorId = "authenticator")
            val dao = database.dao()
            dao.saveEntry(VaultEntry(id = "authenticator", type = EntryType.AUTHENTICATOR,
                title = "Mail code", secondaryValue = "JBSWY3DPEHPK3PXP"), emptySet())
            dao.saveEntryExact(original, emptySet())
            dao.saveGeneratedLogin("https://example.com", original.primaryValue, "new-generated-password", original)
            val generated = dao.loginAndCodeEntries().single { it.type == EntryType.PASSWORD && it.id != original.id }
            assertEquals(original.primaryValue, generated.primaryValue)
            assertEquals("new-generated-password", generated.secondaryValue)
            assertEquals("https://example.com", generated.tertiaryValue)
            assertEquals(original.linkedAuthenticatorId, generated.linkedAuthenticatorId)
            val chromeIdentity = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
            assertTrue(loginAuthorizedForDestination(generated, "com.android.chrome", chromeIdentity, "https://example.com"))
            assertFalse(loginAuthorizedForDestination(generated, "com.android.chrome", chromeIdentity, "https://other.example.com"))
            assertFalse(loginAuthorizedForDestination(generated, "unverified.browser", chromeIdentity, "https://example.com"))
            assertEquals(original, dao.entry(original.id)?.entry)
            dao.saveGeneratedLogin("https://new.example.com", "new@example.com", "new-account-password", null)
            assertTrue(dao.loginAndCodeEntries().any { it.primaryValue == "new@example.com" &&
                it.secondaryValue == "new-account-password" && it.tertiaryValue == "https://new.example.com" })
            try {
                dao.saveGeneratedLogin("https://example.com", "", "orphan-password", null)
                fail("An account is required before saving a generated password")
            } catch (_: IllegalArgumentException) { }
            assertEquals(3, dao.loginAndCodeEntries().count { it.type == EntryType.PASSWORD })
        } finally { database.close() }
    }
}
