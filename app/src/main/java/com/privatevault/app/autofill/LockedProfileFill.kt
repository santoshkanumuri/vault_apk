package com.privatevault.app.autofill

import android.app.assist.AssistStructure
import android.view.autofill.AutofillId
import com.privatevault.app.security.httpsOrigin

/** Intent extra of the profile picker: the one-use token of a [ProfileFillRequest]. */
internal const val PROFILE_FILL_TOKEN = "profile_fill_token"

/** Where a profile form's details go. [origin] is set for a trusted browser only; an app is its own destination. */
internal data class ProfileDestination(val origin: String?, val embeddedWebView: Boolean)

/**
 * Runs on a form that [classifyProfileForm] accepted. A trusted browser needs one canonical https origin, reported by
 * every node that reports a web domain: the picker shows it and re-checks it. An app never gets an origin (it chose
 * its webDomain); a web page inside it only sets [ProfileDestination.embeddedWebView] for the picker's warning.
 */
internal fun profileDestination(roots: List<FormNode>, browser: Boolean): ProfileDestination? {
    val origins = LinkedHashSet<String?>()
    var web = false
    fun visit(node: FormNode) {
        if (!node.webDomain.isNullOrBlank())
            origins.add(if (node.webScheme == "https") httpsOrigin("https://${node.webDomain}") else null)
        if (!node.webDomain.isNullOrBlank() || node.htmlTag != null || node.className?.contains("WebView", true) == true) web = true
        node.children.forEach(::visit)
    }
    roots.forEach(::visit)
    if (!browser) return ProfileDestination(null, web)
    return ProfileDestination(origins.singleOrNull() ?: return null, false)
}

internal class LockedProfileForm(val fields: Map<AutofillId, ProfileField>, val destination: ProfileDestination)

/** Thin adapter for the locked path: the same fields as [profileFields], plus the destination. */
internal fun lockedProfileForm(structure: AssistStructure, browser: Boolean): LockedProfileForm? {
    val tree = formTree(structure) ?: return null
    val fields = classifyProfileForm(tree.roots, browser)?.takeIf { it.isNotEmpty() } ?: return null
    val destination = profileDestination(tree.roots, browser) ?: return null
    return LockedProfileForm(fields.entries.associate { (index, field) -> tree.ids[index] to field }, destination)
}
