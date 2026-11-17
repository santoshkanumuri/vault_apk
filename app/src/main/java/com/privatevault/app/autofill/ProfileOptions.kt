package com.privatevault.app.autofill

import android.view.autofill.AutofillValue
import java.util.Locale

/*
 * State and country drop-downs. A saved address keeps what the user typed ("TX", "Texas", "United States", "USA"); a
 * <select> lists its own spelling. These helpers find the one option that means the same thing, or nothing.
 */

private val US_STATES = mapOf(
    "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California", "CO" to "Colorado",
    "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia", "FL" to "Florida", "GA" to "Georgia",
    "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
    "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan",
    "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
    "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina",
    "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island",
    "SC" to "South Carolina", "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont",
    "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
    "PR" to "Puerto Rico", "GU" to "Guam", "VI" to "Virgin Islands", "AS" to "American Samoa", "MP" to "Northern Mariana Islands",
)

private val CA_PROVINCES = mapOf(
    "AB" to "Alberta", "BC" to "British Columbia", "MB" to "Manitoba", "NB" to "New Brunswick", "NL" to "Newfoundland and Labrador",
    "NS" to "Nova Scotia", "NT" to "Northwest Territories", "NU" to "Nunavut", "ON" to "Ontario", "PE" to "Prince Edward Island",
    "QC" to "Quebec", "SK" to "Saskatchewan", "YT" to "Yukon",
)

private val COUNTRY_ALIASES = mapOf(
    "usa" to "US", "unitedstatesofamerica" to "US", "america" to "US", "us" to "US",
    "uk" to "GB", "greatbritain" to "GB", "england" to "GB", "britain" to "GB", "unitedkingdomofgreatbritainandnorthernireland" to "GB",
    "uae" to "AE", "southkorea" to "KR", "korea" to "KR", "russia" to "RU", "czechia" to "CZ", "holland" to "NL",
)

private fun norm(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

/** Every spelling that means [value] for this kind of field, normalized: "TX" -> {tx, texas}. */
private fun spellings(field: ProfileField, value: String): Set<String> {
    val key = norm(value)
    if (key.isEmpty()) return emptySet()
    return when (field) {
        ProfileField.STATE -> {
            val table = US_STATES + CA_PROVINCES
            val code = table.entries.firstOrNull { norm(it.key) == key || norm(it.value) == key }?.key
            if (code == null) setOf(key) else setOf(norm(code), norm(table.getValue(code)))
        }
        ProfileField.COUNTRY -> {
            val code = COUNTRY_ALIASES[key]
                ?: Locale.getISOCountries().firstOrNull { iso -> norm(iso) == key || norm(Locale("", iso).getDisplayCountry(Locale.ENGLISH)) == key }
            if (code == null) setOf(key)
            else setOf(norm(code), norm(Locale("", code).getDisplayCountry(Locale.ENGLISH))) +
                COUNTRY_ALIASES.filterValues { it == code }.keys + (if (code == "US") setOf("usa", "unitedstatesofamerica") else emptySet())
        }
        else -> setOf(key)
    }
}

/**
 * The drop-down entry for [value]: an exact match of any spelling first ("TX" or "Texas" for a Texas address), then an
 * entry that names it with something around it ("Texas (TX)", "US - United States"). Null when none or several fit.
 */
internal fun profileOption(field: ProfileField, value: String, options: List<String>): Int? {
    val wanted = spellings(field, value)
    if (wanted.isEmpty() || options.isEmpty()) return null
    val normalized = options.map(::norm)
    normalized.indices.filter { normalized[it] in wanted }.singleOrNull()?.let { return it }
    // Words around the name: only a full name (4+ letters) or a code standing alone as a word counts.
    val words = options.map { option -> option.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty) }
    return options.indices.filter { index ->
        wanted.any { spelling -> (spelling.length >= 4 && normalized[index].contains(spelling)) || spelling in words[index] }
    }.singleOrNull()
}

/** What fills [value] into a field: text, or the matching entry of a drop-down ([options] non-empty). Null when no entry fits. */
internal fun profileAutofillValue(field: ProfileField, value: String, options: List<String>?): AutofillValue? =
    if (options.isNullOrEmpty()) AutofillValue.forText(value) else profileOption(field, value, options)?.let(AutofillValue::forList)
