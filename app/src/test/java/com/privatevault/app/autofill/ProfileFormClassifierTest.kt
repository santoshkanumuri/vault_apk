package com.privatevault.app.autofill

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class ProfileFormClassifierTest {
    private fun profile(vararg roots: FormNode, browser: Boolean = false) = classifyProfileForm(roots.toList(), browser)

    private fun fieldOf(vararg attrs: Pair<String, String>, hints: List<String> = emptyList(), inputType: Int = TEXT_INPUT): ProfileField? =
        profileField(hints.toSet(), attrs.toMap(), inputType)

    // ---- given / family names ------------------------------------------------------------------------------------

    @Test fun givenAndFamilyNamesAreDerivedFromTheSingleStoredName() {
        fun parts(name: String) = AutofillProfile(name = name).let { it.value(ProfileField.GIVEN_NAME) to it.value(ProfileField.FAMILY_NAME) }
        assertEquals("Alex" to "Doe", parts("Alex Doe"))
        assertEquals("Mary Jane" to "Watson", parts("Mary Jane Watson"))
        assertEquals("Cher" to "", parts("Cher"))
        assertEquals("Alex" to "Doe", parts("  Alex   Doe  "))
        assertEquals("" to "", parts(""))
        assertEquals("" to "", parts("   "))
        assertEquals("Alex Doe", AutofillProfile(name = "Alex Doe").value(ProfileField.NAME))
    }

    @Test fun everyProfileFieldHasAValueAccessor() {
        val profile = AutofillProfile(name = "Alex Doe", email = "a@example.invalid", phone = "1", address1 = "2", address2 = "3",
            unit = "4", city = "5", state = "6", postalCode = "7", country = "8")
        ProfileField.entries.forEach { assertTrue(it.name, profile.value(it).isNotBlank()) }
        assertTrue(profile.hasValue)
        assertFalse(AutofillProfile().hasValue)
    }

    // ---- token matching -------------------------------------------------------------------------------------------

    @Test fun nameTokensDistinguishFullGivenAndFamilyNames() {
        assertEquals(ProfileField.GIVEN_NAME, fieldOf(hints = listOf("given-name")))
        assertEquals(ProfileField.GIVEN_NAME, fieldOf("name" to "first_name"))
        assertEquals(ProfileField.GIVEN_NAME, fieldOf("id" to "fname"))
        assertEquals(ProfileField.GIVEN_NAME, fieldOf("name" to "givenName"))
        assertEquals(ProfileField.FAMILY_NAME, fieldOf(hints = listOf("family-name")))
        assertEquals(ProfileField.FAMILY_NAME, fieldOf("name" to "last_name"))
        assertEquals(ProfileField.FAMILY_NAME, fieldOf("id" to "lname"))
        assertEquals(ProfileField.FAMILY_NAME, fieldOf("name" to "surname"))
        assertEquals(ProfileField.NAME, fieldOf(hints = listOf("name")))
        assertEquals(ProfileField.NAME, fieldOf("name" to "full_name"))
        assertEquals(ProfileField.NAME, fieldOf("autocomplete" to "name"))
        assertEquals(ProfileField.NAME, fieldOf("id" to "fullname"))
        // An explicit hint beats a guess from the field name.
        assertEquals(ProfileField.NAME, fieldOf("name" to "first_name", hints = listOf("name")))
    }

    @Test fun middleNamesAndOtherPurposesAreNotFilled() {
        assertNull(fieldOf(hints = listOf("additional-name")))
        assertNull(fieldOf("name" to "middle_name"))
        assertNull(fieldOf("name" to "name", "autocomplete" to "additional-name"))
        assertNull(fieldOf("name" to "name", "autocomplete" to "nickname"))
        assertNull(fieldOf("name" to "name", "autocomplete" to "organization"))
        assertNull(fieldOf("name" to "state", "autocomplete" to "address-level3"))
        assertNull(fieldOf("name" to "email", "autocomplete" to "username"))
    }

    @Test fun phoneTokensAndPartialPhoneTokens() {
        listOf("tel", "tel-national", "telephone", "phone", "phoneNumber", "mobile").forEach { assertEquals(it, ProfileField.PHONE, fieldOf(hints = listOf(it))) }
        listOf("telnational", "phonenumber", "mobilephone", "cellphone", "mobile_number", "tel_national").forEach {
            assertEquals(it, ProfileField.PHONE, fieldOf("name" to it))
        }
        listOf("tel-country-code", "tel-area-code", "tel-local", "tel-local-prefix", "tel-local-suffix", "tel-extension").forEach {
            assertNull(it, fieldOf(hints = listOf(it)))
            // A guessable name does not turn a partial number into the whole number.
            assertNull(it, fieldOf("name" to "phone", "autocomplete" to it))
        }
    }

    @Test fun cardPasswordCodeAndHiddenFieldsAreNeverProfileFields() {
        listOf("cc-name", "cc-number", "cc-exp", "cc-exp-month", "cc-csc", "cc-given-name", "cc-family-name", "cc-type",
            "creditCardNumber", "creditCardSecurityCode").forEach {
            assertNull(it, fieldOf(hints = listOf(it)))
            assertNull(it, fieldOf("name" to "name", "autocomplete" to it))
        }
        assertNull(fieldOf("name" to "cardholder", "id" to "name"))
        assertNull(fieldOf("name" to "cc_name", "id" to "name"))
        assertNull(profileField(emptySet(), emptyMap(), TEXT_INPUT, "card_holder", "Name"))
        assertNull(fieldOf("name" to "email", "type" to "password"))
        assertNull(fieldOf("name" to "email", "type" to "hidden"))
        assertNull(fieldOf("name" to "email", hints = listOf("one-time-code")))
        assertNull(fieldOf("name" to "email", hints = listOf("password")))
        assertNull(fieldOf("name" to "email", hints = listOf("new-password")))
        assertNull(fieldOf("name" to "zip", inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertNull(fieldOf("name" to "zip", inputType = TEXT_INPUT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
    }

    @Test fun hintsAreCaseInsensitiveAndIgnoreQualifiers() {
        assertEquals(ProfileField.EMAIL, fieldOf(hints = listOf("EmailAddress")))
        assertEquals(ProfileField.ADDRESS1, fieldOf(hints = listOf("shipping address-line1")))
        assertEquals(ProfileField.ADDRESS1, fieldOf(hints = listOf("section-ship SHIPPING Street-Address")))
        assertEquals(ProfileField.POSTAL_CODE, fieldOf("autocomplete" to "billing postal-code"))
        assertEquals(ProfileField.CITY, fieldOf("autocomplete" to "section-blue shipping address-level2"))
    }

    @Test fun existingFieldKindsStillMatch() {
        assertEquals(ProfileField.PHONE, profileField(emptySet(), mapOf("autocomplete" to "tel"), InputType.TYPE_CLASS_PHONE))
        assertEquals(ProfileField.UNIT, profileField(emptySet(), mapOf("name" to "flat_no"), InputType.TYPE_CLASS_NUMBER))
        assertEquals(ProfileField.POSTAL_CODE, profileField(emptySet(), mapOf("id" to "pin_code"), InputType.TYPE_CLASS_NUMBER))
        assertEquals(ProfileField.FULL_ADDRESS, profileField(setOf("postalAddress"), emptyMap(), TEXT_INPUT))
        assertEquals(ProfileField.COUNTRY, profileField(emptySet(), mapOf("name" to "country"), TEXT_INPUT))
        assertEquals(ProfileField.EMAIL, profileField(emptySet(), emptyMap(), EMAIL_INPUT))
        assertEquals(ProfileField.PHONE, profileField(emptySet(), emptyMap(), InputType.TYPE_CLASS_PHONE))
        assertNull(profileField(emptySet(), emptyMap(), InputType.TYPE_CLASS_NUMBER))
        assertNull(profileField(emptySet(), emptyMap(), TEXT_INPUT))
        assertNull(profileField(setOf("emailAddress"), mapOf("type" to "password"), TEXT_INPUT))
    }

    @Test fun nativeIdAndHintTextMustEqualAKnownName() {
        fun native(entry: String? = null, hint: String? = null) = profileField(emptySet(), emptyMap(), TEXT_INPUT, entry, hint)
        assertEquals(ProfileField.EMAIL, native(hint = "Email"))
        assertEquals(ProfileField.EMAIL, native(entry = "email_address"))
        assertEquals(ProfileField.PHONE, native(hint = "Phone number"))
        assertEquals(ProfileField.PHONE, native(entry = "mobile_number"))
        assertEquals(ProfileField.NAME, native(hint = "Full name"))
        assertEquals(ProfileField.NAME, native(entry = "name"))
        assertEquals(ProfileField.GIVEN_NAME, native(hint = "First name"))
        assertEquals(ProfileField.FAMILY_NAME, native(entry = "last_name"))
        assertEquals(ProfileField.CITY, native(hint = "City"))
        assertEquals(ProfileField.STATE, native(entry = "state"))
        assertEquals(ProfileField.POSTAL_CODE, native(hint = "ZIP code"))
        assertEquals(ProfileField.POSTAL_CODE, native(entry = "postal_code"))
        assertEquals(ProfileField.POSTAL_CODE, native(hint = "PIN code"))
        assertEquals(ProfileField.COUNTRY, native(hint = "Country"))
        assertEquals(ProfileField.ADDRESS1, native(hint = "Address"))
        assertEquals(ProfileField.ADDRESS1, native(entry = "street_address"))
        assertEquals(ProfileField.ADDRESS1, native(hint = "Address line 1"))
        assertEquals(ProfileField.ADDRESS2, native(entry = "address_line_2"))
        // Containing a name is not equal to it.
        assertNull(native(hint = "Enter your email address"))
        assertNull(native(hint = "Search city"))
        assertNull(native(entry = "name_label"))
        assertNull(native(hint = "Company name"))
        assertNull(native(entry = "username"))
        // "address" alone is only trusted for native views.
        assertNull(profileField(emptySet(), mapOf("name" to "address"), TEXT_INPUT))
    }

    // ---- whole forms ------------------------------------------------------------------------------------------------

    @Test fun nativeFormMapsEveryRecognizedField() {
        val fields = profile(appScreen(appInput(0, hints = listOf("emailAddress")), appInput(1, InputType.TYPE_CLASS_PHONE, hints = listOf("phone")),
            appInput(2, hints = listOf("postalAddress")), appInput(3, hints = listOf("addressLevel2")),
            appInput(4, InputType.TYPE_CLASS_NUMBER, hints = listOf("postalCode")), appInput(5, hint = "Nothing here")))!!
        assertEquals(mapOf(0 to ProfileField.EMAIL, 1 to ProfileField.PHONE, 2 to ProfileField.FULL_ADDRESS, 3 to ProfileField.CITY,
            4 to ProfileField.POSTAL_CODE), fields)
    }

    @Test fun nativeHintTextAndViewIdMatchExactly() {
        val fields = profile(appScreen(appInput(0, hint = "Full name"), appInput(1, idEntry = "email"), appInput(2, hint = "Enter your phone"),
            appInput(3, idEntry = "zip_code")))!!
        assertEquals(mapOf(0 to ProfileField.NAME, 1 to ProfileField.EMAIL, 3 to ProfileField.POSTAL_CODE), fields)
    }

    @Test fun duplicateFieldKindsAreAllMapped() {
        val fields = profile(webPage(htmlForm(htmlInput(0, type = "email", name = "email"), htmlInput(1, type = "email", name = "email_confirm",
            autocomplete = "email"), htmlInput(2, autocomplete = "shipping address-line1"), htmlInput(3, autocomplete = "billing address-line1"))),
            browser = true)!!
        assertEquals(mapOf(0 to ProfileField.EMAIL, 1 to ProfileField.EMAIL, 2 to ProfileField.ADDRESS1, 3 to ProfileField.ADDRESS1), fields)
    }

    @Test fun givenAndFamilyFieldsAreMappedTogetherWithOtherDetails() {
        val fields = profile(webPage(htmlForm(htmlInput(0, autocomplete = "given-name"), htmlInput(1, autocomplete = "family-name"),
            htmlInput(2, autocomplete = "additional-name"), htmlInput(3, type = "tel", autocomplete = "tel"),
            htmlInput(4, type = "tel", autocomplete = "tel-country-code"))), browser = true)!!
        assertEquals(mapOf(0 to ProfileField.GIVEN_NAME, 1 to ProfileField.FAMILY_NAME, 3 to ProfileField.PHONE), fields)
    }

    @Test fun cardNameIsNotAProfileName() {
        val form = webPage(htmlForm(htmlInput(0, name = "cardholder", autocomplete = "cc-name"), htmlInput(1, name = "name", autocomplete = "cc-name"),
            htmlInput(2, name = "number", autocomplete = "cc-number")))
        assertNull(profile(form, browser = true))
        val mixed = webPage(htmlForm(htmlInput(0, name = "name", autocomplete = "cc-name"), htmlInput(1, type = "email", name = "email")))
        assertEquals(mapOf(1 to ProfileField.EMAIL), profile(mixed, browser = true))
    }

    @Test fun partialPhoneFieldsAreIgnoredInAForm() {
        val form = webPage(htmlForm(htmlInput(0, type = "tel", name = "cc", autocomplete = "tel-country-code"),
            htmlInput(1, type = "tel", name = "area", autocomplete = "tel-area-code"), htmlInput(2, type = "tel", name = "local", autocomplete = "tel-local"),
            htmlInput(3, type = "tel", name = "ext", autocomplete = "tel-extension"), htmlInput(4, type = "tel", autocomplete = "tel-national")))
        assertEquals(mapOf(4 to ProfileField.PHONE), profile(form, browser = true))
        assertNull(profile(webPage(htmlForm(htmlInput(0, type = "tel", autocomplete = "tel-country-code"))), browser = true))
    }

    @Test fun credentialsAndCodesAreExcludedFromProfileForms() {
        val form = webPage(htmlForm(htmlInput(0, type = "email", name = "email"), htmlInput(1, type = "password", name = "zip"),
            htmlInput(2, name = "otp"), htmlInput(3, type = "hidden", name = "phone"), htmlInput(4, autocomplete = "one-time-code", name = "zip"),
            htmlInput(5, autocomplete = "new-password", name = "city"), htmlInput(6, name = "name")))
        assertEquals(mapOf(0 to ProfileField.EMAIL, 6 to ProfileField.NAME), profile(form, browser = true))
    }

    @Test fun invisibleDisabledAndNonTextFieldsAreSkipped() {
        val form = webPage(htmlForm(htmlInput(0, type = "email", name = "email", visible = false),
            htmlInput(1, type = "email", name = "email", enabled = false),
            FormNode(2, htmlTag = "input", autofillType = 2, htmlAttributes = mapOf("name" to "email")), htmlInput(3, type = "email", name = "email")))
        assertEquals(mapOf(3 to ProfileField.EMAIL), profile(form, browser = true))
    }

    @Test fun manyFormsNeedAFocusedFieldAndUseOnlyThatForm() {
        fun page(focus: Int?) = webPage(
            htmlForm(htmlInput(0, type = "email", name = "email", focused = focus == 0), htmlInput(1, name = "name")),
            htmlForm(htmlInput(2, type = "email", name = "email", focused = focus == 2), htmlInput(3, type = "tel", name = "phone")))
        assertNull(profile(page(null), browser = true))
        assertEquals(setOf(0, 1), profile(page(0), browser = true)!!.keys)
        assertEquals(setOf(2, 3), profile(page(2), browser = true)!!.keys)
        // A focused field the profile does not recognize still names the form.
        val other = webPage(htmlForm(htmlInput(0, type = "email", name = "email")),
            htmlForm(htmlInput(2, type = "email", name = "email"), htmlInput(5, name = "company", focused = true)))
        assertEquals(setOf(2), profile(other, browser = true)!!.keys)
        // Focus outside every form decides nothing.
        assertNull(profile(webPage(htmlInput(9, name = "q", focused = true), htmlForm(htmlInput(0, type = "email")), htmlForm(htmlInput(1, type = "email"))),
            browser = true))
    }

    @Test fun oneFormKeepsEveryFieldOnThePage() {
        val page = webPage(htmlInput(0, type = "email", name = "email"), htmlForm(htmlInput(1, name = "name")))
        assertEquals(setOf(0, 1), profile(page, browser = true)!!.keys)
    }

    @Test fun capIsThirtyRecognizedFields() {
        fun emailPage(count: Int) = webPage(htmlForm(*(0 until count).map { htmlInput(it, type = "email", name = "email") }.toTypedArray()))
        assertEquals(30, profile(emailPage(30), browser = true)!!.size)
        assertNull(profile(emailPage(31), browser = true))
    }

    @Test fun unsafeOrAmbiguousPagesAreRefused() {
        val field = htmlForm(htmlInput(0, type = "email", name = "email"))
        assertNotNull(profile(webPage(field), browser = true))
        assertNull(profile(webPage(field, FormNode(null, htmlTag = "iframe")), browser = true))
        assertNull(profile(webPage(field, scheme = "http"), browser = true))
        assertNull(profile(webPage(field, webPage(domain = "other.example.com")), browser = true))
        assertNull(profile(FormNode(null, children = listOf(field)), browser = true))
        assertNull(profile(appScreen(webPage(field, scheme = "http"))))
        assertNull(profile(appScreen(webPage(field, domain = "a.example.com"), webPage(domain = "b.example.com"))))
        assertNotNull(profile(appScreen(webPage(field))))
        assertNull(profile(appScreen(appLabel("nothing"))))
        assertNull(profile())
    }

    @Test fun nodeAndDepthBoundsApplyToProfilesToo() {
        val field = htmlForm(htmlInput(0, type = "email", name = "email"))
        assertNotNull(profile(webPage(field, *Array(1990) { FormNode(null) }), browser = true))
        assertNull(profile(webPage(field, *Array(2000) { FormNode(null) }), browser = true))
        val deep = (1..41).fold(htmlInput(1, type = "email")) { inner, _ -> FormNode(null, children = listOf(inner)) }
        assertNull(profile(webPage(field, deep), browser = true))
    }

    // ---- picker summary -----------------------------------------------------------------------------------------------

    @Test fun summaryNamesKindsNotValues() {
        assertEquals("Name · Email · Phone +2", profileFillSummary(listOf(ProfileField.PHONE, ProfileField.EMAIL, ProfileField.NAME,
            ProfileField.ADDRESS1, ProfileField.CITY)))
        assertEquals("Name · Email", profileFillSummary(listOf(ProfileField.GIVEN_NAME, ProfileField.FAMILY_NAME, ProfileField.EMAIL,
            ProfileField.EMAIL)))
        assertEquals("Address", profileFillSummary(listOf(ProfileField.ADDRESS2, ProfileField.UNIT, ProfileField.FULL_ADDRESS, ProfileField.ADDRESS1)))
        assertEquals("Postal code · Country", profileFillSummary(listOf(ProfileField.COUNTRY, ProfileField.POSTAL_CODE)))
        assertEquals("Autofill details", profileFillSummary(emptyList()))
        assertEquals("Name · Email · Phone +5", profileFillSummary(ProfileField.entries))
    }
}
