package com.privatevault.app.autofill

import android.view.View
import java.util.Locale

/** A payment-card field this form asks for. */
internal enum class CardField { NUMBER, NAME, GIVEN_NAME, FAMILY_NAME, EXPIRY, EXP_MONTH, EXP_YEAR, CSC, TYPE }

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

/** What a stored card can fill. [expiry] is the card's "MM/YY" text as saved; [network] its saved network ("Visa"), if any. */
internal data class CardDetails(val number: String, val holder: String, val expiry: String, val securityCode: String,
    val network: String = "")

// Explicit purposes (Android hints and HTML autocomplete tokens), normalized: lowercase letters and digits only.
private val EXPLICIT = mapOf(
    "ccnumber" to CardField.NUMBER, "creditcardnumber" to CardField.NUMBER,
    "ccname" to CardField.NAME, "ccgivenname" to CardField.GIVEN_NAME, "ccfamilyname" to CardField.FAMILY_NAME,
    "ccexp" to CardField.EXPIRY, "creditcardexpirationdate" to CardField.EXPIRY,
    "ccexpmonth" to CardField.EXP_MONTH, "creditcardexpirationmonth" to CardField.EXP_MONTH,
    "ccexpyear" to CardField.EXP_YEAR, "creditcardexpirationyear" to CardField.EXP_YEAR,
    "cccsc" to CardField.CSC, "creditcardsecuritycode" to CardField.CSC,
    "cctype" to CardField.TYPE,
)

/** Recognized purposes that are not card fields (HTML autocomplete names and Android hints, normalized). */
private val OTHER_PURPOSES = setOf("name", "givenname", "familyname", "additionalname", "nickname", "email", "emailaddress", "tel",
    "phone", "phonenumber", "username", "newusername", "password", "currentpassword", "newpassword", "onetimecode", "smsotpcode",
    "streetaddress", "addressline1", "addressline2", "addressline3", "addresslevel1", "addresslevel2", "addresslevel3",
    "addresslevel4", "postalcode", "postaladdress", "country", "countryname", "organization", "organizationtitle", "bday",
    "bdayday", "bdaymonth", "bdayyear", "url", "personname", "personnamegiven", "personnamefamily",
    "ccadditionalname", "transactionamount", "transactioncurrency")

/** Cards of other kinds share the words "card number"; Nuvori stores payment cards only. */
private val NOT_PAYMENT = listOf("gift", "loyalty", "member", "reward", "club", "voucher", "coupon", "promo", "library", "idcard",
    "identity", "insurance", "aadhaar", "pancard", "simcard")

/** Words that make a "credit card ..." or "debit card ..." field something other than its number: a PIN, a nickname, an amount. */
private val NOT_NUMBER = listOf("pin", "nickname", "amount", "bill", "limit", "balance", "emi", "otp", "password", "holder", "name",
    "type", "brand", "last4", "lastfour")

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
        // One box for both ("Expiration date (month/year)", "MM / YY") is the whole expiry, not its month.
        (has(n, "month") && has(n, "year") && cardContext) || has(n, "mmyy", "mmyyyy") -> CardField.EXPIRY
        (has(n, "month") || n.endsWith("mm") || n == "mm") && (cardContext || n == "mm") -> CardField.EXP_MONTH
        (has(n, "year") || n.endsWith("yyyy") || n.endsWith("yy") || n == "yy") && (cardContext || n == "yy" || n == "yyyy") -> CardField.EXP_YEAR
        has(n, "expiry", "expiration", "expdate", "ccexp", "cardexp", "validthru", "validuntil", "validtill", "validupto", "validto") ||
            n == "exp" || n == "expires" -> CardField.EXPIRY
        has(n, "cardholder", "nameoncard", "nameasoncard", "ccname", "holdername", "cardowner", "cardname") -> CardField.NAME
        // The network ("card type", "card brand"): filled only from a drop-down entry or box that names the card's network.
        has(n, "cardtype", "cctype", "cardbrand", "ccbrand", "cardnetwork") -> CardField.TYPE
        has(n, "cardnumber", "ccnumber", "ccnum", "cardno", "cardnum") || n == "pan" -> CardField.NUMBER
        // "Credit card" alone is the number; "Debit card PIN" or "Credit card nickname" is not.
        has(n, "creditcard", "debitcard") && NOT_NUMBER.none { it in n } -> CardField.NUMBER
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

/** The "MM / YY" part of a field's placeholder or hint: the separator between month and year, and the year's digits. */
private val EXPIRY_PATTERN = Regex("""mm(\s*[/\-.]?\s*)(yyyy|yy)""")

/**
 * The expiry in the shape the field shows: its own pattern ("MM / YY", "MM-YYYY", "MMYY") when it has one, with the
 * same separator and spaces, because payment pages reformat what they get and "08/2029" in an "MM / YY" box reads as
 * 08 / 20. Without a pattern, the length limit decides: 4 is "MMYY", 7 is "MM/YYYY", anything else "MM/YY".
 */
internal fun expiryText(month: Int, year: Int, spec: CardFieldSpec): String {
    val mm = month.toString().padStart(2, '0')
    val yy = (year % 100).toString().padStart(2, '0')
    EXPIRY_PATTERN.find(spec.pattern.lowercase(Locale.ROOT))?.let { match ->
        val (separator, yearShape) = match.destructured
        val text = mm + separator + if (yearShape == "yyyy") year.toString() else yy
        // A limit shorter than the pattern drops the spaces first ("MM / YY" in a 5-character box).
        return if (spec.maxLength > 0 && text.length > spec.maxLength) text.filterNot(Char::isWhitespace) else text
    }
    return when (spec.maxLength) {
        4 -> "$mm$yy"
        7 -> "$mm/$year"
        else -> "$mm/$yy"
    }
}

/** Whether a year box takes two digits: its hint says "YY" (not "YYYY"), or it holds only two characters. */
private fun twoDigitYear(spec: CardFieldSpec): Boolean {
    val pattern = spec.pattern.lowercase(Locale.ROOT)
    return spec.maxLength == 2 || ("yy" in pattern && "yyyy" !in pattern)
}

/**
 * The number boxes of a form that splits the card number (4 boxes of 4, Amex 4-6-5): two to six number fields that
 * each take at most 6 characters, or three to five with no known limit. Anything else (a number and a "Confirm card
 * number" box, say) gets the whole number in each box.
 */
private fun splitBoxes(numberFields: Collection<CardFieldSpec>): Boolean =
    (numberFields.size in 2..6 && numberFields.all { it.maxLength in 1..6 }) ||
        (numberFields.size in 3..5 && numberFields.all { it.maxLength <= 0 })

/**
 * The values to fill: only fields the card has something for. Split number boxes ([splitBoxes]) take the digits in
 * order, each up to its length (4 when unknown); any other number field gets the whole number. The security code is
 * filled only when [includeSecurityCode].
 */
internal fun <K> cardFillValues(card: CardDetails, fields: Map<K, CardFieldSpec>, includeSecurityCode: Boolean): Map<K, CardValue> {
    val out = LinkedHashMap<K, CardValue>()
    val digits = card.number.filter(Char::isDigit)
    val numberFields = fields.filterValues { it.kind == CardField.NUMBER }
    if (digits.isNotEmpty()) {
        if (splitBoxes(numberFields.values)) {
            var at = 0
            for ((key, spec) in numberFields) {
                if (at >= digits.length) break
                val size = if (spec.maxLength in 1..6) spec.maxLength else 4
                out[key] = CardValue.Text(digits.substring(at, minOf(digits.length, at + size)))
                at += size
            }
        } else {
            // A box too short for the number (a stray 4-character field beside the real one) is left alone.
            for ((key, spec) in numberFields) if (spec.maxLength <= 0 || spec.maxLength >= digits.length) out[key] = CardValue.Text(digits)
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
                else CardValue.Text(if (twoDigitYear(spec)) (year % 100).toString().padStart(2, '0') else year.toString())
            }
            CardField.CSC -> card.securityCode.trim().takeIf { includeSecurityCode && it.isNotEmpty() }?.let { CardValue.Text(it) }
            CardField.TYPE -> card.network.trim().ifEmpty { com.privatevault.app.nfc.cardNetwork(digits) }.takeIf { it.isNotEmpty() }?.let { network ->
                if (spec.options.isNotEmpty()) networkOption(spec.options, network)?.let { CardValue.Choice(it) }
                else CardValue.Text(network)
            }
        }
        if (value != null) out[key] = value
    }
    return out
}

/** Whether a field of this autofill type can take the kind's value: drop-downs only for month and year. */
internal fun cardFieldFits(kind: CardField, autofillType: Int): Boolean = when (autofillType) {
    View.AUTOFILL_TYPE_TEXT -> true
    View.AUTOFILL_TYPE_LIST -> kind == CardField.EXP_MONTH || kind == CardField.EXP_YEAR || kind == CardField.TYPE
    else -> false
}

/** Ways a form names each network, normalized: "Master Card" and "MC" are Mastercard. */
private val NETWORK_NAMES = mapOf(
    "visa" to setOf("visa", "vi"),
    "mastercard" to setOf("mastercard", "mc", "master"),
    "americanexpress" to setOf("americanexpress", "amex", "ax", "ae"),
    "discover" to setOf("discover", "disc", "di"),
    "jcb" to setOf("jcb"),
    "dinersclub" to setOf("dinersclub", "diners", "dc"),
    "unionpay" to setOf("unionpay", "cup", "up"),
    "maestro" to setOf("maestro"),
    "rupay" to setOf("rupay"),
)

/** The drop-down entry for [network] ("Visa", "Mastercard"), by name or common code. Null when none or several fit. */
internal fun networkOption(options: List<String>, network: String): Int? {
    val key = network.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    val names = NETWORK_NAMES.entries.firstOrNull { (canonical, aliases) -> key == canonical || key in aliases }?.value ?: setOf(key)
    if (key.isEmpty()) return null
    val normalized = options.map { it.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit) }
    normalized.indices.filter { normalized[it] in names }.singleOrNull()?.let { return it }
    // A longer label ("Visa Debit", "MasterCard (credit)"): the full name inside it, never a two-letter code.
    return normalized.indices.filter { index -> names.any { it.length >= 4 && normalized[index].contains(it) } }.singleOrNull()
}
