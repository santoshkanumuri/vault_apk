package com.privatevault.app.autofill

import android.text.InputType
import java.util.Locale

private val WHITESPACE = Regex("\\s+")
private val NON_ALPHANUMERIC = Regex("[^a-z0-9]")
private val NON_ALPHANUMERIC_ANY_CASE = Regex("[^A-Za-z0-9]")
private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
// Qualifiers narrow a purpose ("shipping address-line1") but never are one.
private val QUALIFIERS = setOf("shipping", "billing", "webauthn")
private val TEXT_PASSWORD_VARIATIONS = setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
private val PLAIN_TEXT_VARIATIONS = setOf(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
    InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)

private val USERNAME_HINTS = setOf("username", "newusername", "emailaddress", "email")
private val USERNAME_NAMES = setOf("username", "email", "emailaddress", "login", "loginemail", "loginusername", "userid",
    "user", "loginid", "loginname", "accountname", "account", "usernameoremail", "emailorusername", "emailorphone",
    "phoneoremail", "identifier", "signinid", "jusername", "sessionusernameoremail")
private val NATIVE_USERNAME_TOKENS = listOf("username", "userid", "loginid", "login", "email", "emailaddress", "accountname",
    "accountid", "phoneoremail", "emailorphone", "mobileoremail", "emailormobile", "identifier", "signin")
private val NEGATIVE_TOKENS = listOf("search", "confirm", "verify", "otp", "code", "coupon", "promo", "message", "comment",
    "first", "last", "full", "nick", "display", "refer", "billing", "shipping")
// A "login password" box is not an account name even when its id says login.
private val SECRET_TOKENS = listOf("password", "passwd", "pwd", "passcode")

private val OTP_HINTS = setOf("2faappotpcode", "onetimecode")
private val OTP_NAMES = setOf("otp", "totp", "otpcode", "totpcode", "onetimecode", "onetimepassword", "twofactor",
    "twofactorcode", "2fa", "2facode", "mfa", "mfacode", "authcode", "authenticationcode", "authenticatorcode",
    "verificationcode", "approvalscode")
private val NATIVE_OTP_TOKENS = listOf("otp", "totp", "twofactor", "2fa", "mfa", "authenticatorcode", "verificationcode", "authcode")
private val OTP_NEGATIVE_TOKENS = NEGATIVE_TOKENS - setOf("otp", "code", "verify")

/** Lower-case alphanumerics only, so "Login-Email", "login_email" and "loginEmail" compare equal. */
internal fun normalizeToken(value: String?): String = value.orEmpty().lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "")

/** Words of a view id: "etOtp" and "et_otp" are [et, otp], while "root_password" never contains the word "otp". */
private fun idWords(value: String?): List<String> = value.orEmpty().split(CAMEL_BOUNDARY)
    .flatMap { it.lowercase(Locale.ROOT).split(NON_ALPHANUMERIC_ANY_CASE) }.filter { it.isNotEmpty() }

private fun hasIdWord(value: String?, tokens: List<String>): Boolean = idWords(value).let { words ->
    words.indices.any { words[it] in tokens || (it + 1 < words.size && words[it] + words[it + 1] in tokens) }
}

/** Autofill hints plus an HTML autocomplete value: lower-case, split on whitespace, qualifier tokens dropped. */
internal fun purposeTokens(hints: Collection<String>, autocomplete: String? = null): Set<String> {
    val tokens = LinkedHashSet<String>()
    for (source in hints + autocomplete.orEmpty()) for (token in source.lowercase(Locale.ROOT).split(WHITESPACE))
        if (token.isNotEmpty() && token !in QUALIFIERS && !token.startsWith("section-")) tokens.add(token)
    return tokens
}

/** Normalized purposes other than the "off"/"on" autocomplete switches. */
internal fun explicitPurposes(hints: Collection<String>): Set<String> =
    purposeTokens(hints).map(::normalizeToken).filter { it.isNotEmpty() && it != "off" && it != "on" }.toSet()

internal fun hasOtpHint(hints: Collection<String>): Boolean = purposeTokens(hints).any { normalizeToken(it) in OTP_HINTS }

/** Password-like input types. 0x10 is NUMBER_VARIATION_PASSWORD only in the number class (TEXT_VARIATION_URI elsewhere). */
internal fun isPasswordInputType(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER)
        variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD else variation in TEXT_PASSWORD_VARIATIONS
}

/** Single-line, non-password text input; [plainOnly] limits it to the variations an account name is normally typed in. */
internal fun isNativeTextInput(inputType: Int, plainOnly: Boolean): Boolean {
    if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT || isPasswordInputType(inputType) ||
        inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false
    return !plainOnly || inputType and InputType.TYPE_MASK_VARIATION in PLAIN_TEXT_VARIATIONS
}

/** True when any of the texts contains a token that rules a field out (search box, confirmation, code, ...). */
internal fun hasNegativeToken(texts: List<String?>, negatives: List<String> = NEGATIVE_TOKENS + SECRET_TOKENS): Boolean =
    texts.map(::normalizeToken).any { text -> text.isNotEmpty() && negatives.any { it in text } }

/** Recognize account identifiers without guessing from arbitrary text fields or their values. */
internal fun isUsernameField(hints: Set<String>, attributes: Map<String, String>, emailInput: Boolean, browser: Boolean): Boolean {
    val purposes = purposeTokens(hints).map(::normalizeToken).filter { it.isNotEmpty() }
    val type = attributes["type"].orEmpty().trim().lowercase(Locale.ROOT)
    if (browser && type !in setOf("", "text", "email")) return false
    if (purposes.any { it in USERNAME_HINTS } || emailInput || type == "email") return true
    // An explicit purpose such as one-time-code, name or telephone takes precedence.
    if (!browser || purposes.any { it != "off" && it != "on" }) return false
    return listOfNotNull(attributes["name"], attributes["id"]).any { normalizeToken(it) in USERNAME_NAMES }
}

/** Native views carry no autocomplete token: accept an id or hint that clearly names the account and nothing else. */
internal fun nativeUsernameField(hints: Set<String>, idEntry: String?, hintText: String?, inputType: Int): Boolean {
    if (!isNativeTextInput(inputType, plainOnly = false) || explicitPurposes(hints).isNotEmpty()) return false
    val texts = listOf(idEntry, hintText)
    return !hasNegativeToken(texts) && texts.map(::normalizeToken).any { text -> NATIVE_USERNAME_TOKENS.any { it in text } }
}

/**
 * A code field named by the page or view rather than by an autocomplete hint. Never SMS or e-mail OTP hints.
 * A native id normally only has to contain the name; [wholeWord] asks for it to stand as a word, which callers use
 * when the view is masked like a password ("root_password" must not read as a code).
 */
internal fun isOtpFieldByName(hints: Set<String>, attributes: Map<String, String>, idEntry: String?, hintText: String?,
    inputType: Int, html: Boolean, wholeWord: Boolean = false): Boolean {
    if (explicitPurposes(hints).isNotEmpty()) return false
    if (html) {
        val type = attributes["type"].orEmpty().trim().lowercase(Locale.ROOT)
        return type in setOf("", "text", "tel", "number", "password") &&
            listOfNotNull(attributes["name"], attributes["id"]).any { normalizeToken(it) in OTP_NAMES }
    }
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    if (inputClass != InputType.TYPE_CLASS_TEXT && inputClass != InputType.TYPE_CLASS_NUMBER) return false
    if (inputClass == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false
    val named = if (wholeWord) hasIdWord(idEntry, NATIVE_OTP_TOKENS) else normalizeToken(idEntry).let { id -> NATIVE_OTP_TOKENS.any { it in id } }
    return named && !hasNegativeToken(listOf(idEntry, hintText), OTP_NEGATIVE_TOKENS)
}
