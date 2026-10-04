package com.privatevault.app.autofill

import android.content.Context

/** Device-only Autofill choices. These preferences never contain vault data. */
internal object AutofillPreferences {
    private const val FILE = "vault_preferences"
    private const val COPY_LINKED_CODE = "autofill_copy_linked_code"

    /** After the account picker fills a login, copy its linked authenticator code for the next screen. */
    fun copyLinkedCode(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(COPY_LINKED_CODE, true)

    fun setCopyLinkedCode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(COPY_LINKED_CODE, enabled).apply()
    }
}
