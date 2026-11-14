package com.privatevault.app.autofill

import org.junit.Assert.*
import org.junit.Test

class LockedProfileFillTest {
    private fun worth(vararg kinds: ProfileField, loginForm: Boolean = false) = lockedProfileOfferWorthwhile(kinds.toList(), loginForm)

    /** The service's decision on plain nodes: a login form wins, then the profile rule. */
    private fun offered(vararg roots: FormNode, browser: Boolean = true): Boolean {
        val login = classifyLoginForm(roots.toList(), browser)
        val loginForm = login?.password != null || login?.otp != null || !login?.newPasswords.isNullOrEmpty()
        val kinds = classifyProfileForm(roots.toList(), browser)?.values ?: return false
        return lockedProfileOfferWorthwhile(kinds, loginForm)
    }

    // ---- the "worth offering" rule --------------------------------------------------------------------------------

    @Test fun twoOrMoreDifferentKindsAreOffered() {
        assertTrue(worth(ProfileField.EMAIL, ProfileField.PHONE))
        assertTrue(worth(ProfileField.NAME, ProfileField.EMAIL))
        assertTrue(worth(ProfileField.ADDRESS1, ProfileField.CITY, ProfileField.POSTAL_CODE))
    }

    @Test fun aLoneEmailOrPhoneIsNotOffered() {
        assertFalse(worth(ProfileField.EMAIL))
        assertFalse(worth(ProfileField.PHONE))
        // "Email" and "Confirm email" are still one kind, as in the Windows extension.
        assertFalse(worth(ProfileField.EMAIL, ProfileField.EMAIL))
        assertFalse(worth())
    }

    @Test fun aLoneExplicitAddressOrNameFieldIsOffered() {
        assertTrue(worth(ProfileField.POSTAL_CODE))
        assertTrue(worth(ProfileField.ADDRESS1))
        assertTrue(worth(ProfileField.GIVEN_NAME))
    }

    @Test fun loginFormsAreNeverOffered() {
        assertFalse(worth(ProfileField.NAME, ProfileField.EMAIL, ProfileField.PHONE, loginForm = true))
        // A sign-up form with a password is a login form: the login path handles it.
        assertFalse(offered(webPage(htmlForm(
            htmlInput(1, name = "name", autocomplete = "name"),
            htmlInput(2, type = "email", name = "email", autocomplete = "email"),
            htmlInput(3, type = "password", name = "password", autocomplete = "new-password")))))
        // A sign-in page: e-mail and password.
        assertFalse(offered(webPage(htmlForm(
            htmlInput(1, type = "email", name = "email", autocomplete = "username email"),
            htmlInput(2, type = "password", name = "password")))))
    }

    @Test fun checkoutFormsAreOfferedAndLoneEmailStepsAreNot() {
        assertTrue(offered(webPage(htmlForm(
            htmlInput(1, name = "name", autocomplete = "name"),
            htmlInput(2, name = "street", autocomplete = "street-address"),
            htmlInput(3, name = "zip", autocomplete = "postal-code")))))
        assertFalse(offered(webPage(htmlForm(htmlInput(1, type = "email", name = "email", autocomplete = "email")))))
        assertTrue(offered(webPage(htmlForm(htmlInput(1, name = "postal_code", autocomplete = "postal-code")))))
    }

    // ---- the pending store ----------------------------------------------------------------------------------------

    private fun request(expiresAt: Long, packageName: String = "com.example.shop") =
        ProfileFillRequest(packageName, "identity", null, emptyMap(), expiresAt)

    @Test fun aTokenIsTakenOnce() {
        val store = PendingProfileRequests()
        val token = store.put(request(expiresAt = 1_000), now = 0)
        assertEquals("com.example.shop", store.take(token, now = 10)?.packageName)
        assertNull(store.take(token, now = 10))
        assertNull(store.take(null, now = 10))
        assertNull(store.take("unknown", now = 10))
    }

    @Test fun anExpiredTokenIsRefusedAndRemoved() {
        val store = PendingProfileRequests()
        val token = store.put(request(expiresAt = 120_000), now = 0)
        assertNull(store.take(token, now = 120_000))
        assertEquals(0, store.size())
        // Expired requests are dropped when a new one arrives.
        store.put(request(expiresAt = 100), now = 0)
        store.put(request(expiresAt = 500), now = 200)
        assertEquals(1, store.size())
    }

    @Test fun theStoreIsBoundedAndDropsTheOldestFirst() {
        val store = PendingProfileRequests(capacity = 3)
        val tokens = (1..4).map { store.put(request(expiresAt = 10_000, packageName = "app.$it"), now = 0) }
        assertEquals(3, store.size())
        assertNull(store.take(tokens[0], now = 1))
        assertEquals("app.4", store.take(tokens[3], now = 1)?.packageName)
        assertNotEquals(tokens[1], tokens[2])
    }

    @Test fun aCancelledRequestIsRemoved() {
        val store = PendingProfileRequests()
        val token = store.put(request(expiresAt = 10_000), now = 0)
        store.remove(token)
        assertNull(store.take(token, now = 1))
    }

    // ---- values a picked profile fills ----------------------------------------------------------------------------

    @Test fun onlyRequestedFieldsWithValuesAreFilled() {
        val profile = AutofillProfile(name = "Alex Doe", email = "alex@example.invalid", phone = "  ", city = "Springfield")
        val fields = mapOf(1 to ProfileField.GIVEN_NAME, 2 to ProfileField.FAMILY_NAME, 3 to ProfileField.EMAIL,
            4 to ProfileField.PHONE, 5 to ProfileField.POSTAL_CODE, 6 to ProfileField.CITY)
        assertEquals(mapOf(1 to "Alex", 2 to "Doe", 3 to "alex@example.invalid", 6 to "Springfield"), profileFillValues(profile, fields))
    }

    @Test fun aProfileWithoutTheRequestedKindsFillsNothing() {
        val profile = AutofillProfile(email = "alex@example.invalid")
        assertTrue(profileFillValues(profile, mapOf(1 to ProfileField.ADDRESS1, 2 to ProfileField.COUNTRY)).isEmpty())
        assertTrue(profileFillValues(AutofillProfile(), mapOf(1 to ProfileField.NAME)).isEmpty())
        // A one-word name fills the given name only.
        assertEquals(mapOf(1 to "Cher"), profileFillValues(AutofillProfile(name = "Cher"),
            mapOf(1 to ProfileField.GIVEN_NAME, 2 to ProfileField.FAMILY_NAME)))
    }

    @Test fun aFullNameIsTrimmedAndSingleSpaced() {
        assertEquals("Mary Jane Watson", AutofillProfile(name = "  Mary  Jane\tWatson ").value(ProfileField.NAME))
        assertEquals("", AutofillProfile(name = "   ").value(ProfileField.NAME))
        // The stored value keeps its own format: only what is filled changes.
        assertEquals(" Alex  Doe ", AutofillProfile(name = " Alex  Doe ").name)
    }

    // ---- destination ----------------------------------------------------------------------------------------------

    @Test fun aBrowserFormNeedsOneHttpsOrigin() {
        val field = htmlInput(1, name = "zip", autocomplete = "postal-code")
        assertEquals(ProfileDestination("https://shop.example.com", false),
            profileDestination(listOf(webPage(field, domain = "Shop.Example.com")), browser = true))
        assertNull(profileDestination(listOf(webPage(field, scheme = "http")), browser = true))
        assertNull(profileDestination(listOf(webPage(field), webPage(domain = "other.example")), browser = true))
        assertNull(profileDestination(listOf(appScreen(appInput(1))), browser = true))
    }

    @Test fun anAppIsItsOwnDestinationAndItsWebViewIsFlagged() {
        assertEquals(ProfileDestination(null, false), profileDestination(listOf(appScreen(appInput(1))), browser = false))
        assertEquals(ProfileDestination(null, true),
            profileDestination(listOf(webPage(htmlInput(1, name = "zip"), domain = "claimed.example")), browser = false))
    }
}
