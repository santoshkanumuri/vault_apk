package com.privatevault.app.autofill

import android.app.assist.AssistStructure
import android.os.SystemClock
import android.view.autofill.AutofillId
import java.util.UUID

/** Intent extra of the card picker: the one-use token of a [CardFillRequest]. */
internal const val CARD_FILL_TOKEN = "card_fill_token"

/**
 * A payment form waiting for the card picker: field ids, what shapes each value, and the verified destination
 * ([origin] only for a trusted browser). No card data and no form contents.
 */
/** [unverified]: Nuvori could not confirm the site; the picker says the app receives the card, and fills it there only. */
internal data class CardFillRequest(val packageName: String, val identity: String, val origin: String?,
    val fields: Map<AutofillId, CardFieldSpec>, val expiresAt: Long, val embeddedWebView: Boolean = false, val unverified: Boolean = false) {
    val asksForSecurityCode: Boolean get() = fields.values.any { it.kind == CardField.CSC }
}

/** One-use tokens: at most [capacity] waiting requests (oldest dropped first), each taken once and only before it expires. */
internal open class PendingCardRequests(private val capacity: Int = 8) {
    private val requests = LinkedHashMap<String, CardFillRequest>()
    @Synchronized fun put(request: CardFillRequest, now: Long = SystemClock.elapsedRealtime()): String {
        requests.entries.removeAll { it.value.expiresAt <= now }
        while (requests.size >= capacity) requests.remove(requests.keys.first())
        return UUID.randomUUID().toString().also { requests[it] = request }
    }
    @Synchronized fun take(token: String?, now: Long = SystemClock.elapsedRealtime()): CardFillRequest? =
        token?.let(requests::remove)?.takeIf { it.expiresAt > now }
    @Synchronized fun remove(token: String) { requests.remove(token) }
}

internal object PendingCardFills : PendingCardRequests()

/** Thin adapter: the rules live in [classifyCardForm], which runs on plain nodes so it can be unit tested. */
internal fun cardFormFields(structure: AssistStructure, browser: Boolean, lenient: Boolean = false): Pair<Map<AutofillId, CardFieldSpec>, CardFormFields>? {
    val tree = formTree(structure) ?: return null
    val form = classifyCardForm(tree.roots, browser, lenient) ?: return null
    return form.fields.entries.associate { (index, spec) -> tree.ids[index] to spec } to form
}
