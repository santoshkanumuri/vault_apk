package com.privatevault.app.sync

import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncCheckpointEntity
import com.privatevault.app.data.SyncConflictEntity
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.VaultDatabase
import java.time.Instant

/**
 * History compaction on this device (no wire change). A change of author A is deleted when every
 * active member already applied it (its sequence is at or below floor(A)) and it is not in the
 * [KeepSet]. Peers that still need a change pin the floor, so it stays. Shared vectors:
 * `sync-vectors/compaction-v1.json` (scripts/sync-vectors/compaction.py).
 *
 * Nothing is deleted on Google Drive here, and no device is removed automatically: a member that
 * has not synced for months only gets a suggestion ([blockingHint]).
 */
internal object HistoryCompaction {
    const val BATCH = 2_000
    const val INTERVAL_MS = 24L * 60 * 60 * 1000
    const val RESOLVED_CONFLICT_MS = 30L * 24 * 60 * 60 * 1000
    const val HINT_AFTER_MS = 90L * 24 * 60 * 60 * 1000
    const val HINT_CHANGES = 5_000L

    data class Floor(val sequence: Long, val hash: String)

    /** This device's compaction record: when it last ran, and per author the highest change it deleted. */
    data class Record(val at: Long, val floor: Map<String, Floor>)

    /**
     * floor(A) = min over ACTIVE members M (this device included) of the head of A that M applied;
     * a member that never reported gives 0. With Drive sync on ([driveCovered] not null) it is also at
     * most the drive's gap-free coverage of A, so the drive can still get every change from someone.
     */
    fun floors(localDeviceId: String, members: List<SyncMembershipEntity>, localApplied: Map<String, Long>,
        peerApplied: Map<String, Map<String, Long>>, driveCovered: Map<String, Long>?): Map<String, Long> {
        val active = members.filter { it.status == MemberStatus.ACTIVE.name }.map { it.deviceId }
        return localApplied.keys.associateWith { author ->
            val pinned = active.minOfOrNull { member ->
                if (member == localDeviceId) localApplied[author] ?: 0L
                else peerApplied[member]?.get(author) ?: 0L
            } ?: 0L
            if (driveCovered == null) pinned else minOf(pinned, driveCovered[author] ?: 0L)
        }
    }

    /** The hashes to delete: at or below the floor, not kept, and not this device's applied head. */
    fun deletable(changes: List<KeepSet.Change>, keep: Set<String>, floors: Map<String, Long>,
        localApplied: Map<String, Long>): List<String> = changes.filter { change ->
        change.sequence <= (floors[change.deviceId] ?: 0L) && change.hash !in keep &&
            change.sequence != localApplied[change.deviceId]
    }.map { it.hash }

    fun encode(record: Record): String = JsonObject().apply {
        addProperty("at", record.at)
        add("floor", JsonObject().apply {
            record.floor.toSortedMap().forEach { (author, floor) ->
                add(author, JsonArray().apply { add(floor.sequence); add(floor.hash) })
            }
        })
    }.toString()

    /** An empty or unreadable record counts as "never ran". */
    fun parse(json: String): Record = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        Record(root.get("at")?.asLong ?: 0L, root.getAsJsonObject("floor")?.entrySet().orEmpty().associate { (author, value) ->
            val pair = value.asJsonArray
            author to Floor(pair[0].asLong, pair[1].asString)
        })
    }.getOrDefault(Record(0L, emptyMap()))

    /** Resolved conflicts older than 30 days go, so their changes can go too. */
    fun expiredConflicts(conflicts: List<SyncConflictEntity>, now: Long): List<String> = conflicts.filter { conflict ->
        conflict.resolvedAtUtc.isNotEmpty() &&
            (runCatching { Instant.parse(conflict.resolvedAtUtc).toEpochMilli() }.getOrNull() ?: Long.MAX_VALUE) <
            now - RESOLVED_CONFLICT_MS
    }.map { it.conflictId }

    /**
     * Runs once per [INTERVAL_MS] (or when [force]) while the vault is unlocked. Deletes in
     * transactions of [BATCH] changes; each batch records the raised floor in the same transaction,
     * so a crash between batches leaves a smaller history that still follows the rule. Returns the
     * number of changes deleted.
     */
    suspend fun run(database: VaultDatabase, mirror: TransportMirror, cloud: CloudSyncState,
        now: Long = System.currentTimeMillis(), force: Boolean = false): Int {
        val dao = database.syncDao()
        val vaultId = database.dao().settings()?.vaultId ?: return 0
        if (mirror.vaultId != vaultId) return 0
        val stored = dao.checkpoint(vaultId)
        val record = parse(stored?.floorJson.orEmpty())
        if (!force && now - record.at in 0 until INTERVAL_MS) return 0
        val plan = database.withTransaction {
            expiredConflicts(dao.conflicts(), now).chunked(500).forEach { dao.deleteConflicts(it) }
            val heads = dao.deviceHeads(vaultId)
            val localApplied = heads.associate { it.deviceId to it.sequence }
            val peerApplied = mirror.peerProgress.mapValues { (_, progress) -> progress.applied.mapValues { it.value.sequence } }
            val drive = if (!cloud.enabled) null else if (cloud.vaultId == vaultId) cloud.covered else emptyMap()
            val floors = floors(mirror.localDeviceId, dao.memberships(vaultId), localApplied, peerApplied, drive)
            val changes = dao.operationSummaries(vaultId).map {
                KeepSet.Change(it.deviceId, it.sequence, it.hash, it.entityType, it.entityId, it.kind,
                    it.recordVersionJson, it.occurredAtUtc)
            }
            val keep = KeepSet.compute(changes, dao.recordStates(), dao.tombstones(), dao.conflicts())
            val byHash = changes.associateBy { it.hash }
            deletable(changes, keep, floors, localApplied).map { byHash.getValue(it) }
        }
        var floor = record.floor
        var deleted = 0
        plan.chunked(BATCH).forEach { batch ->
            database.withTransaction {
                // A change that became a tombstone's or an open conflict's meanwhile stays.
                val referenced = dao.tombstones().mapTo(hashSetOf()) { it.changeHash } +
                    dao.conflicts().filter { it.resolvedAtUtc.isEmpty() }.flatMap { listOf(it.localChangeHash, it.remoteChangeHash) }
                val gone = batch.filter { it.hash !in referenced }
                gone.chunked(500).forEach { deleted += dao.deleteOperations(it.map { change -> change.hash }) }
                gone.forEach { change ->
                    if (change.sequence > (floor[change.deviceId]?.sequence ?: 0L))
                        floor = floor + (change.deviceId to Floor(change.sequence, change.hash))
                }
                saveRecord(database, vaultId, stored, Record(record.at, floor), now)
            }
        }
        database.withTransaction { saveRecord(database, vaultId, dao.checkpoint(vaultId), Record(now, floor), now) }
        return deleted
    }

    private suspend fun saveRecord(database: VaultDatabase, vaultId: String, stored: SyncCheckpointEntity?,
        record: Record, now: Long) {
        val current = database.syncDao().checkpoint(vaultId) ?: stored
        database.syncDao().saveCheckpoint(SyncCheckpointEntity(vaultId, current?.checkpointJson.orEmpty(),
            encode(record), now))
    }

    /**
     * The suggestion for Devices & sync, or null: an active member that last synced more than 90 days
     * ago and alone keeps more than 5,000 changes from being cleaned up. Never an automatic action.
     */
    fun blockingHint(localDeviceId: String, members: List<SyncMembershipEntity>, localApplied: Map<String, Long>,
        peerApplied: Map<String, Map<String, Long>>, lastSeenAt: Map<String, Long>, now: Long): String? =
        blockingSuggestion(localDeviceId, members, localApplied, peerApplied, lastSeenAt, now)?.second

    /** [blockingHint] with the device it is about, so Devices & sync can link to removing it. */
    fun blockingSuggestion(localDeviceId: String, members: List<SyncMembershipEntity>, localApplied: Map<String, Long>,
        peerApplied: Map<String, Map<String, Long>>, lastSeenAt: Map<String, Long>, now: Long): Pair<String, String>? {
        val floors = floors(localDeviceId, members, localApplied, peerApplied, null)
        return members.filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != localDeviceId }
            .sortedBy { it.deviceId }.firstNotNullOfOrNull { member ->
                val seen = lastSeenAt[member.deviceId] ?: 0L
                if (now - seen <= HINT_AFTER_MS) return@firstNotNullOfOrNull null
                val without = floors(localDeviceId, members.filter { it.deviceId != member.deviceId }, localApplied,
                    peerApplied, null)
                val pinned = without.entries.sumOf { (author, value) -> value - (floors[author] ?: 0L) }
                if (pinned <= HINT_CHANGES) return@firstNotNullOfOrNull null
                val months = ((now - seen) / (30L * 24 * 60 * 60 * 1000)).coerceAtLeast(3)
                val name = member.displayName.ifBlank { "A device" }
                member.deviceId to ("$name last synced ${if (seen == 0L) "a long time" else "$months months"} ago. " +
                    "Nuvori keeps older sync history until it syncs. Remove it if you no longer use it.")
            }
    }
}
