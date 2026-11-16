package com.privatevault.app.passkeys

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException

/**
 * One passkey or password request that Android passed to Nuvori: which app asked, for which site, and whether Nuvori
 * offered itself. [reason] says why not. No challenge, user ID, account name or credential leaves the request.
 */
internal data class PasskeyRequestRecord(
    val at: Long,
    val create: Boolean,
    val passkey: Boolean,
    val appLabel: String,
    val packageName: String,
    val site: String,
    val offered: Boolean,
    val reason: String = "",
)

/**
 * The last requests since Nuvori started, newest first, for "Recent passkey requests" in Settings. Memory only: it
 * answers "did this app ask Nuvori at all, and if so, why didn't Nuvori show up?" and is gone when the app process ends.
 */
internal object PasskeyRequestLog {
    private const val CAPACITY = 10
    private val records = MutableStateFlow<List<PasskeyRequestRecord>>(emptyList())
    val recent: StateFlow<List<PasskeyRequestRecord>> = records.asStateFlow()

    fun add(record: PasskeyRequestRecord) = synchronized(this) {
        records.value = (listOf(record) + records.value).take(CAPACITY)
    }

    fun clear() = synchronized(this) { records.value = emptyList() }

    /**
     * A reason a person can act on. Nuvori's own checks carry readable messages; anything else (a parser error,
     * which can quote request text) is replaced by a generic sentence, so no request content is shown or kept.
     */
    fun reasonOf(error: Throwable): String = when {
        error is IllegalArgumentException && error !is NumberFormatException && error.message?.let(::readable) == true -> error.message!!
        error is JSONException -> "The request is missing a field Nuvori needs"
        else -> "The request isn't in a format Nuvori understands"
    }

    /** Nuvori's messages are short sentences without a final period; library messages ("Failed requirement.") are not. */
    private fun readable(message: String) = message.length in 4..160 && message.first().isUpperCase() && !message.endsWith('.') &&
        listOf("Failed", "Required", "Illegal", "Input byte").none(message::startsWith)
}
