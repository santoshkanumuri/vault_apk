package com.privatevault.app.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One item that another device changed after this device last saw it.
 *
 * [revision] grows by one for every recorded change in this process, so an editor can snapshot
 * [RemoteEntryChanges.revision] when it opens and later ask whether anything newer arrived.
 * [deleted] means the item no longer exists here. [conflict] means the incoming edit was concurrent
 * with this device's copy, so the local copy was kept and a conflict is waiting for review.
 * [photosOnly] means only the item's photos or cover changed; its text fields are unchanged.
 */
data class RemoteEntryChange(
    val entryId: String,
    val revision: Long,
    val deleted: Boolean,
    val conflict: Boolean,
    val photosOnly: Boolean,
)

/**
 * Process-wide signal of entries changed by other devices. It holds item IDs only, never content,
 * so it is safe to keep while locked; it is still cleared on lock to avoid stale banners.
 */
object RemoteEntryChanges {
    private const val MAX_TRACKED = 512
    private val mutableChanges = MutableStateFlow<Map<String, RemoteEntryChange>>(emptyMap())
    private val mutableRevision = MutableStateFlow(0L)

    /** Latest remote change per entry ID. Entries stay until acknowledged or cleared. */
    val changes: StateFlow<Map<String, RemoteEntryChange>> = mutableChanges.asStateFlow()

    /** Snapshot this when an editor opens; compare with a change's revision later. */
    fun revision(): Long = mutableRevision.value

    /** The newest change for [entryId] recorded after [sinceRevision], or null. */
    fun changedSince(entryId: String, sinceRevision: Long): RemoteEntryChange? =
        mutableChanges.value[entryId]?.takeIf { it.revision > sinceRevision }

    fun acknowledge(entryId: String) {
        mutableChanges.update { it - entryId }
    }

    fun clear() {
        mutableChanges.value = emptyMap()
    }

    @Synchronized internal fun record(entryId: String, deleted: Boolean, conflict: Boolean = false,
        photosOnly: Boolean = false) {
        if (entryId.isBlank()) return
        val revision = mutableRevision.value + 1
        mutableRevision.value = revision
        mutableChanges.update { current ->
            val previous = current[entryId]
            val change = RemoteEntryChange(entryId, revision, deleted,
                conflict || (previous?.conflict == true && !deleted),
                photosOnly && (previous == null || previous.photosOnly))
            val next = LinkedHashMap(current)
            next.remove(entryId)
            next[entryId] = change
            while (next.size > MAX_TRACKED) next.remove(next.keys.first())
            next
        }
    }
}
