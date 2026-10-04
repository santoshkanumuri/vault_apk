package com.privatevault.app.autofill

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class UsernameFieldRulesTest {
    private fun browserName(name: String, vararg hints: String) =
        isUsernameField(hints.toSet(), mapOf("name" to name, "type" to "text"), false, true)

    @Test fun extendedExactNamesAreUsernames() {
        listOf("user", "loginid", "loginname", "accountname", "account", "usernameoremail", "emailorusername", "emailorphone",
            "phoneoremail", "identifier", "signinid", "jusername", "sessionusernameoremail", "Login_ID", "j_username",
            "session[username_or_email]", "email-or-phone").forEach { assertTrue(it, browserName(it)) }
    }

    @Test fun lookalikesAndPartialNamesAreNotUsernames() {
        listOf("users", "username_confirmation", "billing_email", "newsletter", "account_number", "accountholder", "identifier_type",
            "user_id_hint", "name", "firstname", "search", "otp", "phone").forEach { assertFalse(it, browserName(it)) }
    }

    @Test fun hintsAreCaseInsensitiveAndQualifiersAreIgnored() {
        assertTrue(isUsernameField(setOf("Username"), emptyMap(), false, false))
        assertTrue(isUsernameField(setOf("EMAILADDRESS"), emptyMap(), false, false))
        assertTrue(isUsernameField(setOf("section-login username webauthn"), emptyMap(), false, true))
        // webauthn alone is a qualifier, so the name decides.
        assertTrue(isUsernameField(setOf("webauthn"), mapOf("name" to "username"), false, true))
        assertTrue(isUsernameField(setOf("section-x", "shipping", "billing"), mapOf("id" to "email"), false, true))
        assertFalse(isUsernameField(setOf("section-x one-time-code"), mapOf("id" to "email2"), false, true))
        assertFalse(isUsernameField(setOf("Tel"), mapOf("name" to "username"), false, true))
    }

    @Test fun htmlTypeStillGatesBrowserNames() {
        listOf("hidden", "number", "tel", "date", "checkbox", "submit").forEach {
            assertFalse(it, isUsernameField(emptySet(), mapOf("name" to "username", "type" to it), false, true))
        }
        assertTrue(isUsernameField(emptySet(), mapOf("name" to "username", "type" to "EMAIL"), false, true))
        assertTrue(isUsernameField(emptySet(), mapOf("name" to "username"), false, true))
    }

    @Test fun nativeUsernameByViewIdOrHintText() {
        fun native(entry: String? = null, hint: String? = null, type: Int = InputType.TYPE_CLASS_TEXT, hints: Set<String> = emptySet()) =
            nativeUsernameField(hints, entry, hint, type)
        listOf("username", "user_id", "login_id", "login", "email", "email_address", "account_name", "account_id", "phone_or_email",
            "email_or_phone", "mobile_or_email", "email_or_mobile", "identifier", "sign_in_name", "etLoginUser", "txtUserName").forEach {
            assertTrue(it, native(entry = it))
        }
        listOf("Username", "Email address", "Email or phone", "Enter your login ID", "Account name", "Sign in with email").forEach {
            assertTrue(it, native(hint = it))
        }
        listOf("search_username", "confirm_email", "verify_email", "otp_login", "login_code", "coupon", "promo_email", "message_email",
            "comment", "first_name", "last_name", "full_name_email", "nick_login", "display_name", "referral_email", "billing_email",
            "shipping_email", "login_password", "email_pwd").forEach {
            assertFalse(it, native(entry = it))
            assertFalse(it, native(hint = it))
        }
        listOf(null, "", "field_1", "name", "phone", "address").forEach { assertFalse("$it", native(entry = it, hint = it)) }
    }

    @Test fun nativeUsernameNeedsASingleLineNonPasswordTextInput() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(nativeUsernameField(emptySet(), "username", null, text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
        assertTrue(nativeUsernameField(emptySet(), "username", null, text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT))
        assertFalse(nativeUsernameField(emptySet(), "username", null, text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(nativeUsernameField(emptySet(), "username", null, text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(nativeUsernameField(emptySet(), "username", null, text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(nativeUsernameField(emptySet(), "username", null, text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(nativeUsernameField(emptySet(), "username", null, InputType.TYPE_CLASS_NUMBER))
        assertFalse(nativeUsernameField(emptySet(), "username", null, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(nativeUsernameField(emptySet(), "username", null, InputType.TYPE_CLASS_PHONE))
        assertFalse(nativeUsernameField(emptySet(), "username", null, 0))
        assertFalse(nativeUsernameField(setOf("name"), "username", null, text))
        assertTrue(nativeUsernameField(setOf("off"), "username", null, text))
    }

    @Test fun passwordInputTypesDependOnTheClass() {
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        // 0x10 is a URI in the text class and a password only in the number class.
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_PHONE))
        assertFalse(isPasswordInputType(0))
    }

    @Test fun otpNamesForBrowsersAreExact() {
        fun html(name: String, type: String = "text", vararg hints: String) =
            isOtpFieldByName(hints.toSet(), mapOf("name" to name, "type" to type), null, null, 0, true)
        listOf("otp", "totp", "otpcode", "totpcode", "onetimecode", "onetimepassword", "twofactor", "twofactorcode", "2fa", "2facode",
            "mfa", "mfacode", "authcode", "authenticationcode", "authenticatorcode", "verificationcode", "approvalscode",
            "OTP_Code", "two-factor-code").forEach { assertTrue(it, html(it)) }
        listOf("code", "otp1", "otp_hint", "pin", "token", "sms_code", "emailcode", "zip").forEach { assertFalse(it, html(it)) }
        listOf("hidden", "checkbox", "email", "submit", "search").forEach { assertFalse(it, html("otp", it)) }
        listOf("text", "tel", "number", "password").forEach { assertTrue(it, html("otp", it)) }
        assertFalse(html("otp", "text", "postal-code"))
        assertTrue(html("otp", "text", "off"))
    }

    @Test fun otpNamesForNativeViewsAreContainedButVetoed() {
        fun native(entry: String?, hint: String? = null, type: Int = InputType.TYPE_CLASS_NUMBER, hints: Set<String> = emptySet()) =
            isOtpFieldByName(hints, emptyMap(), entry, hint, type, false)
        listOf("otp", "et_otp", "totp_input", "two_factor_code", "etTwoFactor", "2fa", "mfa_field", "authenticator_code",
            "verification_code", "auth_code").forEach { assertTrue(it, native(it)) }
        listOf(null, "", "code", "pin", "sms_code", "zip", "password").forEach { assertFalse("$it", native(it)) }
        listOf("search_otp", "confirm_otp", "coupon_otp", "promo_otp", "message_otp", "comment_otp", "first_otp", "last_otp",
            "full_otp", "nick_otp", "display_otp", "refer_otp", "billing_otp", "shipping_otp").forEach { assertFalse(it, native(it)) }
        assertFalse(native("otp", hint = "Search"))
        assertTrue(native("otp", type = InputType.TYPE_CLASS_TEXT))
        assertTrue(native("otp", type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(native("otp", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(native("otp", type = InputType.TYPE_CLASS_PHONE))
        assertFalse(native("otp", hints = setOf("postalCode")))
    }

    @Test fun otpHintsAreCaseInsensitiveAndNeverSms() {
        listOf("2faAppOTPCode", "2FAAPPOTPCODE", "oneTimeCode", "one-time-code", "ONE-TIME-CODE", "section-a one-time-code").forEach {
            assertTrue(it, hasOtpHint(setOf(it)))
        }
        listOf("smsOTPCode", "emailOTPCode", "password", "code", "otp", "").forEach { assertFalse(it, hasOtpHint(setOf(it))) }
    }

    @Test fun purposeTokensSplitLowercaseAndDropQualifiers() {
        assertEquals(setOf("address-line1"), purposeTokens(setOf("Shipping  ADDRESS-line1")))
        assertEquals(setOf("username", "off"), purposeTokens(setOf("section-blue username"), "off webauthn"))
        assertEquals(emptySet<String>(), purposeTokens(emptySet(), null))
        assertEquals(setOf("name"), explicitPurposes(setOf("billing", "NAME", "off", "on")))
        assertEquals("loginemail", normalizeToken("Login-E_mail"))
        assertEquals("", normalizeToken(null))
    }

    @Test fun negativeTokensMatchByContainment() {
        assertTrue(hasNegativeToken(listOf(null, "search_field")))
        assertTrue(hasNegativeToken(listOf("Confirm e-mail")))
        assertTrue(hasNegativeToken(listOf("login_password")))
        assertFalse(hasNegativeToken(listOf(null, "", "username")))
        assertFalse(hasNegativeToken(emptyList()))
    }
}
