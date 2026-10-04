package com.privatevault.app.autofill

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.json.JSONObject

/** Ordinary contact details. The vault entry is the source of truth; the local Autofill copy is optional. */
internal data class AutofillProfile(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val address1: String = "",
    val address2: String = "",
    val unit: String = "",
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val country: String = "",
) {
    fun encode(): String = JSONObject().apply {
        put("name", name); put("email", email); put("phone", phone)
        put("address1", address1); put("address2", address2); put("unit", unit)
        put("city", city); put("state", state); put("postalCode", postalCode); put("country", country)
    }.toString()

    /** The vault stores one name. Given is everything before the last word (or the whole name if it is one word). */
    private fun nameWords(): List<String> = name.trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    fun value(field: ProfileField): String = when (field) {
        ProfileField.NAME -> name
        ProfileField.GIVEN_NAME -> nameWords().let { if (it.size < 2) it.firstOrNull().orEmpty() else it.dropLast(1).joinToString(" ") }
        ProfileField.FAMILY_NAME -> nameWords().let { if (it.size < 2) "" else it.last() }
        ProfileField.EMAIL -> email
        ProfileField.PHONE -> phone
        ProfileField.ADDRESS1 -> address1
        ProfileField.ADDRESS2 -> address2.ifBlank { unit }
        ProfileField.UNIT -> unit
        ProfileField.FULL_ADDRESS -> listOf(address1, address2, unit, city, state, postalCode, country)
            .filter(String::isNotBlank).joinToString(", ")
        ProfileField.CITY -> city
        ProfileField.STATE -> state
        ProfileField.POSTAL_CODE -> postalCode
        ProfileField.COUNTRY -> country
    }

    val hasValue: Boolean get() = ProfileField.entries.any { value(it).isNotBlank() }

    companion object {
        fun decode(value: String): AutofillProfile? = runCatching {
            val json = JSONObject(value)
            AutofillProfile(json.optString("name"), json.optString("email"), json.optString("phone"),
                json.optString("address1"), json.optString("address2"), json.optString("unit"),
                json.optString("city"), json.optString("state"), json.optString("postalCode"),
                json.optString("country"))
        }.getOrNull()
    }
}

internal enum class ProfileField {
    NAME, GIVEN_NAME, FAMILY_NAME, EMAIL, PHONE, ADDRESS1, ADDRESS2, UNIT, FULL_ADDRESS, CITY, STATE, POSTAL_CODE, COUNTRY
}

/** What a field kind is called in the picker. Labels only, never the stored values. */
internal fun ProfileField.summaryLabel(): String = when (this) {
    ProfileField.NAME, ProfileField.GIVEN_NAME, ProfileField.FAMILY_NAME -> "Name"
    ProfileField.EMAIL -> "Email"
    ProfileField.PHONE -> "Phone"
    ProfileField.ADDRESS1, ProfileField.ADDRESS2, ProfileField.UNIT, ProfileField.FULL_ADDRESS -> "Address"
    ProfileField.CITY -> "City"
    ProfileField.STATE -> "State"
    ProfileField.POSTAL_CODE -> "Postal code"
    ProfileField.COUNTRY -> "Country"
}

/** "Name · Email · Phone +2": the kinds a profile will fill, in a fixed order, at most [shown] of them. */
internal fun profileFillSummary(kinds: Collection<ProfileField>, shown: Int = 3): String {
    val labels = kinds.sortedBy { it.ordinal }.map { it.summaryLabel() }.distinct()
    if (labels.isEmpty()) return "Autofill details"
    return labels.take(shown).joinToString(" · ") + if (labels.size > shown) " +${labels.size - shown}" else ""
}

internal fun VaultEntry.autofillProfile(): AutofillProfile? =
    if (type == EntryType.AUTOFILL) AutofillProfile.decode(primaryValue) else null
