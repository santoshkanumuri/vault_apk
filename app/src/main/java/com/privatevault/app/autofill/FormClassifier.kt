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
)

internal data class LoginFormFields(val username: Int?, val password: Int?, val otp: Int?, val origin: String? = null,
    val newPasswords: List<Int> = emptyList(), val embeddedWebView: Boolean = false)

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
    val web: Boolean, val shown: Boolean) {
    val attrs: Map<String, String> = node.htmlAttributes.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
    val tokens: Set<String> = purposeTokens(node.hints, attrs["autocomplete"])
    val norm: Set<String> = tokens.map(::normalizeToken).filter { it.isNotEmpty() }.toSet()
    val type: String = attrs["type"].orEmpty().trim().lowercase(Locale.ROOT)
    val live: Boolean get() = shown && node.enabled && node.id != null
    /** Fields outside any <form> share one group per window. */
    val group: Int get() = if (form >= 0) form else -1 - window
    var role: FieldRole? = null
}

private class FormScan(val items: List<FormItem>, val origins: Set<String>, val forms: Int, val embeddedWebView: Boolean)

/** Walks every window once. Null when the structure is unsafe: too big or deep, a frame, or an untrusted origin. */
private fun scanForm(roots: List<FormNode>, browser: Boolean, profile: Boolean): FormScan? {
    val items = ArrayList<FormItem>()
    val origins = LinkedHashSet<String>()
    var count = 0
    var forms = 0
    var embedded = false
    var unsafe = false
    fun visit(node: FormNode, depth: Int, window: Int, parentForm: Int, parentOrigin: String?, parentShown: Boolean, parentWeb: Boolean) {
        if (unsafe) return
        if (++count > MAX_NODES || depth > MAX_DEPTH) { unsafe = true; return }
        val web = parentWeb || !node.webDomain.isNullOrBlank() || node.htmlTag != null || node.className?.contains("WebView", true) == true
        if (web && !browser) embedded = true
        var origin = parentOrigin
        if (!node.webDomain.isNullOrBlank()) {
            origin = if (node.webScheme == "https") httpsOrigin("https://${node.webDomain}") else null
            if (profile) { if (node.webScheme != null && origin == null) unsafe = true }
            else {
                if (origin == null && browser) unsafe = true
                if (!browser && node.webScheme != null && node.webScheme != "https") unsafe = true
            }
            if (origin != null) origins.add(origin)
        }
        val tag = node.htmlTag?.lowercase(Locale.ROOT)
        if (tag == "iframe" || tag == "frame") unsafe = true
        val form = if (tag == "form") forms++ else parentForm
        val shown = parentShown && node.visible
        val item = FormItem(node, items.size, window, form, origin, web, shown)
        items.add(item)
        // An app cannot ask for a new password or account on a page it hosts, only a trusted browser can.
        if (!profile && !browser && ("newpassword" in item.norm || "newusername" in item.norm)) unsafe = true
        for (child in node.children) visit(child, depth + 1, window, form, origin, shown, web)
    }
    roots.forEachIndexed { window, root -> visit(root, 0, window, -1, null, true, false) }
    return if (unsafe) null else FormScan(items, origins, forms, embedded)
}

private fun roleOf(item: FormItem, browser: Boolean): FieldRole? {
    val node = item.node
    // Hidden inputs are never fields the user sees; invisible or disabled views are never filled.
    if (!item.live || item.type == "hidden") return null
    // Card details (a masked CVV, say) are never a login, whatever the input type.
    if (item.norm.any { it.startsWith("cc") || it.startsWith("creditcard") }) return null
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
            else -> Pair(null, secrets)
        }
        3 -> if (kinds[1] == PasswordKind.NEW && kinds[2] == PasswordKind.NEW && kinds[0] != PasswordKind.NEW) Pair(secrets[0], secrets.drop(1)) else null
        else -> null
    }
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
            profileKind(candidate, browser) != null) return null
        val plain = if (node.htmlTag != null) node.htmlTag.equals("input", true) && candidate.type in FALLBACK_HTML_TYPES &&
            candidate.origin == password.origin
            else !browser && !candidate.web && isNativeTextInput(node.inputType, plainOnly = true)
        return candidate.takeIf { plain }
    }
    return null
}

/** Finds the login, new-password or one-time-code fields of the form the user is working in. Null when unsure. */
internal fun classifyLoginForm(roots: List<FormNode>, browser: Boolean): LoginFormFields? {
    val scan = scanForm(roots, browser, profile = false) ?: return null
    val items = scan.items
    for (item in items) {
        val role = roleOf(item, browser)
        item.role = role
        if (role != null && item.origin == null && (browser || role == FieldRole.NEW_PASSWORD)) return null
    }
    if (browser && scan.origins.size != 1) return null
    // An embedded app can invent webDomain. Its certificate is the destination trust boundary.
    if (scan.embeddedWebView && scan.origins.size > 1) return null
    val origin = if (browser) scan.origins.single() else null
    val embedded = scan.embeddedWebView
    val groups = items.filter { it.role == FieldRole.PASSWORD || it.role == FieldRole.NEW_PASSWORD || it.role == FieldRole.OTP }.map { it.group }.distinct()
    val group = when (groups.size) {
        0 -> {
            // A username-only step (password on the next screen) needs a web page and exactly one candidate.
            if (!(browser || embedded)) return null
            val only = items.filter { it.role == FieldRole.USERNAME }.singleOrNull() ?: return null
            return LoginFormFields(only.node.id, null, null, origin, emptyList(), embedded)
        }
        1 -> groups[0]
        // Several forms hold credentials (login plus sign-up, say): only the one the user is in is safe to choose.
        else -> items.filter { it.node.focused }.map { it.group }.distinct().singleOrNull { it in groups } ?: return null
    }
    val members = items.filter { it.group == group }
    val secrets = members.filter { it.role == FieldRole.PASSWORD || it.role == FieldRole.NEW_PASSWORD }
    val codes = members.filter { it.role == FieldRole.OTP }
    val usernames = members.filter { it.role == FieldRole.USERNAME }
    if (codes.size > 1) return null
    // Separate OTP screens only: do not fill multiple factors in a mixed form.
    if (codes.isNotEmpty()) return if (secrets.isEmpty() && usernames.isEmpty())
        LoginFormFields(null, null, codes[0].node.id, origin, emptyList(), embedded) else null
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
    if (username == null && usernames.isEmpty() && secrets.size == 1 && password != null)
        username = precedingAccount(items, password, browser)
    return LoginFormFields(username?.node?.id, password?.node?.id, null, origin, fresh.mapNotNull { it.node.id }, embedded)
}

/** Contact and address fields of one form. Repeated kinds are fine; credentials and card fields never count. */
internal fun classifyProfileForm(roots: List<FormNode>, browser: Boolean): Map<Int, ProfileField>? {
    val scan = scanForm(roots, browser, profile = true) ?: return null
    if (scan.origins.size > 1 || (browser && scan.origins.size != 1)) return null
    val found = LinkedHashMap<FormItem, ProfileField>()
    for (item in scan.items) {
        val node = item.node
        if (!item.live || node.autofillType != View.AUTOFILL_TYPE_TEXT) continue
        val role = roleOf(item, browser)
        if (role == FieldRole.PASSWORD || role == FieldRole.NEW_PASSWORD || role == FieldRole.OTP) continue
        profileKind(item, browser)?.let { found[item] = it }
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

/** Indexes of [ids] are the FormNode ids. */
internal class FormTree(val roots: List<FormNode>, val ids: List<AutofillId>)

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
            node.webDomain, node.webScheme, node.className, children)
    }
    val roots = ArrayList<FormNode>()
    for (index in 0 until structure.windowNodeCount)
        roots.add(copy(structure.getWindowNodeAt(index).rootViewNode, 0) ?: return null)
    return FormTree(roots, ids)
}
