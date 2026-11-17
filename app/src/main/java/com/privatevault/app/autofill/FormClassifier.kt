package com.privatevault.app.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import com.privatevault.app.security.httpsOrigin
import java.util.Locale

/**
 * One view of an autofill structure, free of framework objects so the rules below run on a plain JVM.
 * [id] indexes the caller's own AutofillId list; null means the view cannot be filled.
 */
internal data class FormNode(
    val id: Int?,
    val hints: List<String> = emptyList(),
    val htmlTag: String? = null,
    val htmlAttributes: Map<String, String> = emptyMap(),
    val inputType: Int = 0,
    val autofillType: Int = View.AUTOFILL_TYPE_TEXT,
    val idEntry: String? = null,
    val hintText: String? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val webDomain: String? = null,
    val webScheme: String? = null,
    val className: String? = null,
    val children: List<FormNode> = emptyList(),
    /** The field's length limit (HTML maxlength or the view's), -1 when there is none. */
    val maxLength: Int = -1,
    /** A drop-down's entries (a select element's options), in order. */
    val options: List<String> = emptyList(),
)

internal data class LoginFormFields(val username: Int?, val password: Int?, val otp: Int?, val origin: String? = null,
    val newPasswords: List<Int> = emptyList(), val embeddedWebView: Boolean = false, val pinPassword: Boolean = false,
    val usernameGuessed: Boolean = false, val embeddedSite: String? = null)

private const val MAX_NODES = 2000
private const val MAX_DEPTH = 40
private const val MAX_PROFILE_FIELDS = 30

private enum class FieldRole { USERNAME, PASSWORD, NEW_PASSWORD, OTP }

private enum class PasswordKind { NEW, CURRENT, PLAIN }

private val NEW_WORDS = listOf("new", "confirm", "repeat", "retype", "verify", "again")
private val CURRENT_WORDS = listOf("current", "old", "existing")
private val FALLBACK_HTML_TYPES = setOf("", "text", "email")

/** A node in document order with what it inherits from its ancestors. */
private class FormItem(val node: FormNode, val index: Int, val window: Int, val form: Int, val origin: String?,
    val web: Boolean, val shown: Boolean, val insecure: Boolean = false) {
    val attrs: Map<String, String> = node.htmlAttributes.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
    val tokens: Set<String> = purposeTokens(node.hints, attrs["autocomplete"])
    val norm: Set<String> = tokens.map(::normalizeToken).filter { it.isNotEmpty() }.toSet()
    val type: String = attrs["type"].orEmpty().trim().lowercase(Locale.ROOT)
    /** An HTML `hidden` attribute hides the field even when a browser still reports it as visible. */
    val live: Boolean get() = shown && node.enabled && node.id != null && "hidden" !in attrs
    /** Fields outside any <form> share one group per window. */
    val group: Int get() = if (form >= 0) form else -1 - window
    var role: FieldRole? = null
}

/** [unknownOrigin]: some node reported a web domain that is not an https origin (no scheme, or not https). */
private class FormScan(val items: List<FormItem>, val origins: Set<String>, val forms: Int, val embeddedWebView: Boolean,
    val unknownOrigin: Boolean)

/**
 * Walks every window once. Null when the structure is unsafe: too big or deep, a frame in an app's WebView or in a
 * profile form, or an untrusted origin. In a trusted browser a frame or a non-https document is not unsafe by itself:
 * its nodes carry their own origin (or none, or [FormItem.insecure]) and [classifyLoginForm] decides on them.
 * [lenient]: the "unverified" fallback. Frames and non-https documents are accepted; the result never carries an origin,
 * so it fills only for the app the user is in, after the user confirms a login in the picker.
 */
private fun scanForm(roots: List<FormNode>, browser: Boolean, profile: Boolean, lenient: Boolean = false): FormScan? {
    val items = ArrayList<FormItem>()
    val origins = LinkedHashSet<String>()
    var count = 0
    var forms = 0
    var embedded = false
    var unsafe = false
    var unknown = false
    fun visit(node: FormNode, depth: Int, window: Int, parentForm: Int, parentOrigin: String?, parentShown: Boolean, parentWeb: Boolean,
        parentInsecure: Boolean) {
        if (unsafe) return
        if (++count > MAX_NODES || depth > MAX_DEPTH) { unsafe = true; return }
        val web = parentWeb || !node.webDomain.isNullOrBlank() || node.htmlTag != null || node.className?.contains("WebView", true) == true
        if (web && !browser) embedded = true
        val tag = node.htmlTag?.lowercase(Locale.ROOT)
        val frame = tag == "iframe" || tag == "frame"
        // An app's WebView and every profile form stay strict: any frame is unsafe.
        if (frame && (profile || !browser) && !lenient) unsafe = true
        // A frame is its own document and never inherits its parent's origin. A frame that reports none leaves its
        // fields without an origin, and those are never filled.
        var origin = if (frame) null else parentOrigin
        var insecure = parentInsecure
        if (!node.webDomain.isNullOrBlank()) {
            origin = if (node.webScheme == "https") httpsOrigin("https://${node.webDomain}") else null
            if (profile) { if (node.webScheme != null && origin == null) unsafe = true }
            else {
                // A trusted browser: an http ad frame does not spoil the page, but nothing in or under a non-https
                // document is filled. The network can change that document and anything it frames.
                if (origin == null && browser) insecure = true
                if (!browser && !lenient && node.webScheme != null && node.webScheme != "https") unsafe = true
            }
            if (origin != null) origins.add(origin) else unknown = true
        }
        val form = if (tag == "form") forms++ else parentForm
        val shown = parentShown && node.visible
        val item = FormItem(node, items.size, window, form, origin, web, shown, insecure)
        items.add(item)
        // An app cannot ask for a new password or account on a page it hosts, only a trusted browser can.
        if (!profile && !browser && ("newpassword" in item.norm || "newusername" in item.norm)) unsafe = true
        for (child in node.children) visit(child, depth + 1, window, form, origin, shown, web, insecure)
    }
    roots.forEachIndexed { window, root -> visit(root, 0, window, -1, null, true, false, false) }
    return if (unsafe) null else FormScan(items, origins, forms, embedded, unknown)
}

private fun roleOf(item: FormItem, browser: Boolean): FieldRole? {
    val node = item.node
    // Hidden inputs are never fields the user sees; invisible or disabled views are never filled.
    if (!item.live || item.type == "hidden") return null
    // Card details (a masked CVV, say) are never a login, whatever the input type.
    if (item.norm.any { it.startsWith("cc") || it.startsWith("creditcard") }) return null
    if (cardKind(item, browser) != null) return null
    val html = browser || item.web
    val native = !browser && !item.web
    val hints = item.tokens
    return when {
        "newpassword" in item.norm -> FieldRole.NEW_PASSWORD
        hasOtpHint(hints) -> FieldRole.OTP
        "password" in item.norm || "currentpassword" in item.norm -> FieldRole.PASSWORD
        // A masked code box is still a code: its name wins over its input type.
        isOtpFieldByName(hints, item.attrs, node.idEntry.takeIf { native }, node.hintText.takeIf { native }, node.inputType, html,
            wholeWord = isPasswordInputType(node.inputType)) -> FieldRole.OTP
        item.type == "password" || isPasswordInputType(node.inputType) -> FieldRole.PASSWORD
        isAccountField(item, html, native) -> FieldRole.USERNAME
        else -> null
    }
}

private fun isAccountField(item: FormItem, html: Boolean, native: Boolean): Boolean {
    val node = item.node
    val variation = node.inputType and InputType.TYPE_MASK_VARIATION
    val emailInput = node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
        (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
    // "Confirm e-mail", "billing e-mail" and similar are not the account even when typed as e-mail.
    val vetoed = native && hasNegativeToken(listOf(node.idEntry, node.hintText))
    if (isUsernameField(item.tokens, item.attrs, emailInput && !vetoed, html)) return true
    return native && nativeUsernameField(item.tokens, node.idEntry, node.hintText, node.inputType)
}

private val CONFIRM_WORDS = listOf("confirm", "repeat", "retype", "verify", "again")

/** Named as a confirmation (password_confirmation, password2), which only follows the new password it repeats. */
private fun confirmsPassword(item: FormItem): Boolean = listOfNotNull(item.attrs["name"], item.attrs["id"]).map(::normalizeToken)
    .any { name -> name.isNotEmpty() && (CONFIRM_WORDS.any { it in name } || name.endsWith("2")) }

private fun kindOf(item: FormItem): PasswordKind {
    if ("newpassword" in item.norm) return PasswordKind.NEW
    if ("currentpassword" in item.norm) return PasswordKind.CURRENT
    val names = listOfNotNull(item.attrs["name"], item.attrs["id"]).map(::normalizeToken).filter { it.isNotEmpty() }
    return when {
        names.any { name -> NEW_WORDS.any { it in name } || name.endsWith("2") } -> PasswordKind.NEW
        names.any { name -> CURRENT_WORDS.any { it in name } } -> PasswordKind.CURRENT
        else -> PasswordKind.PLAIN
    }
}

/**
 * Splits a trusted browser's password fields into (current, new) without autocomplete hints. Only shapes that read
 * unambiguously are accepted: a lone field, registration (password + confirmation), or change (current + new + confirmation).
 */
private fun splitPasswords(secrets: List<FormItem>): Pair<FormItem?, List<FormItem>>? {
    val kinds = secrets.map(::kindOf)
    return when (secrets.size) {
        1 -> if (kinds[0] == PasswordKind.NEW) Pair(null, secrets) else Pair(secrets[0], emptyList())
        2 -> when {
            kinds[1] != PasswordKind.NEW -> null
            kinds[0] == PasswordKind.CURRENT -> Pair(secrets[0], listOf(secrets[1]))
            kinds[0] == PasswordKind.NEW || confirmsPassword(secrets[1]) -> Pair(null, secrets)
            // A plain box beside a "new" one may be a change form whose current field has no label. Too ambiguous to fill.
            else -> null
        }
        3 -> if (kinds[1] == PasswordKind.NEW && kinds[2] == PasswordKind.NEW && kinds[0] != PasswordKind.NEW) Pair(secrets[0], secrets.drop(1)) else null
        else -> null
    }
}

/** Card field this input is, by explicit purpose, name, or (for native views) id and hint text. */
private fun cardKind(item: FormItem, browser: Boolean): CardField? {
    val native = !browser && !item.web
    // A web field's placeholder arrives as its hint text; "Card number" or "MM / YY" is a clear card signal there too.
    return cardField(item.tokens, item.attrs, item.node.idEntry.takeIf { native }, item.node.hintText)
}

/** Contact detail this field is called by its hint, name or (for native views) exact id/hint text, if any. */
private fun profileKind(item: FormItem, browser: Boolean): ProfileField? {
    val native = !browser && !item.web
    return profileField(item.tokens, item.attrs, item.node.inputType, item.node.idEntry.takeIf { native }, item.node.hintText.takeIf { native })
}

/**
 * The input just before a lone password, when nothing in its form is recognizably the account. Only a plain single-line
 * field of the same window, form and origin qualifies, and only when it names no other purpose.
 */
private fun precedingAccount(items: List<FormItem>, password: FormItem, browser: Boolean): FormItem? {
    for (index in password.index - 1 downTo 0) {
        val candidate = items[index]
        if (candidate.group != password.group || !candidate.live || candidate.node.autofillType != View.AUTOFILL_TYPE_TEXT) continue
        val node = candidate.node
        if (explicitPurposes(node.hints + candidate.attrs["autocomplete"].orEmpty()).isNotEmpty() ||
            hasNegativeToken(listOf(candidate.attrs["name"], candidate.attrs["id"], node.idEntry, node.hintText)) ||
            // Many sites sign in with a phone number or an e-mail; a name or address part is never the account.
            profileKind(candidate, browser).let { it != null && it != ProfileField.PHONE && it != ProfileField.EMAIL }) return null
        val plain = if (node.htmlTag != null) node.htmlTag.equals("input", true) && candidate.type in FALLBACK_HTML_TYPES &&
            candidate.origin == password.origin
            else !browser && !candidate.web && isNativeTextInput(node.inputType, plainOnly = true)
        return candidate.takeIf { plain }
    }
    return null
}

/**
 * Finds the login, new-password or one-time-code fields of the form the user is working in. Null when unsure.
 * [lenient] (with browser = false): the "unverified" fallback for a browser Nuvori does not recognize, a non-https page,
 * or a WebView with frames. It needs the user to be in one of the fields, and it never reports an origin.
 */
internal fun classifyLoginForm(roots: List<FormNode>, browser: Boolean, lenient: Boolean = false): LoginFormFields? {
    if (lenient && browser) return null
    val scan = scanForm(roots, browser, profile = false, lenient = lenient) ?: return null
    val items = scan.items
    for (item in items) {
        val role = roleOf(item, browser)
        item.role = role
        if (role != null && item.origin == null && (browser || role == FieldRole.NEW_PASSWORD)) return null
        // Anywhere on the page, a credential-looking field in or under a non-https document makes the request unsafe.
        if (role != null && browser && item.insecure) return null
    }
    // An embedded app can invent webDomain. Its certificate is the destination trust boundary.
    if (scan.embeddedWebView && scan.origins.size > 1 && !lenient) return null
    val embedded = scan.embeddedWebView
    /*
     * (origin, embeddedSite) for the chosen role fields. A trusted browser fills the origin of the credential fields
     * themselves, not of the whole page: every field with a role in the chosen group must carry one https origin, or
     * nothing is filled. Other nodes (ads, embeds) are ignored. A frame's own origin is what the login matches, as in
     * Chrome's password manager: a login saved for site B fills a B frame even inside a page of site A.
     * An app's WebView never gets an origin, because the app chose its webDomain. It gets embeddedSite instead: the one
     * https site the whole WebView reported, a picker hint that never authorizes anything.
     */
    fun destination(fields: List<FormItem>): Pair<String?, String?>? {
        val reported = fields.map { it.origin }.distinct()
        if (browser) return reported.singleOrNull()?.let { it to null }
        val hint = if (!embedded || scan.unknownOrigin || scan.origins.size != 1) null else reported.singleOrNull()
        return null to hint
    }
    val groups = items.filter { it.role == FieldRole.PASSWORD || it.role == FieldRole.NEW_PASSWORD || it.role == FieldRole.OTP }.map { it.group }.distinct()
    val group = when (groups.size) {
        0 -> {
            // A username-only step (password on the next screen) needs a web page and one candidate: the only one, or
            // the one the user is in (a page can keep a second, script-copied account box).
            if (!(browser || embedded)) return null
            val candidates = items.filter { it.role == FieldRole.USERNAME }
            val only = candidates.singleOrNull() ?: candidates.singleOrNull { it.node.focused } ?: return null
            if (lenient && !only.node.focused) return null
            val (origin, site) = destination(listOf(only)) ?: return null
            return LoginFormFields(only.node.id, null, null, origin, emptyList(), embedded, embeddedSite = site)
        }
        1 -> groups[0]
        // Several forms hold credentials (login plus sign-up, say): only the one the user is in is safe to choose.
        else -> items.filter { it.node.focused }.map { it.group }.distinct().singleOrNull { it in groups } ?: return null
    }
    val members = items.filter { it.group == group }
    // The fallback offers a fill only where the user is: in one of this form's fields.
    if (lenient && members.none { it.role != null && it.node.focused }) return null
    // Role fields of one group in two origins (fields around and inside a frame, say): never mix them.
    val (origin, site) = destination(members.filter { it.role != null }) ?: return null
    val secrets = members.filter { it.role == FieldRole.PASSWORD || it.role == FieldRole.NEW_PASSWORD }
    val codes = members.filter { it.role == FieldRole.OTP }
    val usernames = members.filter { it.role == FieldRole.USERNAME }
    if (codes.size > 1) return null
    if (codes.isNotEmpty()) {
        if (secrets.isEmpty() && usernames.isEmpty())
            return LoginFormFields(null, null, codes[0].node.id, origin, emptyList(), embedded, embeddedSite = site)
        // One form asks for the account, the password and a code (autofill.me "login-totp"). Each fill is one factor:
        // in the code field the code alone, anywhere else the login, and the code field is left alone. The unverified
        // fallback still refuses such a form.
        if (lenient) return null
        if (codes[0].node.focused) return LoginFormFields(null, null, codes[0].node.id, origin, emptyList(), embedded, embeddedSite = site)
    }
    // Without autocomplete hints only a trusted browser may tell a new password from the current one; an app's lone field is current.
    val split: Pair<FormItem?, List<FormItem>> = if (browser) {
        splitPasswords(secrets) ?: return null
    } else {
        if (secrets.size != 1 || secrets[0].role != FieldRole.PASSWORD) return null
        Pair(secrets[0], emptyList())
    }
    val (password, fresh) = split
    val anchor = secrets[0]
    var username = usernames.lastOrNull { it.index < anchor.index } ?: usernames.singleOrNull()
    var guessed = false
    if (username == null && usernames.isEmpty() && secrets.size == 1 && password != null) {
        username = precedingAccount(items, password, browser)
        guessed = username != null
    }
    val pin = password != null && password.node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
    return LoginFormFields(username?.node?.id, password?.node?.id, null, origin, fresh.mapNotNull { it.node.id }, embedded, pin, guessed, site)
}

/** Contact and address fields of one form. Repeated kinds are fine; credentials and card fields never count. */
internal fun classifyProfileForm(roots: List<FormNode>, browser: Boolean): Map<Int, ProfileField>? {
    val scan = scanForm(roots, browser, profile = true) ?: return null
    if (scan.origins.size > 1 || (browser && scan.origins.size != 1)) return null
    val found = LinkedHashMap<FormItem, ProfileField>()
    for (item in scan.items) {
        val node = item.node
        if (!item.live) continue
        // A drop-down counts only as a state or country list with entries to choose from.
        val list = node.autofillType == View.AUTOFILL_TYPE_LIST
        if (!list && node.autofillType != View.AUTOFILL_TYPE_TEXT) continue
        val role = if (list) null else roleOf(item, browser)
        if (role == FieldRole.PASSWORD || role == FieldRole.NEW_PASSWORD || role == FieldRole.OTP) continue
        profileKind(item, browser)?.takeIf { !list || (node.options.isNotEmpty() && (it == ProfileField.STATE || it == ProfileField.COUNTRY)) }
            ?.let { found[item] = it }
    }
    var chosen: Map<FormItem, ProfileField> = found
    if (scan.forms > 1) {
        // Without a focused field there is no telling which form was meant.
        val focus = scan.items.filter { it.node.focused }.map { it.group }.distinct()
            .singleOrNull { group -> found.keys.any { it.group == group } } ?: return null
        chosen = found.filterKeys { it.group == focus }
    }
    if (chosen.isEmpty() || chosen.size > MAX_PROFILE_FIELDS) return null
    return chosen.entries.mapNotNull { (item, field) -> item.node.id?.let { it to field } }.toMap()
}

internal data class CardFormFields(val fields: Map<Int, CardFieldSpec>, val origin: String?, val embeddedWebView: Boolean)

private const val MAX_CARD_FIELDS = 12

/**
 * Payment-card fields of the form the user is in. A trusted browser fills one https origin: the number, expiry and
 * security code must share it (hosted payment frames, such as one frame per field, all report the payment provider's
 * origin); a name field on another origin is left out. Nothing in or under a non-https document is filled. An app
 * fills for its signing identity; a frame in its WebView, or an insecure page, makes the request unsafe.
 * Offered only for a card number, or for an explicitly marked security code (a "confirm your CVV" step).
 */
internal fun classifyCardForm(roots: List<FormNode>, browser: Boolean, lenient: Boolean = false): CardFormFields? {
    if (lenient && browser) return null
    val scan = scanForm(roots, browser, profile = false, lenient = lenient) ?: return null
    val found = LinkedHashMap<FormItem, CardField>()
    var explicitCode = false
    for (item in scan.items) {
        if (!item.live || item.type == "hidden") continue
        val kind = cardKind(item, browser) ?: continue
        if (!cardFieldFits(kind, item.node.autofillType)) continue
        if (browser && (item.origin == null || item.insecure)) continue
        found[item] = kind
        if (kind == CardField.CSC && item.norm.any { it == "cccsc" || it == "creditcardsecuritycode" }) explicitCode = true
    }
    if (found.isEmpty()) return null
    val groups = found.keys.map { it.group }.distinct()
    val group = groups.singleOrNull()
        ?: scan.items.filter { it.node.focused }.map { it.group }.distinct().singleOrNull { it in groups } ?: return null
    var chosen = found.filterKeys { it.group == group }
    // A card number beside a password is a bank sign-in ("card number and passcode"), not a payment: refused. A form
    // that also asks for the expiry or the security code is a payment, even when it creates an account at the same
    // time (a sign-up-and-pay checkout, roboform.com's test form); the login there is offered in its own fields.
    val payment = chosen.values.any { it == CardField.EXPIRY || it == CardField.EXP_MONTH || it == CardField.EXP_YEAR || it == CardField.CSC }
    if (!payment && scan.items.any { it.group == group && it.live && (it.type == "password" || isPasswordInputType(it.node.inputType)) &&
            cardKind(it, browser) == null }) return null
    val origin: String? = if (browser) {
        val sensitive = chosen.filterValues { it != CardField.NAME && it != CardField.GIVEN_NAME && it != CardField.FAMILY_NAME }
        val origins = (sensitive.ifEmpty { chosen }).keys.map { it.origin }.distinct()
        val only = origins.singleOrNull() ?: return null
        chosen = chosen.filterKeys { it.origin == only }
        only
    } else null
    if (!browser && !lenient && scan.embeddedWebView && scan.origins.size > 1) return null
    // The fallback offers a card only where the user is: in one of these card fields.
    if (lenient && chosen.keys.none { it.node.focused }) return null
    val kinds = chosen.values
    if (CardField.NUMBER !in kinds && !(CardField.CSC in kinds && explicitCode)) return null
    if (chosen.size > MAX_CARD_FIELDS) return null
    val fields = chosen.entries.mapNotNull { (item, kind) ->
        val node = item.node
        val limit = item.attrs["maxlength"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: node.maxLength
        val pattern = listOfNotNull(item.attrs["placeholder"], node.hintText, item.attrs["aria-label"]).firstOrNull { it.isNotBlank() }.orEmpty()
        node.id?.let { it to CardFieldSpec(kind, limit, pattern, if (node.autofillType == View.AUTOFILL_TYPE_LIST) node.options else emptyList()) }
    }.toMap()
    return CardFormFields(fields, origin, !browser && scan.embeddedWebView)
}

/** Indexes of [ids] are the FormNode ids. */
internal class FormTree(val roots: List<FormNode>, val ids: List<AutofillId>) {
    /** The entries of each drop-down among [fields], by autofill id. */
    fun listOptions(fields: Collection<AutofillId>): Map<AutofillId, List<String>> {
        val wanted = fields.toSet()
        val found = HashMap<AutofillId, List<String>>()
        fun visit(node: FormNode) {
            val id = node.id?.let(ids::getOrNull)
            if (id != null && id in wanted && node.autofillType == View.AUTOFILL_TYPE_LIST && node.options.isNotEmpty()) found[id] = node.options
            node.children.forEach(::visit)
        }
        roots.forEach(::visit)
        return found
    }
}

/** Copies an AssistStructure into FormNodes. Null when it exceeds the node or depth bound, which callers treat as unsafe. */
internal fun formTree(structure: AssistStructure): FormTree? {
    val ids = ArrayList<AutofillId>()
    var count = 0
    fun copy(node: AssistStructure.ViewNode, depth: Int): FormNode? {
        if (++count > MAX_NODES || depth > MAX_DEPTH) return null
        val id = node.autofillId?.let { ids.add(it); ids.size - 1 }
        val html = node.htmlInfo
        val children = ArrayList<FormNode>(node.childCount)
        for (index in 0 until node.childCount) children.add(copy(node.getChildAt(index), depth + 1) ?: return null)
        return FormNode(id, node.autofillHints?.toList().orEmpty(), html?.let { it.tag ?: "" },
            html?.attributes.orEmpty().associate { it.first.lowercase(Locale.ROOT) to it.second.orEmpty() }, node.inputType,
            node.autofillType, node.idEntry, node.hint, node.visibility == View.VISIBLE, node.isEnabled, node.isFocused,
            node.webDomain, node.webScheme, node.className, children, node.maxTextLength,
            node.autofillOptions?.map { it.toString() }.orEmpty())
    }
    val roots = ArrayList<FormNode>()
    for (index in 0 until structure.windowNodeCount)
        roots.add(copy(structure.getWindowNodeAt(index).rootViewNode, 0) ?: return null)
    return FormTree(roots, ids)
}
