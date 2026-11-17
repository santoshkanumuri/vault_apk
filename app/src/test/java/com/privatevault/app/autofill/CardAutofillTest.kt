package com.privatevault.app.autofill

import android.view.View
import org.junit.Assert.*
import org.junit.Test

class CardAutofillTest {
    private fun cardForm(vararg roots: FormNode, browser: Boolean = true) = classifyCardForm(roots.toList(), browser)
    private fun kinds(form: CardFormFields?) = form?.fields?.mapValues { it.value.kind }

    private fun frame(vararg children: FormNode, domain: String, scheme: String = "https") =
        FormNode(null, htmlTag = "iframe", webDomain = domain, webScheme = scheme, children = children.toList())

    private fun select(id: Int, name: String, options: List<String>) = FormNode(id, htmlTag = "select", autofillType = View.AUTOFILL_TYPE_LIST,
        htmlAttributes = mapOf("name" to name), options = options)

    private val card = CardDetails("4111 1111 1111 1111", "Jamie Q Doe", "08/29", "123")

    // ---- field detection -----------------------------------------------------------------------------------------

    @Test fun explicitPurposesNameEveryCardField() {
        fun field(token: String) = cardField(listOf(token), emptyMap(), null, null)
        assertEquals(CardField.NUMBER, field("cc-number"))
        assertEquals(CardField.NUMBER, field("creditCardNumber"))
        assertEquals(CardField.NAME, field("cc-name"))
        assertEquals(CardField.GIVEN_NAME, field("cc-given-name"))
        assertEquals(CardField.FAMILY_NAME, field("cc-family-name"))
        assertEquals(CardField.EXPIRY, field("cc-exp"))
        assertEquals(CardField.EXPIRY, field("creditCardExpirationDate"))
        assertEquals(CardField.EXP_MONTH, field("cc-exp-month"))
        assertEquals(CardField.EXP_YEAR, field("creditCardExpirationYear"))
        assertEquals(CardField.CSC, field("cc-csc"))
        assertEquals(CardField.CSC, field("creditCardSecurityCode"))
        // The card type takes the network ("Visa"), matched to the form's own entries.
        assertEquals(CardField.TYPE, field("cc-type"))
    }

    @Test fun namesAndPlaceholdersAreReadMostSpecificFirst() {
        fun byName(name: String) = cardField(emptyList(), mapOf("name" to name), null, null)
        assertEquals(CardField.NUMBER, byName("cardnumber"))
        assertEquals(CardField.NUMBER, byName("card_number"))
        assertEquals(CardField.NUMBER, byName("ccNum"))
        assertEquals(CardField.NAME, byName("cardholder-name"))
        assertEquals(CardField.NAME, byName("name_on_card"))
        assertEquals(CardField.EXPIRY, byName("expiry"))
        assertEquals(CardField.EXPIRY, byName("exp-date"))
        assertEquals(CardField.EXP_MONTH, byName("expiry_month"))
        assertEquals(CardField.EXP_MONTH, byName("cc-exp-mm"))
        assertEquals(CardField.EXP_YEAR, byName("expiryYear"))
        assertEquals(CardField.CSC, byName("cvv"))
        assertEquals(CardField.CSC, byName("cardSecurityCode"))
        assertEquals(CardField.CSC, byName("cvc"))
        // Without a card word these are one-time codes, which the login rules handle.
        assertNull(byName("securityCode"))
        assertNull(byName("verification_code"))
        assertNull(byName("account_security_code"))
        assertEquals(CardField.EXPIRY, cardField(emptyList(), emptyMap(), null, "MM / YY"))
        assertEquals(CardField.NUMBER, cardField(emptyList(), emptyMap(), null, "Card number"))
        assertEquals(CardField.NUMBER, cardField(emptyList(), emptyMap(), "et_card_number", null))
    }

    @Test fun otherCardsAndOtherPurposesAreNotPaymentCards() {
        fun byName(name: String) = cardField(emptyList(), mapOf("name" to name), null, null)
        assertNull(byName("giftcardnumber"))
        assertNull(byName("loyalty_card_number"))
        assertNull(byName("membership-card-no"))
        assertNull(byName("birth_month"))
        assertNull(byName("year"))
        assertNull(byName("name"))
        assertNull(byName("number"))
        // A known purpose wins over the name; unknown autocomplete values (used to turn browser autofill off) do not.
        assertNull(cardField(listOf("email"), mapOf("name" to "cardnumber"), null, null))
        assertEquals(CardField.NUMBER, cardField(listOf("off"), mapOf("name" to "cardnumber"), null, null))
        assertEquals(CardField.NUMBER, cardField(listOf("nope"), mapOf("name" to "cardnumber"), null, null))
    }

    // ---- forms -----------------------------------------------------------------------------------------------------

    @Test fun aCheckoutFormIsFoundWithItsOrigin() {
        val form = cardForm(webPage(htmlForm(
            htmlInput(0, name = "cardnumber", autocomplete = "cc-number"),
            htmlInput(1, name = "ccname", autocomplete = "cc-name"),
            htmlInput(2, name = "exp", autocomplete = "cc-exp", placeholder = "MM/YY"),
            htmlInput(3, type = "password", name = "cvc", autocomplete = "cc-csc"),
        ), domain = "shop.example"))
        assertEquals("https://shop.example", form?.origin)
        assertEquals(mapOf(0 to CardField.NUMBER, 1 to CardField.NAME, 2 to CardField.EXPIRY, 3 to CardField.CSC), kinds(form))
        assertEquals("MM/YY", form?.fields?.get(2)?.pattern)
    }

    @Test fun aMaskedSecurityCodeIsNotTakenForALoginPassword() {
        val page = webPage(htmlForm(htmlInput(0, name = "cardnumber"), htmlInput(1, type = "password", name = "cvv")), domain = "shop.example")
        assertNull(classifyLoginForm(listOf(page), true))
        assertEquals(mapOf(0 to CardField.NUMBER, 1 to CardField.CSC), kinds(cardForm(page)))
    }

    @Test fun hostedPaymentFramesOfOneProviderFill() {
        // One frame per field, all from the payment provider; the cardholder name sits on the shop's own page.
        val form = cardForm(webPage(
            htmlInput(0, name = "name", autocomplete = "cc-name"),
            frame(htmlInput(1, name = "cardnumber", autocomplete = "cc-number"), domain = "js.pay.example"),
            frame(htmlInput(2, name = "exp-date", autocomplete = "cc-exp"), domain = "js.pay.example"),
            frame(htmlInput(3, name = "cvc", autocomplete = "cc-csc"), domain = "js.pay.example"),
            domain = "shop.example"))
        assertEquals("https://js.pay.example", form?.origin)
        // The name field of the other origin is left alone.
        assertEquals(mapOf(1 to CardField.NUMBER, 2 to CardField.EXPIRY, 3 to CardField.CSC), kinds(form))
    }

    @Test fun cardFieldsSplitAcrossTwoProvidersAreNotFilled() {
        assertNull(cardForm(webPage(
            frame(htmlInput(0, autocomplete = "cc-number"), domain = "a.pay.example"),
            frame(htmlInput(1, autocomplete = "cc-csc"), domain = "b.pay.example"), domain = "shop.example")))
    }

    @Test fun insecurePagesAndFramesAreNeverFilled() {
        assertNull(cardForm(webPage(htmlInput(0, autocomplete = "cc-number"), domain = "shop.example", scheme = "http")))
        assertNull(cardForm(webPage(frame(htmlInput(0, autocomplete = "cc-number"), domain = "pay.example", scheme = "http"),
            domain = "shop.example")))
    }

    @Test fun anAppWebViewWithAFrameIsUnsafeButAPlainOneIsMarkedEmbedded() {
        assertNull(cardForm(appScreen(webPage(frame(htmlInput(0, autocomplete = "cc-number"), domain = "pay.example"))), browser = false))
        val form = cardForm(appScreen(webPage(htmlInput(0, autocomplete = "cc-number"), domain = "pay.example")), browser = false)
        assertNotNull(form)
        assertNull("an app never gets an origin", form?.origin)
        assertTrue(form!!.embeddedWebView)
    }

    @Test fun nativeAppsUseHintsIdsAndHintText() {
        val form = cardForm(appScreen(
            appInput(0, hints = listOf("creditCardNumber")),
            appInput(1, idEntry = "card_holder"),
            appInput(2, hint = "MM/YY"),
            appInput(3, PASSWORD_INPUT, idEntry = "cvv"),
        ), browser = false)
        assertEquals(mapOf(0 to CardField.NUMBER, 1 to CardField.NAME, 2 to CardField.EXPIRY, 3 to CardField.CSC), kinds(form))
        assertFalse(form!!.embeddedWebView)
    }

    @Test fun onlyANumberOrAnExplicitSecurityCodeStartsAnOffer() {
        assertNull("a lone name-only field", cardForm(webPage(htmlInput(0, name = "cardholder"))))
        assertNull("a cvv box by name alone", cardForm(webPage(htmlInput(0, name = "cvv"))))
        assertEquals(mapOf(0 to CardField.CSC), kinds(cardForm(webPage(htmlInput(0, name = "cvv", autocomplete = "cc-csc")))))
    }

    @Test fun aPasswordBesideCardFieldsMeansSomethingElse() {
        // A bank sign-in with a card number and a passcode: not a payment.
        assertNull(cardForm(webPage(htmlForm(htmlInput(0, name = "cardnumber"), htmlInput(1, type = "password", name = "password")))))
    }

    @Test fun aSignUpAndPayFormStillFillsTheCard() {
        // roboform.com/filling-test-all-fields: a user ID and password in the same form as a full card.
        val form = cardForm(webPage(htmlForm(htmlInput(0, name = "30_user_id"), htmlInput(1, type = "password", name = "31password"),
            htmlInput(2, name = "41ccnumber", focused = true), htmlInput(3, name = "43cvc"), htmlInput(4, name = "42ccexp_mm"))))!!
        assertEquals(CardField.NUMBER, form.fields[2]?.kind)
        assertEquals(CardField.CSC, form.fields[3]?.kind)
        assertNull("the password is never a card field", form.fields[1])
    }

    @Test fun withTwoCardFormsOnlyTheFocusedOneCounts() {
        fun page(focus: Int?) = webPage(
            htmlForm(htmlInput(0, name = "cardnumber", focused = focus == 0)),
            htmlForm(htmlInput(1, name = "cardnumber", focused = focus == 1)))
        assertNull(cardForm(page(null)))
        assertEquals(mapOf(1 to CardField.NUMBER), kinds(cardForm(page(1))))
    }

    @Test fun dropDownsAreKeptOnlyForMonthAndYear() {
        val form = cardForm(webPage(htmlForm(htmlInput(0, name = "cardnumber"),
            select(1, "exp_month", listOf("Month", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12")),
            select(2, "exp_year", listOf("Year", "2026", "2027", "2028", "2029")))))
        assertEquals(mapOf(0 to CardField.NUMBER, 1 to CardField.EXP_MONTH, 2 to CardField.EXP_YEAR), kinds(form))
        assertEquals(13, form?.fields?.get(1)?.options?.size)
    }

    // ---- values ----------------------------------------------------------------------------------------------------

    @Test fun storedExpiriesAreRead() {
        assertEquals(8 to 2029, parseExpiry("08/29"))
        assertEquals(8 to 2029, parseExpiry("8/2029"))
        assertEquals(8 to 2029, parseExpiry("0829"))
        assertEquals(12 to 2030, parseExpiry("12 - 30"))
        assertNull(parseExpiry("13/29"))
        assertNull(parseExpiry(""))
        assertNull(parseExpiry("soon"))
    }

    @Test fun eachFieldGetsTheShapeItAsksFor() {
        val values = cardFillValues(card, mapOf(
            "number" to CardFieldSpec(CardField.NUMBER),
            "name" to CardFieldSpec(CardField.NAME),
            "given" to CardFieldSpec(CardField.GIVEN_NAME),
            "family" to CardFieldSpec(CardField.FAMILY_NAME),
            "exp" to CardFieldSpec(CardField.EXPIRY),
            "exp4" to CardFieldSpec(CardField.EXPIRY, pattern = "MM/YYYY"),
            "expPacked" to CardFieldSpec(CardField.EXPIRY, maxLength = 4),
            "month" to CardFieldSpec(CardField.EXP_MONTH),
            "year" to CardFieldSpec(CardField.EXP_YEAR),
            "year2" to CardFieldSpec(CardField.EXP_YEAR, maxLength = 2),
            "code" to CardFieldSpec(CardField.CSC),
        ), includeSecurityCode = true)
        assertEquals(CardValue.Text("4111111111111111"), values["number"])
        assertEquals(CardValue.Text("Jamie Q Doe"), values["name"])
        assertEquals(CardValue.Text("Jamie Q"), values["given"])
        assertEquals(CardValue.Text("Doe"), values["family"])
        assertEquals(CardValue.Text("08/29"), values["exp"])
        assertEquals(CardValue.Text("08/2029"), values["exp4"])
        assertEquals(CardValue.Text("0829"), values["expPacked"])
        assertEquals(CardValue.Text("08"), values["month"])
        assertEquals(CardValue.Text("2029"), values["year"])
        assertEquals(CardValue.Text("29"), values["year2"])
        assertEquals(CardValue.Text("123"), values["code"])
    }

    @Test fun theSecurityCodeIsLeftOutUnlessAskedFor() {
        val values = cardFillValues(card, mapOf("number" to CardFieldSpec(CardField.NUMBER), "code" to CardFieldSpec(CardField.CSC)),
            includeSecurityCode = false)
        assertEquals(setOf("number"), values.keys)
    }

    @Test fun splitNumberBoxesTakeTheDigitsInOrder() {
        val boxes = linkedMapOf("a" to CardFieldSpec(CardField.NUMBER, 4), "b" to CardFieldSpec(CardField.NUMBER, 4),
            "c" to CardFieldSpec(CardField.NUMBER, 4), "d" to CardFieldSpec(CardField.NUMBER, 4))
        assertEquals(listOf("4111", "1111", "1111", "1111"),
            cardFillValues(card, boxes, false).values.map { (it as CardValue.Text).text })
        // Amex in 4-6-5 boxes.
        val amex = linkedMapOf("a" to CardFieldSpec(CardField.NUMBER, 4), "b" to CardFieldSpec(CardField.NUMBER, 6),
            "c" to CardFieldSpec(CardField.NUMBER, 5))
        assertEquals(listOf("3782", "822463", "10005"),
            cardFillValues(card.copy(number = "378282246310005"), amex, false).values.map { (it as CardValue.Text).text })
    }

    @Test fun dropDownsPickTheMatchingEntry() {
        val months = listOf("Month", "1 - January", "2 - February", "3", "4", "5", "6", "7", "08 - August", "9", "10", "11", "12")
        val names = listOf("MM", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val years = listOf("YYYY", "2027", "2028", "2029", "2030")
        val twoDigit = listOf("YY", "27", "28", "29")
        assertEquals(8, monthOption(months, 8))
        assertEquals(8, monthOption(names, 8))
        assertEquals(3, yearOption(years, 2029))
        assertEquals(3, yearOption(twoDigit, 2029))
        assertNull(yearOption(years, 2035))
        val values = cardFillValues(card, mapOf("m" to CardFieldSpec(CardField.EXP_MONTH, options = months),
            "y" to CardFieldSpec(CardField.EXP_YEAR, options = years)), false)
        assertEquals(CardValue.Choice(8), values["m"])
        assertEquals(CardValue.Choice(3), values["y"])
    }

    @Test fun theCardTypeDropDownTakesTheNetwork() {
        // autofill.me "credit-card-simple": <select autocomplete="cc-type"> with Visa, Master Card, American Express, Discover.
        val types = listOf("Choose...", "Visa", "Master Card", "American Express", "Discover")
        assertEquals(1, networkOption(types, "Visa"))
        assertEquals(2, networkOption(types, "Mastercard"))
        assertEquals(3, networkOption(types, "American Express"))
        assertEquals(1, networkOption(listOf("VI", "MC", "AX"), "Mastercard"))
        assertEquals(1, networkOption(listOf("Visa Electron", "Visa"), "Visa"))
        assertNull("credit or debit is not a network", networkOption(listOf("Credit", "Debit"), "Visa"))
        assertNull(networkOption(types, "RuPay"))
        assertEquals(CardField.TYPE, cardField(listOf("cc-type"), emptyMap(), null, null))
        assertEquals(CardField.TYPE, cardFieldByName("cardType"))
        // The saved network first; without one, the network the number belongs to.
        val spec = mapOf("t" to CardFieldSpec(CardField.TYPE, options = types))
        assertEquals(CardValue.Choice(1), cardFillValues(card, spec, false)["t"])
        assertEquals(CardValue.Choice(2), cardFillValues(CardDetails("5500000000000004", "", "", "", "Mastercard"), spec, false)["t"])
        assertEquals(CardValue.Text("Visa"), cardFillValues(card, mapOf("t" to CardFieldSpec(CardField.TYPE)), false)["t"])
        assertTrue(cardFieldFits(CardField.TYPE, android.view.View.AUTOFILL_TYPE_LIST))
    }

    @Test fun anUnreadableExpiryOrEmptyNameFillsNothingForThoseFields() {
        val values = cardFillValues(CardDetails("4111111111111111", "", "someday", ""), mapOf(
            "n" to CardFieldSpec(CardField.NAME), "e" to CardFieldSpec(CardField.EXPIRY), "c" to CardFieldSpec(CardField.CSC),
            "num" to CardFieldSpec(CardField.NUMBER)), includeSecurityCode = true)
        assertEquals(setOf("num"), values.keys)
    }

    @Test fun pendingRequestsAreOneUseAndExpire() {
        val store = PendingCardRequests(capacity = 2)
        val request = CardFillRequest("app", "id", null, emptyMap(), expiresAt = 1_000)
        val token = store.put(request, now = 0)
        assertEquals(request, store.take(token, now = 10))
        assertNull("taken once", store.take(token, now = 10))
        val late = store.put(request, now = 0)
        assertNull("expired", store.take(late, now = 1_000))
    }

    @Test fun theUnverifiedCardFallbackNeedsTheFocusOnACardField() {
        fun page(focus: Boolean) = webPage(htmlForm(htmlInput(0, autocomplete = "cc-number", focused = focus),
            htmlInput(1, autocomplete = "cc-exp")), scheme = "http")
        assertNull(classifyCardForm(listOf(page(true)), browser = true))
        val form = classifyCardForm(listOf(page(true)), browser = false, lenient = true)!!
        assertEquals(setOf(CardField.NUMBER, CardField.EXPIRY), form.fields.values.map { it.kind }.toSet())
        assertNull(form.origin)
        assertNull(classifyCardForm(listOf(page(false)), browser = false, lenient = true))
        assertNull(classifyCardForm(listOf(page(true)), browser = true, lenient = true))
    }
}
