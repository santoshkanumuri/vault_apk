package com.privatevault.app.sync

import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.TrashedEntry
import com.privatevault.app.data.VaultEntry

/**
 * What a forced leave (this device learned it was removed) does with changes it received but could
 * not apply: a change that waits for an earlier change, a photo or a linked item, or one that was set
 * aside. The leave drops the old group's keys, so these changes could never apply afterwards.
 *
 * Nothing is dropped silently. The newest unapplied version of each entry is kept in Recently
 * deleted (with the usual 30 days), unless this device already holds the same entry. The user can
 * restore it as a new item. Other kinds of change (folders, passkeys, photos) can't be kept there;
 * they are counted so the app can say so. Their authors still hold them. Deletes need nothing.
 */
internal object RemovalArchive {
    const val TRASH_ID_PREFIX = "unapplied-"

    /** An unapplied entry upsert, decrypted. */
    data class Candidate(val operation: SyncOperationEntity, val entry: VaultEntry, val groupIds: List<String>)

    data class Summary(val keptEntries: Int, val otherChanges: Int) {
        /** The sentence for the user, or null when nothing was waiting. */
        fun message(): String? {
            if (keptEntries == 0 && otherChanges == 0) return null
            val kept = if (keptEntries == 0) "" else
                "$keptEntries ${if (keptEntries == 1) "item that had" else "items that had"} not finished syncing " +
                    "${if (keptEntries == 1) "is" else "are"} in Recently deleted. Restore ${if (keptEntries == 1) "it" else "them"} there within 30 days."
            val other = if (otherChanges == 0) "" else
                "$otherChanges other ${if (otherChanges == 1) "change" else "changes"} (folders, passkeys or photos) " +
                    "could not be kept here. The devices that made them still have them."
            return listOf(kept, other).filter(String::isNotEmpty).joinToString(" ")
        }
    }

    /** Upserts of these types carry data that a forced leave would lose on this device. */
    private val COUNTED_TYPES = setOf("group", "passkey", "photo", "photo_cover")

    /**
     * The newest candidate per entry (by change time, then author and sequence), without the ones
     * whose entry this device already holds with the same content.
     */
    fun pick(candidates: List<Candidate>, local: Map<String, VaultEntry>): List<Candidate> =
        candidates.groupBy { it.operation.entityId }.values.map { versions ->
            versions.maxWith(compareBy<Candidate>({ it.operation.occurredAtUtc }, { it.operation.deviceId },
                { it.operation.sequence }))
        }.filter { candidate ->
            val held = local[candidate.operation.entityId]
            held == null || trashJson(held) != trashJson(candidate.entry)
        }.sortedBy { it.operation.hash }

    /** Unapplied changes other than entry upserts that held data (see [COUNTED_TYPES]). */
    fun otherChanges(operations: List<SyncOperationEntity>): Int =
        operations.count { it.kind == "upsert" && it.entityType in COUNTED_TYPES }

    private fun trashJson(entry: VaultEntry): String = with(SyncEntryCodec) { entry.toTrashJson() }

    /** The Recently deleted row for [candidate]. Its ID comes from the change, so a retry adds no copy. */
    fun trashRow(candidate: Candidate, now: Long) = TrashedEntry(id = TRASH_ID_PREFIX + candidate.operation.hash,
        entryId = candidate.entry.id, type = candidate.entry.type, title = candidate.entry.title,
        entryJson = trashJson(candidate.entry), groupIds = candidate.groupIds.joinToString("\n"),
        deletedAt = now, deletedElsewhere = false)
}
