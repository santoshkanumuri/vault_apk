package com.privatevault.app.passkeys

import android.content.Context
import com.privatevault.app.security.SealedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

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
 * The last [CAPACITY] requests, newest first, for "Passkey requests" in Settings. It answers "did this app ask Nuvori
 * at all, and if so, why didn't Nuvori show up?". Kept on this device in a [SealedFile] (never synced or backed up),
 * so the history survives a restart; "Clear history" deletes it. Disk work runs on one background thread.
 */
internal object PasskeyRequestLog {
    const val CAPACITY = 100
    private val records = MutableStateFlow<List<PasskeyRequestRecord>>(emptyList())
    val recent: StateFlow<List<PasskeyRequestRecord>> = records.asStateFlow()
    private val disk = Executors.newSingleThreadExecutor { Thread(it, "nuvori-requests").apply { isDaemon = true } }
    private var loaded = false

    private fun file(context: Context) = SealedFile(context, "passkey-requests", "nuvori_passkey_requests_v1", 128 * 1024)

    fun add(context: Context, record: PasskeyRequestRecord) {
        val app = context.applicationContext
        disk.execute {
            runCatching {
                synchronized(this) {
                    if (!loaded) records.value = read(app)
                    loaded = true
                    records.value = (listOf(record) + records.value).take(CAPACITY)
                    write(app, records.value)
                }
            }
        }
    }

    /** Reads the saved history into [recent]. Safe to call often: it reads the disk only the first time. */
    fun load(context: Context) {
        val app = context.applicationContext
        disk.execute { runCatching { synchronized(this) { if (!loaded) { records.value = read(app); loaded = true } } } }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(this) { records.value = emptyList(); loaded = true }
        disk.execute { runCatching { file(app).delete() } }
    }

    /** Waits until every disk task sent so far is done. For tests. */
    fun awaitDisk() { disk.submit { }.get() }

    /** Forgets what is in memory, so the next [load] reads the disk again. For tests. */
    fun forgetMemory() = synchronized(this) { records.value = emptyList(); loaded = false }

    private fun read(context: Context): List<PasskeyRequestRecord> = runCatching {
        val plain = file(context).read() ?: return emptyList()
        val array = JSONObject(plain.toString(Charsets.UTF_8)).getJSONArray("requests")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PasskeyRequestRecord(item.getLong("at"), item.getBoolean("create"), item.getBoolean("passkey"),
                item.getString("app"), item.getString("package"), item.getString("site"), item.getBoolean("offered"),
                item.optString("reason"))
        }.take(CAPACITY)
    }.getOrDefault(emptyList())

    private fun write(context: Context, list: List<PasskeyRequestRecord>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("at", it.at).put("create", it.create).put("passkey", it.passkey).put("app", it.appLabel)
                .put("package", it.packageName).put("site", it.site).put("offered", it.offered).put("reason", it.reason))
        }
        file(context).write(JSONObject().put("v", 1).put("requests", array).toString().toByteArray(Charsets.UTF_8))
    }

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
