package com.privatevault.app.autofill

import android.text.InputType
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.junit.Assert.*
import org.junit.Test

class ProfileAutofillTest {
    @Test fun explicitContactAndAddressFieldsAreMatchedWithoutGuessingFromNumbers() {
        assertEquals(ProfileField.EMAIL, profileField(setOf("emailAddress"), emptyMap(), InputType.TYPE_CLASS_TEXT))
        assertEquals(ProfileField.PHONE, profileField(emptySet(), mapOf("autocomplete" to "tel"), InputType.TYPE_CLASS_PHONE))
        assertEquals(ProfileField.ADDRESS1, profileField(setOf("shipping address-line1"), emptyMap(), InputType.TYPE_CLASS_TEXT))
        assertEquals(ProfileField.ADDRESS2, profileField(emptySet(), mapOf("autocomplete" to "address-line2"), InputType.TYPE_CLASS_TEXT))
        assertEquals(ProfileField.UNIT, profileField(emptySet(), mapOf("name" to "flat_no"), InputType.TYPE_CLASS_NUMBER))
        assertEquals(ProfileField.CITY, profileField(emptySet(), mapOf("autocomplete" to "address-level2"), InputType.TYPE_CLASS_TEXT))
        assertEquals(ProfileField.STATE, profileField(emptySet(), mapOf("name" to "state"), InputType.TYPE_CLASS_TEXT))
        assertEquals(ProfileField.POSTAL_CODE, profileField(emptySet(), mapOf("id" to "pin_code"), InputType.TYPE_CLASS_NUMBER))
        assertNull(profileField(emptySet(), emptyMap(), InputType.TYPE_CLASS_NUMBER))
        assertNull(profileField(setOf("emailAddress"), mapOf("type" to "password"), InputType.TYPE_CLASS_TEXT))
    }

    @Test fun deviceCopyIsOptInAndContainsOnlyProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val preferences = context.getSharedPreferences("vault_preferences", android.content.Context.MODE_PRIVATE)
        val old = preferences.getBoolean("autofill_unlocked_profiles", false)
        val store = UnlockedProfileStore(context)
        try {
            preferences.edit().putBoolean("autofill_unlocked_profiles", true).commit()
            val profile = AutofillProfile(email = "test@example.invalid", phone = "5551230000", address1 = "1 Test Lane")
            val entries = listOf(VaultEntry(type = EntryType.AUTOFILL, title = "Test details", primaryValue = profile.encode()),
                VaultEntry(type = EntryType.PASSWORD, title = "Secret login", secondaryValue = "never-cache-this-password"))
            store.publish("test-vault", entries)
            assertEquals(listOf("Test details" to profile), store.read())
            val bytes = context.noBackupFilesDir.resolve("autofill-profiles").readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("test@example.invalid"))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("never-cache-this-password"))
            store.publish("test-vault", entries.drop(1))
            assertTrue(store.read().isEmpty())
            preferences.edit().putBoolean("autofill_unlocked_profiles", false).commit()
            assertTrue(store.read().isEmpty())
        } finally {
            store.clear()
            preferences.edit().putBoolean("autofill_unlocked_profiles", old).commit()
        }
    }
}
