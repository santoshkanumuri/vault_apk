package com.privatevault.app.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * This device's usage record ([ItemUse] per id), in a [SealedFile]. It never syncs and never enters a backup. It holds
 * ids, counts and times only, so it can be written while the vault is locked: a keyboard suggestion is picked after
 * the picker has closed. Writes run on one background thread, in order.
 */
internal object ItemUsageStore {
    /** Keyboard picks already counted, so a fill event read twice counts once. */
    private const val MAX_EVENTS = 32
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "nuvori-usage").apply { isDaemon = true } }

    private data class Record(val uses: Map<String, ItemUse>, val events: List<String>)

    private fun file(context: Context) = SealedFile(context, "item-usage", "nuvori_item_usage_v1", 256 * 1024)

    /** One use of [id] now. Fire and forget: a failure only loses this one use. */
    fun record(context: Context, id: String) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        writer.execute { runCatching { update(app) { it.copy(uses = ItemUsage.record(it.uses, id, now)) } } }
    }

    /** A keyboard suggestion was picked. [eventKey] names that pick; a key seen before is not counted again. */
    fun recordKeyboardPick(context: Context, eventKey: String, id: String) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        writer.execute {
            runCatching {
                update(app) { record ->
                    if (eventKey in record.events) record
                    else Record(ItemUsage.record(record.uses, id, now), (record.events + eventKey).takeLast(MAX_EVENTS))
                }
            }
        }
    }

    /** Waits until every write sent so far is on disk. For tests. */
    fun awaitWrites() { writer.submit { }.get() }

    /**
     * Forgets every use on this device ("Clear Most used" in Autofill and codes). It runs after the writes sent so
     * far, so none of them comes back. Blocks until done: call off the main thread.
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        writer.submit { synchronized(this) { file(app).delete() } }.get()
    }

    /** The record as it is now. Call off the main thread. An unreadable record reads as empty. */
    fun snapshot(context: Context): UsageSnapshot =
        UsageSnapshot(runCatching { read(context.applicationContext).uses }.getOrDefault(emptyMap()), System.currentTimeMillis())

    @Synchronized private fun update(context: Context, change: (Record) -> Record) {
        val record = change(runCatching { read(context) }.getOrDefault(Record(emptyMap(), emptyList())))
        write(context, record)
    }

    @Synchronized private fun read(context: Context): Record {
        val plain = file(context).read() ?: return Record(emptyMap(), emptyList())
        val json = JSONObject(plain.toString(Charsets.UTF_8))
        val items = json.getJSONObject("items")
        val uses = items.keys().asSequence().associateWith { id ->
            val value = items.getJSONArray(id)
            ItemUse(value.getDouble(0), value.getInt(1), value.getLong(2))
        }
        val events = json.optJSONArray("events")?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty()
        return Record(uses, events)
    }

    private fun write(context: Context, record: Record) {
        val items = JSONObject()
        record.uses.forEach { (id, use) -> items.put(id, JSONArray().put(use.score).put(use.count).put(use.lastUsedAt)) }
        file(context).write(JSONObject().put("v", 1).put("items", items).put("events", JSONArray(record.events))
            .toString().toByteArray(Charsets.UTF_8))
    }
}
