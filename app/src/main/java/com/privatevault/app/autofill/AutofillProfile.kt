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

    fun value(field: ProfileField): String = when (field) {
        ProfileField.NAME -> name
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
    NAME, EMAIL, PHONE, ADDRESS1, ADDRESS2, UNIT, FULL_ADDRESS, CITY, STATE, POSTAL_CODE, COUNTRY
}

internal fun VaultEntry.autofillProfile(): AutofillProfile? =
    if (type == EntryType.AUTOFILL) AutofillProfile.decode(primaryValue) else null
