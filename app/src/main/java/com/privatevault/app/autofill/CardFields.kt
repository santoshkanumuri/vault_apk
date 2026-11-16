package com.privatevault.app.autofill

import android.view.View
import java.util.Locale

/** A payment-card field this form asks for. */
internal enum class CardField { NUMBER, NAME, GIVEN_NAME, FAMILY_NAME, EXPIRY, EXP_MONTH, EXP_YEAR, CSC }

/**
 * One card field of a form: its kind plus what shapes its value. [maxLength] is -1 when unknown. [pattern] is the
 * field's placeholder or hint ("MM/YYYY", say). [options] are a drop-down's entries (month and year lists).
 */
internal data class CardFieldSpec(val kind: CardField, val maxLength: Int = -1, val pattern: String = "",
    val options: List<String> = emptyList())

/** A value for one field: text, or the index of a drop-down entry. */
internal sealed interface CardValue {
    data class Text(val text: String) : CardValue
    data class Choice(val index: Int) : CardValue
}

/** What a stored card can fill. [expiry] is the card's "MM/YY" text as saved. */
internal data class CardDetails(val number: String, val holder: String, val expiry: String, val securityCode: String)

// Explicit purposes (Android hints and HTML autocomplete tokens), normalized: lowercase letters and digits only.
private val EXPLICIT = mapOf(
    "ccnumber" to CardField.NUMBER, "creditcardnumber" to CardField.NUMBER,
    "ccname" to CardField.NAME, "ccgivenname" to CardField.GIVEN_NAME, "ccfamilyname" to CardField.FAMILY_NAME,
    "ccexp" to CardField.EXPIRY, "creditcardexpirationdate" to CardField.EXPIRY,
    "ccexpmonth" to CardField.EXP_MONTH, "creditcardexpirationmonth" to CardField.EXP_MONTH,
    "ccexpyear" to CardField.EXP_YEAR, "creditcardexpirationyear" to CardField.EXP_YEAR,
    "cccsc" to CardField.CSC, "creditcardsecuritycode" to CardField.CSC,
)

/** Recognized purposes that are not card fields (HTML autocomplete names and Android hints, normalized). */
private val OTHER_PURPOSES = setOf("name", "givenname", "familyname", "additionalname", "nickname", "email", "emailaddress", "tel",
    "phone", "phonenumber", "username", "newusername", "password", "currentpassword", "newpassword", "onetimecode", "smsotpcode",
    "streetaddress", "addressline1", "addressline2", "addressline3", "addresslevel1", "addresslevel2", "addresslevel3",
    "addresslevel4", "postalcode", "postaladdress", "country", "countryname", "organization", "organizationtitle", "bday",
    "bdayday", "bdaymonth", "bdayyear", "url", "personname", "personnamegiven", "personnamefamily", "cctype",
    "ccadditionalname", "transactionamount", "transactioncurrency")

/** Cards of other kinds share the words "card number"; Nuvori stores payment cards only. */
private val NOT_PAYMENT = listOf("gift", "loyalty", "member", "reward", "club", "voucher", "coupon", "promo", "library", "idcard",
    "identity", "insurance", "aadhaar", "pancard", "simcard")

private fun has(text: String, vararg words: String) = words.any { it in text }

/**
 * The card field a name, id, placeholder or label describes. Checked from the most specific kind to the least, so
 * "cardholder" is a name, "expiry month" a month and "card number" a number. Null when it names none.
 */
internal fun cardFieldByName(name: String): CardField? {
    val n = normalizeToken(name)
    if (n.isEmpty() || NOT_PAYMENT.any { it in n }) return null
    // "cc" only as a prefix: inside a word ("account", "access") it is not about cards.
    val card = has(n, "card", "credit", "debit") || n.startsWith("cc")
    val cardContext = card || has(n, "payment", "exp", "valid")
    return when {
        // "Security code" and "verification code" are one-time codes unless the field also names the card.
        has(n, "cvv", "cvc", "csc", "cvn", "cardcode", "cardverification") || n == "cid" ||
            (card && has(n, "securitycode", "verificationcode")) -> CardField.CSC
        (has(n, "month") || n.endsWith("mm") || n == "mm") && (cardContext || n == "mm") -> CardField.EXP_MONTH
        (has(n, "year") || n.endsWith("yyyy") || n.endsWith("yy") || n == "yy") && !has(n, "mmyy", "mmyyyy") &&
            (cardContext || n == "yy" || n == "yyyy") -> CardField.EXP_YEAR
        has(n, "expiry", "expiration", "expdate", "ccexp", "cardexp", "validthru", "validuntil", "mmyy", "mmyyyy") || n == "exp" -> CardField.EXPIRY
        has(n, "cardholder", "nameoncard", "ccname", "holdername", "cardowner", "cardname") -> CardField.NAME
        has(n, "cardnumber", "ccnumber", "ccnum", "cardno", "cardnum", "creditcard", "debitcard") || n == "pan" -> CardField.NUMBER
        else -> null
    }
}

/**
 * The card field of one input: explicit purposes first (an `autocomplete` of "cc-number", Android's
 * creditCardNumber hint), then names and ids, then a native view's id and hint text. Never from an input type alone.
 * Any other explicit purpose (a password, a username, a postal code) means it is not a card field.
 */
internal fun cardField(tokens: Collection<String>, attributes: Map<String, String>, idEntry: String?, hintText: String?): CardField? {
    val explicit = tokens.map(::normalizeToken).filter { it.isNotEmpty() }
    explicit.firstNotNullOfOrNull { EXPLICIT[it] }?.let { return it }
    // A known other purpose (an e-mail, a password, a street) is never a card field. Unknown values ("off",
    // "nope", which many payment pages use to turn browser autofill off) say nothing, so names still count.
    if (explicit.any { it in OTHER_PURPOSES }) return null
    val names = listOf(attributes["name"], attributes["id"], attributes["data-elements-stable-field-name"], attributes["placeholder"],
        attributes["aria-label"], idEntry, hintText)
    return names.firstNotNullOfOrNull { it?.let(::cardFieldByName) }
}

/** Month (1..12) and four-digit year of a stored expiry such as "08/29", "8/2029" or "0829". Null when unreadable. */
internal fun parseExpiry(text: String): Pair<Int, Int>? {
    val digits = text.filter(Char::isDigit)
    val parts = text.split('/', '-', ' ', '.').map { part -> part.filter(Char::isDigit) }.filter { it.isNotEmpty() }
    val (month, year) = when {
        parts.size >= 2 -> parts[0].toIntOrNull() to parts[1].toIntOrNull()
        digits.length == 4 -> digits.take(2).toIntOrNull() to digits.drop(2).toIntOrNull()
        digits.length == 6 -> digits.take(2).toIntOrNull() to digits.drop(2).toIntOrNull()
        else -> return null
    }
    if (month == null || year == null || month !in 1..12) return null
    val fullYear = when { year in 0..99 -> 2000 + year; year in 2000..2199 -> year; else -> return null }
    return month to fullYear
}

private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** The drop-down entry for [month]: "08", "8", "August", "Aug" or "08 - August". */
internal fun monthOption(options: List<String>, month: Int): Int? = options.indexOfFirst { option ->
    val text = option.trim().lowercase(Locale.ROOT)
    val number = text.takeWhile(Char::isDigit).toIntOrNull()
    number == month || (number == null && text.startsWith(MONTH_NAMES[month - 1]))
}.takeIf { it >= 0 }

/** The drop-down entry for [year]: "2029" or "29". */
internal fun yearOption(options: List<String>, year: Int): Int? = options.indexOfFirst { option ->
    val digits = option.filter(Char::isDigit)
    digits == year.toString() || digits == (year % 100).toString().padStart(2, '0')
}.takeIf { it >= 0 }

/** "MM/YY" unless the field shows or allows another shape ("MM/YYYY", "MMYY", "MM / YY"). */
internal fun expiryText(month: Int, year: Int, spec: CardFieldSpec): String {
    val mm = month.toString().padStart(2, '0')
    val yy = (year % 100).toString().padStart(2, '0')
    val pattern = spec.pattern.lowercase(Locale.ROOT).replace(" ", "")
    return when {
        "yyyy" in pattern || spec.maxLength == 7 -> "$mm/$year"
        ("mmyy" in pattern && '/' !in pattern) || spec.maxLength == 4 -> "$mm$yy"
        else -> "$mm/$yy"
    }
}

/**
 * The values to fill: only fields the card has something for. Split number boxes (several NUMBER fields) take the
 * digits in order, each up to its length (4 when unknown). The security code is filled only when [includeSecurityCode].
 */
internal fun <K> cardFillValues(card: CardDetails, fields: Map<K, CardFieldSpec>, includeSecurityCode: Boolean): Map<K, CardValue> {
    val out = LinkedHashMap<K, CardValue>()
    val digits = card.number.filter(Char::isDigit)
    val numberFields = fields.filterValues { it.kind == CardField.NUMBER }
    if (digits.isNotEmpty()) {
        if (numberFields.size == 1) out[numberFields.keys.first()] = CardValue.Text(digits)
        else {
            var at = 0
            for ((key, spec) in numberFields) {
                if (at >= digits.length) break
                val size = if (spec.maxLength in 1..19) spec.maxLength else 4
                out[key] = CardValue.Text(digits.substring(at, minOf(digits.length, at + size)))
                at += size
            }
        }
    }
    val holder = card.holder.trim()
    val given = holder.substringBeforeLast(' ', holder).trim()
    val family = if (' ' in holder) holder.substringAfterLast(' ').trim() else ""
    val expiry = parseExpiry(card.expiry)
    for ((key, spec) in fields) {
        val value: CardValue? = when (spec.kind) {
            CardField.NUMBER -> null
            CardField.NAME -> holder.takeIf { it.isNotEmpty() }?.let { CardValue.Text(it) }
            CardField.GIVEN_NAME -> given.takeIf { it.isNotEmpty() && family.isNotEmpty() }?.let { CardValue.Text(it) }
            CardField.FAMILY_NAME -> family.takeIf { it.isNotEmpty() }?.let { CardValue.Text(it) }
            CardField.EXPIRY -> expiry?.let { (month, year) -> CardValue.Text(expiryText(month, year, spec)) }
            CardField.EXP_MONTH -> expiry?.let { (month, _) ->
                if (spec.options.isNotEmpty()) monthOption(spec.options, month)?.let { CardValue.Choice(it) }
                else CardValue.Text(if (spec.maxLength == 1) month.toString() else month.toString().padStart(2, '0'))
            }
            CardField.EXP_YEAR -> expiry?.let { (_, year) ->
                if (spec.options.isNotEmpty()) yearOption(spec.options, year)?.let { CardValue.Choice(it) }
                else CardValue.Text(if (spec.maxLength == 2 || (spec.pattern.lowercase(Locale.ROOT).trim() == "yy")) (year % 100).toString().padStart(2, '0')
                    else year.toString())
            }
            CardField.CSC -> card.securityCode.trim().takeIf { includeSecurityCode && it.isNotEmpty() }?.let { CardValue.Text(it) }
        }
        if (value != null) out[key] = value
    }
    return out
}

/** Whether a field of this autofill type can take the kind's value: drop-downs only for month and year. */
internal fun cardFieldFits(kind: CardField, autofillType: Int): Boolean = when (autofillType) {
    View.AUTOFILL_TYPE_TEXT -> true
    View.AUTOFILL_TYPE_LIST -> kind == CardField.EXP_MONTH || kind == CardField.EXP_YEAR
    else -> false
}
