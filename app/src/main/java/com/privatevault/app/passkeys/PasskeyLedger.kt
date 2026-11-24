package com.privatevault.app.passkeys

import com.privatevault.app.data.PasskeyEventKind
import com.privatevault.app.data.PasskeyHistoryEvent
import com.privatevault.app.data.PasskeyOrigin
import java.security.MessageDigest
import java.util.Base64

/**
 * Plain-language reading of the passkey ledger for the passkeys page. Pure functions, so they are unit tested.
 * Nothing here reads a private key: only ids, names, times and the public key.
 */
internal object PasskeyLedger {
    /** One line of history, as a sentence: "Created in Chrome", "Signed in to github.com with Chrome". */
    fun describe(event: PasskeyHistoryEvent): String {
        val app = event.appLabel.ifBlank { event.appPackage }
        val detail = event.detail
        return when (event.eventKind) {
            PasskeyEventKind.ADDED_BEFORE_HISTORY -> "Saved before Nuvori kept passkey history"
            PasskeyEventKind.CREATED -> if (app.isNotBlank()) "Created in $app" else "Created on this device"
            PasskeyEventKind.IMPORTED -> if (detail.isNotBlank()) "Imported from $detail" else "Imported from another manager"
            PasskeyEventKind.SYNCED_IN -> when {
                detail == JOINED_SYNC -> "Copied when this device joined sync"
                detail.isNotBlank() -> "Synced from $detail"
                else -> "Synced from another device"
            }
            PasskeyEventKind.RESTORED -> "Restored from a backup"
            PasskeyEventKind.USED -> {
                val site = detail.removePrefix("https://")
                when {
                    site.isNotBlank() && app.isNotBlank() -> "Signed in to $site with $app"
                    app.isNotBlank() -> "Signed in to $app"
                    else -> "Signed in"
                }
            }
            PasskeyEventKind.DELETED -> "Deleted on this device"
            PasskeyEventKind.DELETED_ON_OTHER_DEVICE -> if (detail.isNotBlank()) "Deleted on $detail" else "Deleted on another device"
            null -> "Changed"
        }
    }

    /** What [SyncSnapshot][com.privatevault.app.sync] writes as the detail when this device joins a sync group. */
    const val JOINED_SYNC = "Joined the sync group"

    /** [passkeyId]'s lines, newest first. [history] is newest first already. */
    fun eventsFor(passkeyId: String, history: List<PasskeyHistoryEvent>): List<PasskeyHistoryEvent> =
        history.filter { it.passkeyId == passkeyId }

    /**
     * Where the passkey came from, for "Added from": the app that created it here ([origin]), or else its oldest
     * change line. Null when this device has no line for it (its history was cleared).
     */
    fun addedFrom(origin: PasskeyOrigin?, events: List<PasskeyHistoryEvent>): String? {
        if (origin != null) return origin.appLabel.ifBlank { origin.appPackage }.let { "Created in $it" }
        return events.lastOrNull { it.eventKind?.isChange == true }?.let(::describe)
    }

    /** The newest change (not a sign-in), or null. */
    fun lastChange(events: List<PasskeyHistoryEvent>): PasskeyHistoryEvent? =
        events.firstOrNull { it.eventKind?.isChange == true }

    /** Sign-ins recorded in the ledger. */
    fun signIns(events: List<PasskeyHistoryEvent>): List<PasskeyHistoryEvent> =
        events.filter { it.eventKind == PasskeyEventKind.USED }

    /**
     * SHA-256 of the stored public key (X.509 SubjectPublicKeyInfo), the first 16 bytes as 8 groups of 4 hex digits.
     * Enough to tell two keys apart on screen. Null when the key isn't base64url.
     */
    fun publicKeyFingerprint(publicKey: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(publicKey))
        digest.take(16).joinToString("") { "%02X".format(it.toInt() and 0xff) }.chunked(4).joinToString(" ")
    }.getOrNull()

    /** A long id kept readable: the first 8 and last 6 characters. Short ids stay whole. */
    fun shortId(id: String): String = if (id.length <= 18) id else "${id.take(8)}…${id.takeLast(6)}"
}
