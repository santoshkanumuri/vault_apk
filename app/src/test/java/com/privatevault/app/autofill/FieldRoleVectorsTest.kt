package com.privatevault.app.autofill

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared with the browser extension (`extension/fixtures/field-roles-v1.json` in the Windows
 * repository): the phone and the browser call the same code and address fields the same thing.
 */
class FieldRoleVectorsTest {
    private val names = mapOf(
        ProfileField.NAME to "name", ProfileField.GIVEN_NAME to "given", ProfileField.FAMILY_NAME to "family",
        ProfileField.EMAIL to "email", ProfileField.PHONE to "phone", ProfileField.ADDRESS1 to "address1",
        ProfileField.ADDRESS2 to "address2", ProfileField.UNIT to "unit", ProfileField.CITY to "city",
        ProfileField.STATE to "state", ProfileField.POSTAL_CODE to "postalCode", ProfileField.COUNTRY to "country")

    @Test fun codeAndAddressFieldsAreReadAsTheSharedVectorsSay() {
        val text = requireNotNull(javaClass.classLoader?.getResource("autofill-vectors/field-roles-v1.json")).readText()
        val cases = JsonParser.parseString(text).asJsonObject.getAsJsonArray("cases")
        assertTrue(cases.size() > 0)
        cases.forEach { element ->
            val case = element.asJsonObject
            val attributes = buildMap {
                case.get("name")?.let { put("name", it.asString) }
                case.get("autocomplete")?.let { put("autocomplete", it.asString) }
            }
            val tokens = purposeTokens(emptyList(), attributes["autocomplete"])
            val code = hasOtpHint(tokens) || isOtpFieldByName(tokens, attributes, null, null, 0, html = true)
            val role = if (code) "code" else profileField(emptySet(), attributes, 0)?.let { names.getValue(it) }
            val expected = case.get("role")?.takeUnless { it.isJsonNull }?.asString
            assertEquals(attributes.toString(), expected, role)
        }
    }
}
