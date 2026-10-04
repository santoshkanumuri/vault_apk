package com.privatevault.app.autofill

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class LoginFormClassifierTest {
    private fun login(vararg roots: FormNode, browser: Boolean = false) = classifyLoginForm(roots.toList(), browser)

    // ---- native apps -----------------------------------------------------------------------------------------------

    @Test fun nativeUsernameRecognizedByViewIdAlone() {
        val form = login(appScreen(appInput(0, idEntry = "login_username"), appInput(1, PASSWORD_INPUT, idEntry = "pass")))!!
        assertEquals(0, form.username); assertEquals(1, form.password)
        assertNull(form.otp); assertNull(form.origin); assertTrue(form.newPasswords.isEmpty()); assertFalse(form.embeddedWebView)
    }

    @Test fun nativeUsernameRecognizedByHintText() {
        assertEquals(0, login(appScreen(appInput(0, hint = "Email or phone"), appInput(1, PASSWORD_INPUT)))!!.username)
        assertEquals(0, login(appScreen(appInput(0, hint = "Enter your user ID"), appInput(1, PASSWORD_INPUT)))!!.username)
        assertEquals(0, login(appScreen(appInput(0, idEntry = "et_account_name"), appInput(1, PASSWORD_INPUT)))!!.username)
    }

    @Test fun nativeNegativeTokensVetoUsernameAndFallback() {
        listOf("confirm_email" to null, "search_login" to null, null to "Promo code", null to "First name", null to "Billing email",
            "login_password_box" to null).forEach { (entry, hint) ->
            val form = login(appScreen(appInput(0, idEntry = entry, hint = hint), appInput(1, PASSWORD_INPUT)))!!
            assertNull("$entry/$hint", form.username)
            assertEquals(1, form.password)
        }
        // An e-mail typed box named as a confirmation is not the account either.
        assertNull(login(appScreen(appInput(0, EMAIL_INPUT, idEntry = "confirm_email"), appInput(1, PASSWORD_INPUT)))!!.username)
        assertEquals(0, login(appScreen(appInput(0, EMAIL_INPUT), appInput(1, PASSWORD_INPUT)))!!.username)
    }

    @Test fun nativeUsernameNeedsPlainSingleLineText() {
        val multiline = TEXT_INPUT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertNull(login(appScreen(appInput(0, multiline, idEntry = "username"), appInput(1, PASSWORD_INPUT)))!!.username)
        assertNull(login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER, idEntry = "username"), appInput(1, PASSWORD_INPUT)))!!.username)
        assertNull(login(appScreen(appInput(0, InputType.TYPE_CLASS_PHONE, idEntry = "username"), appInput(1, PASSWORD_INPUT)))!!.username)
    }

    @Test fun nativeExplicitOtherPurposeStopsTextGuessing() {
        val form = login(appScreen(appInput(0, idEntry = "username", hints = listOf("name")), appInput(1, PASSWORD_INPUT)))!!
        assertNull(form.username)
    }

    @Test fun lonePasswordFallsBackToNearestPrecedingTextInput() {
        val form = login(appScreen(appInput(0, idEntry = "field_one"), appInput(1, idEntry = "field_two", hint = "Enter ID"),
            appLabel("Password"), appInput(2, PASSWORD_INPUT)))!!
        assertEquals(1, form.username); assertEquals(2, form.password)
    }

    @Test fun fallbackSkipsInvisibleDisabledAndNonInputs() {
        val form = login(appScreen(appInput(0, idEntry = "field_one"), appInput(1, visible = false), appInput(2, enabled = false),
            appLabel("x"), appInput(3, PASSWORD_INPUT)))!!
        assertEquals(0, form.username)
    }

    @Test fun fallbackStopsAtNearestInputThatIsNotPlain() {
        // The nearest input is a phone box: do not look past it for something older.
        assertNull(login(appScreen(appInput(0, idEntry = "field_one"), appInput(1, InputType.TYPE_CLASS_PHONE),
            appInput(2, PASSWORD_INPUT)))!!.username)
        assertNull(login(appScreen(appInput(0, idEntry = "field_one"), appInput(1, TEXT_INPUT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            appInput(2, PASSWORD_INPUT)))!!.username)
        assertNull(login(appScreen(appInput(0, idEntry = "field_one"), appInput(1, TEXT_INPUT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            appInput(2, PASSWORD_INPUT)))!!.username)
    }

    @Test fun fallbackRefusesFieldsWithAnotherPurpose() {
        listOf(listOf("name"), listOf("tel"), listOf("address-line1"), listOf("cc-number"),
            listOf("postalCode")).forEach { hints ->
            assertNull("$hints", login(appScreen(appInput(0, hints = hints), appInput(1, PASSWORD_INPUT)))!!.username)
        }
        listOf("search_box", "coupon", "message", "comment_text").forEach { entry ->
            assertNull(entry, login(appScreen(appInput(0, idEntry = entry), appInput(1, PASSWORD_INPUT)))!!.username)
        }
    }

    @Test fun fallbackIsLimitedToTheSameWindow() {
        val form = login(appScreen(appInput(0, idEntry = "field_one")), appScreen(appInput(1, PASSWORD_INPUT)))!!
        assertNull(form.username); assertEquals(1, form.password)
    }

    @Test fun severalNativeCandidatesPickTheNearestBeforeThePassword() {
        val form = login(appScreen(appInput(0, idEntry = "email"), appInput(1, idEntry = "username"), appInput(2, PASSWORD_INPUT)))!!
        assertEquals(1, form.username)
    }

    @Test fun usernameAfterThePasswordIsUsedOnlyWhenUnique() {
        assertEquals(2, login(appScreen(appInput(0, PASSWORD_INPUT), appInput(2, idEntry = "username")))!!.username)
        assertNull(login(appScreen(appInput(0, PASSWORD_INPUT), appInput(1, idEntry = "username"), appInput(2, idEntry = "email")))!!.username)
    }

    @Test fun visiblePasswordVariationIsAPassword() {
        val visible = TEXT_INPUT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val form = login(appScreen(appInput(0, idEntry = "username"), appInput(1, visible)))!!
        assertEquals(1, form.password); assertEquals(0, form.username)
    }

    @Test fun numericPinIsAPasswordOnlyInTheNumberClass() {
        val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertEquals(0, login(appScreen(appInput(0, pin)))!!.password)
        // 0x10 is TEXT_VARIATION_URI in the text class.
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI)
        assertNull(login(appScreen(appInput(0, TEXT_INPUT or InputType.TYPE_TEXT_VARIATION_URI))))
        assertNull(login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER))))
    }

    @Test fun webPasswordVariationIsStillAPassword() {
        assertEquals(0, login(appScreen(appInput(0, TEXT_INPUT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))!!.password)
    }

    @Test fun nativeHintsAreMatchedCaseInsensitively() {
        val form = login(appScreen(appInput(0, hints = listOf("USERNAME")), appInput(1, hints = listOf("Password"))))!!
        assertEquals(0, form.username); assertEquals(1, form.password)
        assertEquals(0, login(appScreen(appInput(0, hints = listOf("CURRENT-PASSWORD"))))!!.password)
        assertEquals(0, login(appScreen(appInput(0, hints = listOf("2FAAPPOTPCODE"))))!!.otp)
    }

    @Test fun nativeExplicitNewPasswordOrAccountHintIsUnsafe() {
        listOf("newPassword", "new-password", "NEWPASSWORD", "newUsername").forEach {
            assertNull(it, login(appScreen(appInput(0, hints = listOf(it)), appInput(1, PASSWORD_INPUT))))
        }
        // Even an invisible node carrying the hint makes the form unsafe.
        assertNull(login(appScreen(appInput(0, hints = listOf("newPassword"), visible = false), appInput(1, PASSWORD_INPUT))))
    }

    @Test fun nativeAppsNeverGuessNewVersusCurrentPasswords() {
        assertNull(login(appScreen(appInput(0, PASSWORD_INPUT, idEntry = "current"), appInput(1, PASSWORD_INPUT, idEntry = "new"))))
        assertNull(login(appScreen(appInput(0, PASSWORD_INPUT), appInput(1, PASSWORD_INPUT), appInput(2, PASSWORD_INPUT))))
        assertEquals(0, login(appScreen(appInput(0, PASSWORD_INPUT, idEntry = "new_password")))!!.password)
    }

    @Test fun nativeUsernameWithoutPasswordIsNotAForm() {
        assertNull(login(appScreen(appInput(0, idEntry = "username"))))
        assertNull(login(appScreen(appInput(0, EMAIL_INPUT))))
    }

    @Test fun nativeOtpByHintAndByViewId() {
        val byHint = login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER, hints = listOf("2faAppOTPCode"))))!!
        assertEquals(0, byHint.otp); assertNull(byHint.password)
        assertEquals(0, login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER, idEntry = "et_otp")))!!.otp)
        assertEquals(0, login(appScreen(appInput(0, TEXT_INPUT, idEntry = "verification_code_input")))!!.otp)
        assertEquals(0, login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, idEntry = "totp")))!!.otp)
    }

    @Test fun nativeOtpIgnoresSmsHintsAndNegativeNames() {
        assertNull(login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER, hints = listOf("smsOTPCode")))))
        assertNull(login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER, hints = listOf("emailOTPCode")))))
        assertNull(login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER, idEntry = "search_otp"))))
        assertNull(login(appScreen(appInput(0, InputType.TYPE_CLASS_NUMBER, idEntry = "promo_otp"))))
        assertNull(login(appScreen(appInput(0, TEXT_INPUT, idEntry = "otp", hints = listOf("postalCode")))))
    }

    @Test fun maskedNativeFieldsAreCodesOnlyWhenTheIdIsAWord() {
        val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        listOf("et_otp", "etOtp", "otp", "totp_field", "two_factor", "etTwoFactor", "verification_code").forEach {
            assertEquals(it, 0, login(appScreen(appInput(0, pin, idEntry = it)))?.otp)
        }
        // "otp" hides inside these ids; a password-masked box with such an id is still a password.
        listOf("root_password", "hot_password", "pilot_pin", "etotp", "spotpicker").forEach {
            val form = login(appScreen(appInput(0, pin, idEntry = it)))!!
            assertNull(it, form.otp); assertEquals(it, 0, form.password)
        }
        // An unmasked text box keeps the plain containment rule.
        assertEquals(0, login(appScreen(appInput(0, TEXT_INPUT, idEntry = "etotp")))!!.otp)
    }

    @Test fun otpMixedWithPasswordOrUsernameInTheSameFormIsRefused() {
        assertNull(login(appScreen(appInput(0, hints = listOf("2faAppOTPCode")), appInput(1, PASSWORD_INPUT))))
        assertNull(login(appScreen(appInput(0, hints = listOf("2faAppOTPCode")), appInput(1, idEntry = "username"))))
        assertNull(login(appScreen(appInput(0, hints = listOf("2faAppOTPCode")), appInput(1, hints = listOf("2faAppOTPCode")))))
    }

    @Test fun invisibleOrDisabledFieldsAreIgnored() {
        val form = login(appScreen(appInput(0, PASSWORD_INPUT, visible = false), appInput(1, PASSWORD_INPUT, enabled = false),
            appInput(2, PASSWORD_INPUT)))!!
        assertEquals(2, form.password)
        // A hidden parent hides its children.
        val hidden = FormNode(null, visible = false, children = listOf(appInput(0, PASSWORD_INPUT)))
        assertNull(login(appScreen(hidden)))
    }

    // ---- embedded web views ---------------------------------------------------------------------------------------

    @Test fun embeddedWebViewFormFillsWithoutAnOrigin() {
        val form = login(appScreen(webPage(htmlForm(htmlInput(0, autocomplete = "username"),
            htmlInput(1, type = "password", autocomplete = "current-password")), domain = "fill.dev")))!!
        assertEquals(0, form.username); assertEquals(1, form.password)
        assertTrue(form.embeddedWebView); assertNull(form.origin)
    }

    @Test fun embeddedWebViewWithTwoOriginsIsRefused() {
        val form = htmlForm(htmlInput(0, autocomplete = "username"), htmlInput(1, type = "password"))
        assertNull(login(appScreen(webPage(form, domain = "a.example.com"), webPage(domain = "b.example.com"))))
        assertNull(login(appScreen(webPage(form, domain = "fill.dev", scheme = "http"))))
    }

    @Test fun embeddedWebViewNewPasswordHintIsUnsafe() {
        assertNull(login(appScreen(webPage(htmlForm(htmlInput(0, type = "password", autocomplete = "new-password"))))))
    }

    @Test fun embeddedWebViewKeepsOnlyTheCredentialForm() {
        val form = login(appScreen(webPage(htmlForm(htmlInput(0, type = "email", name = "newsletter")),
            htmlForm(htmlInput(1, name = "username"), htmlInput(2, type = "password")))))!!
        assertEquals(1, form.username); assertEquals(2, form.password)
    }

    @Test fun embeddedWebViewUsernameOnlyNeedsOneCandidate() {
        assertEquals(0, login(appScreen(webPage(htmlForm(htmlInput(0, name = "username")))))!!.username)
        assertNull(login(appScreen(webPage(htmlForm(htmlInput(0, name = "username")), htmlForm(htmlInput(1, type = "email"))))))
    }

    // ---- trusted browsers ----------------------------------------------------------------------------------------

    @Test fun loginFormIsChosenOverASearchAndNewsletterForm() {
        val page = webPage(
            htmlForm(htmlInput(0, type = "search", name = "q")),
            htmlForm(htmlInput(1, type = "email", name = "newsletter")),
            htmlForm(htmlInput(2, name = "username"), htmlInput(3, type = "password", name = "password")))
        val form = login(page, browser = true)!!
        assertEquals(2, form.username); assertEquals(3, form.password)
        assertEquals("https://example.com", form.origin); assertFalse(form.embeddedWebView)
    }

    @Test fun nearbyFieldsOutsideTheCredentialFormAreNeverUsed() {
        val page = webPage(htmlInput(0, name = "username"), htmlForm(htmlInput(1, type = "password")))
        val form = login(page, browser = true)!!
        assertNull(form.username); assertEquals(1, form.password)
    }

    @Test fun twoFormsWithPasswordsAreResolvedByFocus() {
        fun page(focus: Int?) = webPage(
            htmlForm(htmlInput(0, name = "username", focused = focus == 0), htmlInput(1, type = "password", focused = focus == 1)),
            htmlForm(htmlInput(2, name = "email", focused = focus == 2), htmlInput(3, type = "password", name = "pw", focused = focus == 3)))
        assertNull(login(page(null), browser = true))
        login(page(1), browser = true)!!.let { assertEquals(0, it.username); assertEquals(1, it.password) }
        login(page(3), browser = true)!!.let { assertEquals(2, it.username); assertEquals(3, it.password) }
        // The focus may sit on the username box instead of the password box.
        login(page(2), browser = true)!!.let { assertEquals(2, it.username); assertEquals(3, it.password) }
        // Focus on something outside both forms decides nothing.
        assertNull(login(webPage(htmlInput(9, focused = true),
            htmlForm(htmlInput(1, type = "password")), htmlForm(htmlInput(3, type = "password"))), browser = true))
    }

    @Test fun passwordAndCodeFormsTogetherNeedFocus() {
        val unfocused = webPage(htmlForm(htmlInput(0, type = "password")), htmlForm(htmlInput(1, name = "otp")))
        assertNull(login(unfocused, browser = true))
        val focused = webPage(htmlForm(htmlInput(0, type = "password")), htmlForm(htmlInput(1, name = "otp", focused = true)))
        assertEquals(1, login(focused, browser = true)!!.otp)
    }

    @Test fun registrationIsRecognizedFromAConfirmationFieldName() {
        val form = login(webPage(htmlForm(htmlInput(0, type = "email", name = "email"),
            htmlInput(1, type = "password", name = "password"),
            htmlInput(2, type = "password", name = "password_confirmation"))), browser = true)!!
        assertNull(form.password); assertEquals(listOf(1, 2), form.newPasswords); assertEquals(0, form.username)
    }

    @Test fun registrationFromExplicitHintsAndEveryNewWord() {
        val explicit = login(webPage(htmlForm(htmlInput(0, type = "password", autocomplete = "new-password"),
            htmlInput(1, type = "password", autocomplete = "new-password"))), browser = true)!!
        assertEquals(listOf(0, 1), explicit.newPasswords); assertNull(explicit.password)
        listOf("confirm", "repeat_password", "retype", "verify_password", "password_again", "password2", "newpass").forEach {
            val form = login(webPage(htmlForm(htmlInput(0, type = "password", name = "pw"), htmlInput(1, type = "password", name = it))), browser = true)
            assertEquals(it, listOf(0, 1), form!!.newPasswords)
        }
    }

    @Test fun changePasswordHasCurrentPlusTwoNew() {
        val labelled = login(webPage(htmlForm(htmlInput(0, type = "password", name = "current_password"),
            htmlInput(1, type = "password", name = "new_password"), htmlInput(2, type = "password", name = "confirm_new_password"))),
            browser = true)!!
        assertEquals(0, labelled.password); assertEquals(listOf(1, 2), labelled.newPasswords)
        val unlabelled = login(webPage(htmlForm(htmlInput(0, type = "password", name = "password"),
            htmlInput(1, type = "password", autocomplete = "new-password"), htmlInput(2, type = "password", name = "password_confirm"))),
            browser = true)!!
        assertEquals(0, unlabelled.password); assertEquals(listOf(1, 2), unlabelled.newPasswords)
        val hinted = login(webPage(htmlForm(htmlInput(0, type = "password", autocomplete = "current-password"),
            htmlInput(1, type = "password", autocomplete = "new-password"), htmlInput(2, type = "password", autocomplete = "new-password"))),
            browser = true)!!
        assertEquals(0, hinted.password); assertEquals(listOf(1, 2), hinted.newPasswords)
    }

    @Test fun currentPlusOneNewOnlyWhenTheFirstIsCurrent() {
        val form = login(webPage(htmlForm(htmlInput(0, type = "password", name = "old_password"),
            htmlInput(1, type = "password", name = "new_password"))), browser = true)!!
        assertEquals(0, form.password); assertEquals(listOf(1), form.newPasswords)
        val hinted = login(webPage(htmlForm(htmlInput(0, type = "password", autocomplete = "current-password"),
            htmlInput(1, type = "password", autocomplete = "new-password"))), browser = true)!!
        assertEquals(0, hinted.password); assertEquals(listOf(1), hinted.newPasswords)
        // An unlabelled first box beside a new one is a registration, not a change.
        val registration = login(webPage(htmlForm(htmlInput(0, type = "password", name = "password"),
            htmlInput(1, type = "password", autocomplete = "new-password"))), browser = true)!!
        assertNull(registration.password); assertEquals(listOf(0, 1), registration.newPasswords)
    }

    @Test fun aLoneNewPasswordFieldIsNew() {
        val hinted = login(webPage(htmlForm(htmlInput(0, type = "password", autocomplete = "new-password"))), browser = true)!!
        assertNull(hinted.password); assertEquals(listOf(0), hinted.newPasswords)
        val named = login(webPage(htmlForm(htmlInput(0, type = "password", name = "new_password"))), browser = true)!!
        assertEquals(listOf(0), named.newPasswords)
    }

    @Test fun ambiguousPasswordCombinationsAreRefused() {
        fun pw(id: Int, name: String) = htmlInput(id, type = "password", name = name)
        listOf(
            listOf(pw(0, "a"), pw(1, "b")),                                      // two unlabelled
            listOf(pw(0, "new_password"), pw(1, "b")),                          // new then unlabelled
            listOf(pw(0, "old_password"), pw(1, "current")),                    // two current
            listOf(pw(0, "a"), pw(1, "b"), pw(2, "c")),
            listOf(pw(0, "new_a"), pw(1, "new_b"), pw(2, "new_c")),
            listOf(pw(0, "a"), pw(1, "b"), pw(2, "new_c")),
            listOf(pw(0, "a"), pw(1, "new_b"), pw(2, "new_c"), pw(3, "new_d")),   // four
            listOf(pw(0, "a"), pw(1, "b"), pw(2, "c"), pw(3, "d")),
        ).forEach { fields -> assertNull(login(webPage(htmlForm(*fields.toTypedArray())), browser = true)) }
    }

    @Test fun nameBasedNewPasswordsNeverApplyOutsideTrustedBrowsers() {
        val form = webPage(htmlForm(htmlInput(0, type = "password", name = "password"), htmlInput(1, type = "password", name = "confirm_password")))
        assertNull(login(appScreen(form)))
        assertEquals(listOf(0, 1), login(form, browser = true)!!.newPasswords)
    }

    @Test fun browserRejectsFramesAndUntrustedOrigins() {
        val fields = htmlForm(htmlInput(0, name = "username"), htmlInput(1, type = "password"))
        assertNotNull(login(webPage(fields), browser = true))
        assertNull(login(webPage(fields, FormNode(null, htmlTag = "iframe")), browser = true))
        assertNull(login(webPage(fields, FormNode(null, htmlTag = "FRAME")), browser = true))
        assertNull(login(webPage(FormNode(null, htmlTag = "iframe", children = listOf(fields))), browser = true))
        assertNull(login(webPage(fields, scheme = "http"), browser = true))
        assertNull(login(webPage(fields, domain = "localhost"), browser = true))
        assertNull(login(webPage(fields, domain = "user@evil.example"), browser = true))
        assertNull(login(webPage(fields, webSchemeless()), browser = true))
        assertNull(login(webPage(fields, webPage(domain = "other.example.com")), browser = true))
        // An https subdomain is a different origin, never a suffix match.
        assertNull(login(webPage(fields, webPage(domain = "login.example.com")), browser = true))
    }

    private fun webSchemeless() = FormNode(null, webDomain = "example.com", webScheme = null)

    @Test fun browserNeedsExactlyOneOriginAndCredentialsInsideIt() {
        val fields = htmlForm(htmlInput(0, name = "username"), htmlInput(1, type = "password"))
        assertNull(login(FormNode(null, children = listOf(fields)), browser = true))
        val outside = FormNode(null, children = listOf(webPage(htmlForm(htmlInput(0, type = "password"))),
            htmlForm(htmlInput(1, type = "password"))))
        assertNull(login(outside, browser = true))
    }

    @Test fun unsafeFormsStayUnsafeWhateverThePageHolds() {
        val fields = htmlForm(htmlInput(0, name = "username"), htmlInput(1, type = "password"))
        val tooDeep = (0..40).fold(htmlInput(2, name = "x")) { inner, _ -> FormNode(null, children = listOf(inner)) }
        assertNull(login(webPage(fields, tooDeep), browser = true))
        val wide = (0 until 1998).map { FormNode(null) }
        assertNotNull(login(webPage(fields, *wide.toTypedArray().copyOfRange(0, 1990)), browser = true))
        assertNull(login(webPage(fields, *wide.toTypedArray()), browser = true))
    }

    @Test fun depthBoundIsFortyLevels() {
        fun nest(levels: Int) = (1..levels).fold(htmlInput(0, type = "password")) { inner, _ -> FormNode(null, children = listOf(inner)) }
        // The page root is depth 0, so a field nested N levels below it sits at depth N + 1.
        assertNotNull(login(webPage(nest(39)), browser = true))
        assertNull(login(webPage(nest(40)), browser = true))
    }

    @Test fun cardFieldsAreNeverLoginFields() {
        assertNull(login(webPage(htmlForm(htmlInput(0, type = "password", name = "cvv", autocomplete = "cc-csc"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, type = "tel", name = "otp", autocomplete = "cc-number"))), browser = true))
        assertNull(login(appScreen(appInput(0, PASSWORD_INPUT, hints = listOf("creditCardSecurityCode")))))
        // A card box on the same page does not disturb a real login form.
        val form = login(webPage(htmlForm(htmlInput(0, name = "username"), htmlInput(1, type = "password"),
            htmlInput(2, type = "password", autocomplete = "cc-csc"))), browser = true)!!
        assertEquals(1, form.password)
    }

    @Test fun hiddenInputsNeverCount() {
        assertNull(login(webPage(htmlForm(htmlInput(0, type = "hidden", name = "password", autocomplete = "current-password"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, type = "hidden", name = "otp"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, type = "hidden", name = "username"))), browser = true))
        val form = login(webPage(htmlForm(htmlInput(0, name = "username"), htmlInput(1, type = "hidden", name = "token"),
            htmlInput(2, type = "password"))), browser = true)!!
        assertEquals(0, form.username); assertEquals(2, form.password)
    }

    @Test fun browserOtpByExactNames() {
        listOf("otp", "totp", "otp_code", "TOTP-code", "one_time_code", "one-time-password", "two_factor", "twoFactorCode", "2fa",
            "2fa_code", "mfa", "mfa-code", "auth_code", "authenticationCode", "authenticator_code", "verification_code",
            "approvals_code").forEach {
            val form = login(webPage(htmlForm(htmlInput(0, name = it))), browser = true)
            assertEquals(it, 0, form?.otp)
        }
        assertEquals(0, login(webPage(htmlForm(htmlInput(0, name = "x", elementId = "otp", type = "tel"))), browser = true)!!.otp)
        assertEquals(0, login(webPage(htmlForm(htmlInput(0, name = "x", autocomplete = "one-time-code"))), browser = true)!!.otp)
        assertEquals(0, login(webPage(htmlForm(htmlInput(0, name = "x", autocomplete = "ONE-TIME-CODE"))), browser = true)!!.otp)
    }

    @Test fun browserOtpIsNotGuessedFromSimilarNames() {
        listOf("code", "otp_token_hint", "promo_code", "search", "zip", "email_otp_sent", "sms_code").forEach {
            assertNull(it, login(webPage(htmlForm(htmlInput(0, name = it))), browser = true))
        }
        // Explicit purposes win over the name.
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "otp", autocomplete = "postal-code"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "otp", type = "checkbox"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "x", hints = listOf("smsOTPCode")))), browser = true))
    }

    @Test fun maskedCodeBoxNamedOtpIsACode() {
        assertEquals(0, login(webPage(htmlForm(htmlInput(0, type = "password", name = "otp"))), browser = true)!!.otp)
        // ... but an explicit password hint still makes it a password.
        assertEquals(0, login(webPage(htmlForm(htmlInput(0, type = "password", name = "otp", autocomplete = "current-password"))),
            browser = true)!!.password)
    }

    @Test fun browserOtpWithPasswordOrUsernameInTheSameFormIsRefused() {
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "otp"), htmlInput(1, type = "password"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "username"), htmlInput(1, name = "otp"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "otp"), htmlInput(1, type = "password", autocomplete = "new-password"))), browser = true))
        // A different, unfocused-irrelevant form with an e-mail field does not block a code screen.
        val form = login(webPage(htmlForm(htmlInput(0, type = "email", name = "newsletter")), htmlForm(htmlInput(1, name = "otp"))), browser = true)!!
        assertEquals(1, form.otp)
    }

    @Test fun twoCodeFieldsInOneFormAreRefused() {
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "otp"), htmlInput(1, name = "totp"))), browser = true))
    }

    @Test fun browserUsernameNamesAndCaseInsensitiveHints() {
        listOf("user", "loginid", "login_name", "accountName", "account", "username_or_email", "emailOrUsername", "email-or-phone",
            "phoneOrEmail", "identifier", "signin_id", "j_username", "session[username_or_email]").forEach {
            val form = login(webPage(htmlForm(htmlInput(0, name = it), htmlInput(1, type = "password"))), browser = true)
            assertEquals(it, 0, form!!.username)
        }
        val hinted = login(webPage(htmlForm(htmlInput(0, autocomplete = "Username"), htmlInput(1, type = "password", autocomplete = "Current-Password"))),
            browser = true)!!
        assertEquals(0, hinted.username); assertEquals(1, hinted.password)
        val qualified = login(webPage(htmlForm(htmlInput(0, autocomplete = "section-login username webauthn"),
            htmlInput(1, type = "password", autocomplete = "section-login current-password webauthn"))), browser = true)!!
        assertEquals(0, qualified.username); assertEquals(1, qualified.password)
    }

    @Test fun nonAccountNamesAreNotUsernames() {
        listOf("billing_email", "username_confirmation", "name", "firstname", "search", "coupon", "phone", "city").forEach {
            val form = login(webPage(htmlForm(htmlInput(9, name = it), htmlInput(1, type = "password", name = "pass"))), browser = true)
            // The nearest-input fallback must also refuse these: they name another purpose.
            assertNull(it, form!!.username)
        }
    }

    @Test fun severalBrowserCandidatesPickTheNearestBeforeThePassword() {
        val form = login(webPage(htmlForm(htmlInput(0, name = "username"), htmlInput(1, type = "email"), htmlInput(2, type = "password"))),
            browser = true)!!
        assertEquals(1, form.username)
        val after = login(webPage(htmlForm(htmlInput(2, type = "password"), htmlInput(0, name = "username"), htmlInput(1, type = "email"))),
            browser = true)!!
        assertNull(after.username)
    }

    @Test fun browserFallbackUsesTheNearestPlainInputInTheSameForm() {
        val form = login(webPage(htmlForm(htmlInput(0, name = "email_field_x", type = "text"), htmlInput(1, name = "uid_field", type = "text"),
            htmlInput(2, type = "password"))), browser = true)!!
        assertEquals(1, form.username)
        // The nearest input is a search box, so there is no fallback at all.
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "uid_field"), htmlInput(1, type = "search", name = "q"),
            htmlInput(2, type = "password"))), browser = true)!!.username)
        // Another form's field never counts.
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "uid_field")), htmlForm(htmlInput(2, type = "password"))), browser = true)!!.username)
        // A field with its own purpose is not guessed to be the account.
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "uid_field", autocomplete = "tel"), htmlInput(2, type = "password"))),
            browser = true)!!.username)
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "uid_field", type = "tel"), htmlInput(2, type = "password"))),
            browser = true)!!.username)
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "uid_field", placeholder = "Search"), htmlInput(2, type = "password"))),
            browser = true)!!.username)
    }

    @Test fun twoOriginsAreRefusedEvenWhenTheFieldsAreSplitAcrossThem() {
        val page = FormNode(null, children = listOf(
            webPage(htmlInput(0, name = "uid_field"), domain = "other.example.com"),
            webPage(htmlInput(1, type = "password"))))
        // Two origins in a browser: refused outright.
        assertNull(login(page, browser = true))
    }

    @Test fun fallbackIsSkippedWhenThereIsMoreThanOnePassword() {
        val form = login(webPage(htmlForm(htmlInput(0, name = "uid_field"), htmlInput(1, type = "password", name = "password"),
            htmlInput(2, type = "password", name = "password_confirm"))), browser = true)!!
        assertNull(form.username); assertEquals(listOf(1, 2), form.newPasswords)
    }

    @Test fun browserUsernameOnlyStepNeedsExactlyOneCandidate() {
        val one = login(webPage(htmlForm(htmlInput(0, name = "username"))), browser = true)!!
        assertEquals(0, one.username); assertNull(one.password)
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "username"), htmlInput(1, name = "email"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "q", type = "search"))), browser = true))
        // A text box with no name is not guessed in the absence of a password.
        assertNull(login(webPage(htmlForm(htmlInput(0, name = "uid_field"))), browser = true))
    }

    @Test fun browserFieldsWithoutExactIdentificationAreIgnored() {
        assertNull(login(webPage(htmlForm(htmlInput(0, type = "text", name = "firstname"))), browser = true))
        assertNull(login(webPage(htmlForm(htmlInput(0, type = "checkbox", name = "remember"), htmlInput(1, type = "submit"))), browser = true))
    }

    @Test fun passwordFieldInputTypesCountInsideWebViews() {
        assertEquals(0, login(webPage(htmlForm(htmlInput(0, type = null, inputType = PASSWORD_INPUT))), browser = true)!!.password)
    }

    @Test fun emptyOrFieldlessStructuresAreNotForms() {
        assertNull(login())
        assertNull(login(appScreen(appLabel("hello"))))
        assertNull(login(webPage(), browser = true))
    }

    @Test fun fieldsWithoutAutofillIdsAreSkipped() {
        val noId = FormNode(null, inputType = PASSWORD_INPUT)
        assertNull(login(appScreen(noId)))
    }
}
