package com.privatevault.app.autofill

import android.text.InputType

internal const val TEXT_INPUT = InputType.TYPE_CLASS_TEXT
internal const val PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
internal const val EMAIL_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

/** The page root a browser reports: carries the origin that every descendant inherits. */
internal fun webPage(vararg children: FormNode, domain: String = "example.com", scheme: String = "https") =
    FormNode(null, htmlTag = "html", webDomain = domain, webScheme = scheme, className = "android.webkit.WebView", children = children.toList())

internal fun htmlForm(vararg children: FormNode) = FormNode(null, htmlTag = "form", children = children.toList())

internal fun htmlInput(id: Int, type: String? = "text", name: String? = null, elementId: String? = null,
    autocomplete: String? = null, focused: Boolean = false, visible: Boolean = true, enabled: Boolean = true,
    placeholder: String? = null, inputType: Int = 0, hints: List<String> = emptyList()) =
    FormNode(id, hints = hints, htmlTag = "input", inputType = inputType, hintText = placeholder, visible = visible,
        enabled = enabled, focused = focused,
        htmlAttributes = listOfNotNull(type?.let { "type" to it }, name?.let { "name" to it }, elementId?.let { "id" to it },
            autocomplete?.let { "autocomplete" to it }).toMap())

internal fun appScreen(vararg children: FormNode) =
    FormNode(null, className = "android.widget.LinearLayout", children = children.toList())

internal fun appInput(id: Int, inputType: Int = TEXT_INPUT, idEntry: String? = null, hint: String? = null,
    hints: List<String> = emptyList(), focused: Boolean = false, visible: Boolean = true, enabled: Boolean = true) =
    FormNode(id, hints = hints, inputType = inputType, idEntry = idEntry, hintText = hint, visible = visible, enabled = enabled,
        focused = focused, className = "android.widget.EditText")

/** A label or button: no autofill type, so never a field. */
internal fun appLabel(text: String) = FormNode(null, autofillType = 0, hintText = text, className = "android.widget.TextView")
