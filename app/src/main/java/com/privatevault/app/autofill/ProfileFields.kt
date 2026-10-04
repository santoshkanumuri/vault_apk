package com.privatevault.app.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.autofill.AutofillId
import java.util.Locale

private val EMAIL_TOKENS = setOf("emailaddress", "email", "emailid")
private val GIVEN_NAME_TOKENS = setOf("givenname", "firstname", "fname")
private val FAMILY_NAME_TOKENS = setOf("familyname", "lastname", "lname", "surname")
private val PHONE_TOKENS = setOf("phone", "tel", "mobile", "mobilenumber", "phonenumber", "telephone", "telnational",
    "mobilephone", "cellphone")
private val POSTAL_CODE_TOKENS = setOf("postalcode", "zipcode", "zip", "pincode")
private val ADDRESS1_TOKENS = setOf("addressline1", "streetaddress", "street", "address1", "addresslineone")
private val ADDRESS2_TOKENS = setOf("addressline2", "address2", "addresslinetwo")
private val UNIT_TOKENS = setOf("addressline3", "apartment", "apartmentnumber", "apartmentno", "flat", "flatnumber", "flatno",
    "suite", "unit", "unitnumber", "unitno")
private val CITY_TOKENS = setOf("addresslevel2", "city", "town", "locality")
private val STATE_TOKENS = setOf("addresslevel1", "state", "province", "region")
private val COUNTRY_TOKENS = setOf("country", "countryname")
private val NAME_TOKENS = setOf("name", "fullname")

// Purposes that are not stored contact details. Card data, credentials, codes and partial phone numbers must never be
// filled from a profile, whatever the field is called.
private val OTHER_PURPOSES = setOf("additionalname", "middlename", "honorificprefix", "honorificsuffix", "nickname", "username",
    "newusername", "password", "newpassword", "currentpassword", "onetimecode", "2faappotpcode", "smsotpcode", "emailotpcode",
    "organization", "organizationtitle", "addresslevel3", "addresslevel4", "countrycode", "phonecountrycode", "bday", "bdayday",
    "bdaymonth", "bdayyear", "sex", "gender", "url", "photo", "impp", "language")

private fun otherPurpose(token: String): Boolean = token in OTHER_PURPOSES || token.startsWith("cc") || token.startsWith("creditcard") ||
    token.startsWith("transaction") || (token.startsWith("tel") && token !in PHONE_TOKENS)

/** Exact matches only: "name" is a full name, "firstname" is a given name, "username" is neither. */
private fun matchProfile(values: Set<String>, native: Boolean = false): ProfileField? {
    fun has(candidates: Set<String>) = candidates.any { it in values }
    return when {
        has(EMAIL_TOKENS) -> ProfileField.EMAIL
        has(PHONE_TOKENS) -> ProfileField.PHONE
        has(POSTAL_CODE_TOKENS) -> ProfileField.POSTAL_CODE
        "postaladdress" in values -> ProfileField.FULL_ADDRESS
        has(ADDRESS1_TOKENS) || (native && "address" in values) -> ProfileField.ADDRESS1
        has(ADDRESS2_TOKENS) -> ProfileField.ADDRESS2
        has(UNIT_TOKENS) -> ProfileField.UNIT
        has(CITY_TOKENS) -> ProfileField.CITY
        has(STATE_TOKENS) -> ProfileField.STATE
        has(COUNTRY_TOKENS) -> ProfileField.COUNTRY
        // Given and family names are checked before the full name, which only the single stored name can answer.
        has(GIVEN_NAME_TOKENS) -> ProfileField.GIVEN_NAME
        has(FAMILY_NAME_TOKENS) -> ProfileField.FAMILY_NAME
        has(NAME_TOKENS) -> ProfileField.NAME
        else -> null
    }
}

/**
 * Match explicit Autofill hints and unambiguous HTML field names, in that order. Never infer from a numeric input alone.
 * A native view's [idEntry] or [hintText] must equal a known name once punctuation is dropped, never merely contain one.
 */
internal fun profileField(hints: Set<String>, attributes: Map<String, String>, inputType: Int,
    idEntry: String? = null, hintText: String? = null): ProfileField? {
    if (attributes["type"]?.trim()?.lowercase(Locale.ROOT) in setOf("password", "hidden") || isPasswordInputType(inputType)) return null
    val tokens = purposeTokens(hints, attributes["autocomplete"]).map(::normalizeToken).filter { it.isNotEmpty() }.toSet()
    if (tokens.any(::otherPurpose)) return null
    matchProfile(tokens)?.let { return it }
    val names = listOf(attributes["name"], attributes["id"]).map(::normalizeToken).filter { it.isNotEmpty() }.toSet()
    val labels = listOf(idEntry, hintText).map(::normalizeToken).filter { it.isNotEmpty() }.toSet()
    // "cardholder" next to id="name" is still a card field.
    if ((names + labels).any { it.startsWith("cc") || it.startsWith("card") || it.startsWith("creditcard") }) return null
    matchProfile(names)?.let { return it }
    matchProfile(labels, native = true)?.let { return it }
    return when {
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE -> ProfileField.PHONE
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_MASK_VARIATION in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> ProfileField.EMAIL
        else -> null
    }
}

internal fun profileFields(structure: AssistStructure, browser: Boolean): Map<AutofillId, ProfileField>? {
    val tree = formTree(structure) ?: return null
    return classifyProfileForm(tree.roots, browser)?.entries?.associate { (index, field) -> tree.ids[index] to field }
}
