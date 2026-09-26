package com.privatevault.app.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import com.privatevault.app.security.httpsOrigin
import java.util.Locale

/** Match explicit Autofill hints and unambiguous HTML field names. Never infer from a numeric input alone. */
internal fun profileField(hints: Set<String>, attributes: Map<String, String>, inputType: Int): ProfileField? {
    if (attributes["type"]?.lowercase(Locale.ROOT) in setOf("password", "hidden") ||
        inputType and InputType.TYPE_MASK_VARIATION in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return null
    val tokens = hints.flatMap { it.lowercase(Locale.ROOT).split(' ') }
        .map { it.replace(Regex("[^a-z0-9]"), "") }.toSet()
    val names = listOfNotNull(attributes["autocomplete"], attributes["name"], attributes["id"])
        .map { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") }
    fun has(vararg values: String): Boolean = values.any { it in tokens || it in names }
    return when {
        has("emailaddress", "email", "emailid") -> ProfileField.EMAIL
        has("phone", "tel", "mobile", "mobilenumber", "phonenumber", "telephone") -> ProfileField.PHONE
        has("postalcode", "zipcode", "zip", "pincode") -> ProfileField.POSTAL_CODE
        has("postaladdress") -> ProfileField.FULL_ADDRESS
        has("addressline1", "streetaddress", "street", "address1", "addresslineone") -> ProfileField.ADDRESS1
        has("addressline2", "address2", "addresslinetwo") -> ProfileField.ADDRESS2
        has("addressline3", "apartment", "apartmentnumber", "apartmentno", "flat", "flatnumber", "flatno", "suite", "unit", "unitnumber", "unitno") -> ProfileField.UNIT
        has("addresslevel2", "city", "town", "locality") -> ProfileField.CITY
        has("addresslevel1", "state", "province", "region") -> ProfileField.STATE
        has("country", "countryname") -> ProfileField.COUNTRY
        has("name", "fullname", "full_name") -> ProfileField.NAME
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE -> ProfileField.PHONE
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_MASK_VARIATION in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> ProfileField.EMAIL
        else -> null
    }
}

internal fun profileFields(structure: AssistStructure, browser: Boolean): Map<AutofillId, ProfileField>? {
    val found = linkedMapOf<AutofillId, ProfileField>()
    val origins = mutableSetOf<String>()
    var forms = 0
    var count = 0
    var unsafe = false
    fun visit(node: AssistStructure.ViewNode, depth: Int, visibleParent: Boolean = true) {
        if (++count > 2000 || depth > 40) { unsafe = true; return }
        if (!node.webDomain.isNullOrBlank()) {
            val origin = if (node.webScheme == "https") httpsOrigin("https://${node.webDomain}") else null
            if (node.webScheme != null && origin == null) unsafe = true
            if (origin != null) origins.add(origin)
        }
        if (node.htmlInfo?.tag.equals("form", true)) forms++
        if (node.htmlInfo?.tag.equals("iframe", true) || node.htmlInfo?.tag.equals("frame", true)) unsafe = true
        val visible = visibleParent && node.visibility == View.VISIBLE
        if (visible && node.isEnabled && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val attributes = node.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase(Locale.ROOT) to it.second }
            val hints = node.autofillHints.orEmpty().toSet() + attributes["autocomplete"].orEmpty().split(' ')
            if (hints.any { it.equals("one-time-code", true) || it.equals("password", true) || it.equals("new-password", true) }) {
                // A profile response must not include credential or code fields.
            } else node.autofillId?.let { id -> profileField(hints, attributes, node.inputType)?.let { found[id] = it } }
        }
        for (index in 0 until node.childCount) visit(node.getChildAt(index), depth + 1, visible)
    }
    for (index in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(index).rootViewNode, 0)
    if (unsafe || forms > 1 || origins.size > 1 || (browser && origins.size != 1) || found.isEmpty() ||
        found.values.size != found.values.toSet().size) return null
    return found
}
