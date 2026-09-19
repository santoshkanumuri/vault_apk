package com.privatevault.app.autofill

import org.junit.Assert.*
import org.junit.Test

class UsernameFieldsTest {
    @Test fun recognizesSeedrUsernameWithoutAutocomplete() {
        assertTrue(isUsernameField(emptySet(), mapOf("name" to "username", "type" to "text"), false, true))
    }

    @Test fun recognizesEmailAndUsernameVariants() {
        listOf("email", "email_address", "login-email", "user_name", "USERID").forEach {
            assertTrue(isUsernameField(setOf("off"), mapOf("id" to it, "type" to "text"), false, true))
        }
        assertTrue(isUsernameField(setOf("email"), emptyMap(), false, true))
        assertTrue(isUsernameField(setOf("emailAddress"), emptyMap(), false, false))
        assertTrue(isUsernameField(emptySet(), emptyMap(), true, false))
    }

    @Test fun doesNotGuessUnrelatedOrNonTextFields() {
        listOf("search", "name", "firstname", "billing_email", "otp", "username_confirmation").forEach {
            assertFalse(isUsernameField(emptySet(), mapOf("name" to it), false, true))
        }
        listOf("hidden", "checkbox", "password", "tel", "search").forEach {
            assertFalse(isUsernameField(emptySet(), mapOf("name" to "username", "type" to it), false, true))
        }
        assertFalse(isUsernameField(setOf("one-time-code"), mapOf("name" to "username"), false, true))
        assertFalse(isUsernameField(setOf("name"), mapOf("name" to "username"), false, true))
        assertFalse(isUsernameField(emptySet(), mapOf("name" to "username"), false, false))
    }
}
