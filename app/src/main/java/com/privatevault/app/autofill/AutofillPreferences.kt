package com.privatevault.app.autofill

import android.content.Context

/** Device-only Autofill choices. These preferences never contain vault data. */
internal object AutofillPreferences {
    private const val FILE = "vault_preferences"
    private const val COPY_LINKED_CODE = "autofill_copy_linked_code"

    /**
     * Optional fallback: after the account picker fills a login, also copy its linked code to the clipboard.
     * Off unless the user turned it on: the keyboard and the app in front can read the clipboard, and the code
     * screen offers the code through Autofill instead. An explicit earlier choice is kept either way.
     */
    fun copyLinkedCode(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(COPY_LINKED_CODE, false)

    fun setCopyLinkedCode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(COPY_LINKED_CODE, enabled).apply()
    }

    private const val OFFER_LOCKED_PROFILES = "autofill_offer_locked_profiles"

    /**
     * Contact and address forms offer "Unlock to fill your details" while the vault is locked. On by default: the
     * suggestion holds no saved details, and nothing fills before unlock and a tap on one profile.
     */
    fun offerLockedProfiles(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(OFFER_LOCKED_PROFILES, true)

    fun setOfferLockedProfiles(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(OFFER_LOCKED_PROFILES, enabled).apply()
    }

    private const val OFFER_CARDS = "autofill_offer_cards"

    /**
     * Payment forms offer "Unlock to fill a card". On by default: the suggestion holds no card data, and nothing fills
     * before unlock and a tap on one card.
     */
    /**
     * "How logins appear": keyboard suggestions (true) or the account picker (false, the default). After an unlock,
     * Chrome and Firefox do not bring the keyboard back, so keyboard suggestions stay hidden until the field is tapped
     * again; the picker shows the matching logins at once.
     */
    const val KEYBOARD_SUGGESTIONS = "autofill_keyboard_suggestions"
    const val KEYBOARD_SUGGESTIONS_DEFAULT = false

    fun keyboardSuggestions(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEYBOARD_SUGGESTIONS, KEYBOARD_SUGGESTIONS_DEFAULT)

    fun offerCards(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(OFFER_CARDS, true)

    fun setOfferCards(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(OFFER_CARDS, enabled).apply()
    }
}
