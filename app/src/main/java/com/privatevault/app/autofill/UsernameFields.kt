package com.privatevault.app.autofill

import java.util.Locale

/** Recognize account identifiers without guessing from arbitrary text fields or their values. */
internal fun isUsernameField(hints: Set<String>, attributes: Map<String, String>, emailInput: Boolean, browser: Boolean): Boolean {
    val normalizedHints = hints.map { it.lowercase(Locale.ROOT) }.toSet()
    val type = attributes["type"].orEmpty().lowercase(Locale.ROOT)
    if (browser && type !in setOf("", "text", "email")) return false
    if (normalizedHints.any { it in setOf("username", "newusername", "emailaddress", "email") } || emailInput || type == "email") return true
    // An explicit purpose such as one-time-code, name or telephone takes precedence.
    if (!browser || normalizedHints.any { it.isNotBlank() && it !in setOf("off", "on") }) return false
    return listOfNotNull(attributes["name"], attributes["id"]).any {
        it.lowercase(Locale.ROOT).replace("_", "").replace("-", "") in
            setOf("username", "email", "emailaddress", "login", "loginemail", "loginusername", "userid")
    }
}
