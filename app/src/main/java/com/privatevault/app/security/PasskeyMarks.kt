package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.PasskeySummary
import com.privatevault.app.data.VaultEntry
import java.util.Locale

/**
 * "passkey_mark": a passkey for the same account as the login [entry]. The usernames must be the same, ignoring
 * case and outer spaces. The RP ID must be valid for one of the login's saved https origins ([savedLoginOrigins]):
 * the origin's host itself or a parent domain of it, never a public suffix. Windows uses the same rule
 * (`browser::passkey_matches_login`). The result is only a hint for the user. No fill or signing decision depends on it.
 */
internal fun passkeyMatchesLogin(rpId: String, passkeyUsername: String, entry: VaultEntry): Boolean {
    if (entry.type != EntryType.PASSWORD) return false
    val username = entry.primaryValue.trim()
    if (username.isEmpty() || username.lowercase(Locale.ROOT) != passkeyUsername.trim().lowercase(Locale.ROOT)) return false
    val rp = rpId.trim().lowercase(Locale.ROOT)
    // A public suffix ("com", "github.io"), a single label or an IP address is never a valid RP ID.
    if (rp.isEmpty() || registrableDomainOfHost(rp) == null) return false
    return savedLoginOrigins(entry).any { origin ->
        val host = origin.removePrefix("https://").substringBefore(':')
        host == rp || host.endsWith(".$rp")
    }
}

/** The ids of the logins in [entries] with a passkey for the same account. Reads the Public Suffix List: call it off the main thread. */
internal fun loginsWithPasskeys(entries: List<VaultEntry>, passkeys: List<PasskeySummary>): Set<String> {
    if (passkeys.isEmpty()) return emptySet()
    return entries.filter { entry -> passkeys.any { passkeyMatchesLogin(it.rpId, it.username, entry) } }
        .mapTo(HashSet()) { it.id }
}
