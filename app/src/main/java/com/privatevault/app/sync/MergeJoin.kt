package com.privatevault.app.sync

import android.content.Context
import androidx.annotation.Keep
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.backup.BackupData
import com.privatevault.app.backup.VaultBackupManager
import com.privatevault.app.data.SyncConflictEntity
import com.privatevault.app.data.SyncLineageEntity
import com.privatevault.app.data.SyncLostManagerEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.TrashedEntry
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.security.EncryptedPhotoStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The merge join rule (feature [FEATURE], shared vectors `sync-vectors/merge-join-v1.json` from
 * scripts/sync-vectors/lineage_merge.py; Windows `merge_join.rs`). A device that joins a group with
 * items of its own matches each item by its ID against the group's copy. Items that differ become
 * conflicts to review on every device; nothing is decided by "newest wins".
 */
internal object MergeJoinRules {
    const val FEATURE = "merge-join-v1"

    enum class Outcome { SAME, KEEP_HOST, DROP, ADD, CONFLICT }
    enum class HostState { PRESENT, DELETED, ABSENT }

    /** The fields conflict review shows. lastOpenedAt and updatedAt never make two copies differ. */
    val ENTRY_FIELDS = listOf("type", "title", "primaryValue", "secondaryValue", "tertiaryValue", "fourthValue",
        "notes", "tags", "favorite", "sortOrder", "color", "network", "linkedAuthenticatorId", "linkedApps",
        "autofillSignatures", "autofillOrigins", "cardKind", "totpAlgorithm", "totpDigits", "totpPeriod")
    val GROUP_FIELDS = listOf("name", "notes", "folderType")
    val PASSKEY_FIELDS = listOf("id", "rpId", "userHandle", "username", "displayName", "privateKey", "publicKey", "createdAt")

    /** One field as text: missing or null is empty, booleans are true/false, numbers are integers. */
    fun normalize(value: JsonElement?): String = when {
        value == null || value.isJsonNull -> ""
        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean.toString()
        value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asBigDecimal.toLong().toString()
        value.isJsonPrimitive -> value.asString
        else -> value.toString()
    }

    /** [left] and [right] are payload-shaped: `{"entry": …, "groupIds": […]}`, `{"group": …}` or `{"passkey": …}`. */
    fun sameContent(entityType: String, left: JsonObject, right: JsonObject): Boolean {
        fun same(field: String, fields: List<String>) = left.getAsJsonObject(field).let { a ->
            right.getAsJsonObject(field).let { b -> fields.all { normalize(a.get(it)) == normalize(b.get(it)) } } }
        return when (entityType) {
            "entry" -> same("entry", ENTRY_FIELDS) && groupIds(left) == groupIds(right)
            "group" -> same("group", GROUP_FIELDS)
            "passkey" -> same("passkey", PASSKEY_FIELDS)
            else -> throw IllegalArgumentException("Unsupported merge entity")
        }
    }

    fun sameContent(entityType: String, left: JSONObject, right: JSONObject): Boolean = sameContent(entityType,
        JsonParser.parseString(left.toString()).asJsonObject, JsonParser.parseString(right.toString()).asJsonObject)

    /** SHA-256 of the compared fields of a payload ([sameContent]): equal digests mean the same content. */
    fun contentDigest(entityType: String, payload: JSONObject): String {
        val json = JsonParser.parseString(payload.toString()).asJsonObject
        fun fields(name: String, list: List<String>) = json.getAsJsonObject(name).let { o -> list.map { normalize(o.get(it)) } }
        val values = when (entityType) {
            "entry" -> fields("entry", ENTRY_FIELDS) + listOf(groupIds(json).joinToString(","))
            "group" -> fields("group", GROUP_FIELDS)
            "passkey" -> fields("passkey", PASSKEY_FIELDS)
            else -> throw IllegalArgumentException("Unsupported merge entity")
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun groupIds(payload: JsonObject): List<String> =
        payload.getAsJsonArray("groupIds")?.map { it.asString }?.sorted().orEmpty()

    /**
     * [joinerOld] is the joiner's version of the item in the old group, [lineage] the host's (record
     * or delete), both null when unknown. [sharedLineage]: both come from the same old group.
     * [joinerUnchanged]: the joiner's copy is still the one it had in the old group (its content
     * digest matches the lineage row, [contentDigest]). An item edited after leaving was never seen
     * by the group, so it becomes a conflict instead of being replaced or dropped.
     */
    fun decide(sameContent: Boolean, host: HostState, sharedLineage: Boolean, joinerOld: RecordVersion?,
        lineage: RecordVersion?, joinerUnchanged: Boolean = true): Outcome {
        if (host == HostState.PRESENT && sameContent) return Outcome.SAME
        val seen = sharedLineage && joinerUnchanged && lineage != null && joinerOld != null &&
            joinerOld.relationTo(lineage) in setOf(VersionRelation.BEFORE, VersionRelation.EQUAL)
        if (seen) return if (host == HostState.PRESENT) Outcome.KEEP_HOST else Outcome.DROP
        return if (host == HostState.ABSENT) Outcome.ADD else Outcome.CONFLICT
    }
}

/**
 * "Mark as lost" on a surviving device (local only, no wire change). It keeps the first
 * [Freeze.membershipCount] signed membership events of the old group and refuses changes by the
 * lost device after its last known change, with the [RemovalCutoff.allows] predicate.
 */
internal object LostManagerFreeze {
    @Keep
    data class Freeze(val deviceId: String, val membershipCount: Long, val headSequence: Long, val headHash: String)

    fun of(entity: SyncLostManagerEntity?): Freeze? =
        entity?.let { Freeze(it.deviceId, it.membershipCount, it.headSequence, it.headHash) }

    fun allowsChange(freeze: Freeze?, deviceId: String, sequence: Long, hash: String): Boolean =
        freeze == null || deviceId != freeze.deviceId ||
            sequence in 1..freeze.headSequence && (sequence != freeze.headSequence || hash == freeze.headHash)

    fun <T> keptEvents(freeze: Freeze?, events: List<T>): List<T> =
        if (freeze == null) events else events.take(freeze.membershipCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
}

/** One item's version in the old group, without its payload (snapshot v4 `lineage`). */
@Keep
internal data class LineageRecord(val entityType: String, val entityId: String, val recordVersionJson: String)

@Keep
internal data class SnapshotLineage(val fromVaultId: String, val records: List<LineageRecord>,
    val tombstones: List<LineageRecord>) {
    fun validate() {
        @Suppress("SENSELESS_COMPARISON")
        require(fromVaultId != null && fromVaultId.isNotBlank() && records != null && tombstones != null &&
            records.size + tombstones.size <= MAX_ROWS) { "Invalid sync lineage" }
        (records + tombstones).forEach {
            @Suppress("SENSELESS_COMPARISON")
            require(it.entityType != null && it.entityType.isNotBlank() && it.entityId != null && it.entityId.isNotBlank() &&
                it.recordVersionJson != null) { "Invalid sync lineage" }
            RecordVersion.parse(it.recordVersionJson).validate()
        }
    }

    companion object {
        const val MAX_ROWS = 200_000
        fun parse(element: JsonElement?): SnapshotLineage? {
            if (element == null || element.isJsonNull) return null
            return Gson().fromJson(element, SnapshotLineage::class.java).also { it.validate() }
        }
        fun of(rows: List<SyncLineageEntity>): SnapshotLineage? {
            val from = rows.firstOrNull()?.fromVaultId ?: return null
            val own = rows.filter { it.fromVaultId == from }
            return SnapshotLineage(from, own.filter { !it.deleted }.map { LineageRecord(it.entityType, it.entityId, it.recordVersionJson) },
                own.filter { it.deleted }.map { LineageRecord(it.entityType, it.entityId, it.recordVersionJson) })
        }
    }
}

/** Joining a group while keeping this device's items, matched by ID ([MergeJoinRules]). */
internal object MergeJoin {
    const val BACKUP_DAYS = 7L
    private val MERGED_TYPES = setOf("entry", "group", "passkey")

    data class Summary(val alreadyThere: Int = 0, val conflicts: Int = 0, val added: Int = 0, val dropped: Int = 0) {
        fun describe(): String? = listOfNotNull(
            alreadyThere.takeIf { it > 0 }?.let { if (it == 1) "1 item was already in the group." else "$it items were already in the group." },
            added.takeIf { it > 0 }?.let { if (it == 1) "1 item from this device was added." else "$it items from this device were added." },
            conflicts.takeIf { it > 0 }?.let { if (it == 1) "1 item differs from the group's copy. Review it under Sync conflicts."
                else "$it items differ from the group's copies. Review them under Sync conflicts." },
            dropped.takeIf { it > 0 }?.let { if (it == 1) "1 item was deleted in the group. It is in Recently deleted."
                else "$it items were deleted in the group. They are in Recently deleted." },
        ).joinToString(" ").ifBlank { null }
    }

    /** This device's items and old-group versions, read in the commit transaction before the snapshot replaces them. */
    class Local(val vaultId: String, val shared: Boolean, val entries: List<Pair<VaultEntry, List<String>>>,
        val photos: List<VaultPhoto>, val groups: List<VaultGroup>, val passkeys: List<VaultPasskey>,
        val records: Map<Pair<String, String>, String>, val deleted: Set<Pair<String, String>>,
        val lineage: List<SyncLineageEntity>)

    suspend fun readLocal(database: VaultDatabase): Local {
        val dao = database.dao()
        val sync = database.syncDao()
        val vaultId = requireNotNull(dao.settings()).vaultId
        val entries = dao.allEntries()
        return Local(vaultId, sync.memberships(vaultId).size >= 2,
            entries.map { it.entry to dao.groupIdsOf(it.entry.id) }, entries.flatMap { it.photos },
            dao.allGroupsWithEntries().map { it.group }, dao.allPasskeys(),
            sync.recordStates().associate { (it.entityType to it.entityId) to it.recordVersionJson },
            sync.tombstones().mapTo(hashSetOf()) { it.entityType to it.entityId }, sync.lineage())
    }

    /**
     * What a device keeps of a group it leaves: each item's version, whether it was deleted, and a
     * digest of its content then ([digests], [MergeJoinRules.contentDigest]), so a later merge join
     * can tell an item edited after leaving from the copy the group saw.
     */
    fun lineageRows(fromVaultId: String, records: List<SyncRecordStateEntity>, tombstones: List<SyncTombstoneEntity>,
        digests: Map<Pair<String, String>, String>): List<SyncLineageEntity> {
        val deleted = tombstones.associateBy { it.entityType to it.entityId }
        return records.filter { it.entityType in MERGED_TYPES }.map { record ->
            SyncLineageEntity(fromVaultId, record.entityType, record.entityId, record.recordVersionJson,
                deleted.containsKey(record.entityType to record.entityId), digests[record.entityType to record.entityId].orEmpty())
        }
    }

    /** The content digest of every entry, folder and passkey in the vault now. */
    suspend fun contentDigests(database: VaultDatabase): Map<Pair<String, String>, String> {
        val dao = database.dao()
        return dao.allEntries().associate { ("entry" to it.entry.id) to
                MergeJoinRules.contentDigest("entry", entryPayload(it.entry, dao.groupIdsOf(it.entry.id))) } +
            dao.allGroupsWithEntries().associate { ("group" to it.group.id) to MergeJoinRules.contentDigest("group", groupPayload(it.group)) } +
            dao.allPasskeys().associate { ("passkey" to it.id) to MergeJoinRules.contentDigest("passkey", passkeyPayload(it)) }
    }

    private fun contentDigests(local: Local): Map<Pair<String, String>, String> =
        local.entries.associate { (entry, groups) -> ("entry" to entry.id) to MergeJoinRules.contentDigest("entry", entryPayload(entry, groups)) } +
            local.groups.associate { ("group" to it.id) to MergeJoinRules.contentDigest("group", groupPayload(it)) } +
            local.passkeys.associate { ("passkey" to it.id) to MergeJoinRules.contentDigest("passkey", passkeyPayload(it)) }

    /** [lineageRows] of the group [local] leaves now. */
    fun lineageRows(local: Local): List<SyncLineageEntity> {
        val digests = contentDigests(local)
        return local.records.filterKeys { it.first in MERGED_TYPES }
            .map { (key, version) -> SyncLineageEntity(local.vaultId, key.first, key.second, version, key in local.deleted, digests[key].orEmpty()) }
    }

    fun entryPayload(entry: VaultEntry, groupIds: Collection<String>): JSONObject =
        JSONObject().put("entry", entry.toSyncJson()).put("groupIds", JSONArray(groupIds.sorted()))
    fun groupPayload(group: VaultGroup): JSONObject = JSONObject().put("group", JSONObject().put("id", group.id)
        .put("name", group.name).put("notes", group.notes).put("folderType", group.folderType?.name ?: ""))
    fun passkeyPayload(passkey: VaultPasskey): JSONObject = JSONObject().put("passkey", JSONObject()
        .put("id", passkey.id).put("rpId", passkey.rpId).put("userHandle", passkey.userHandle)
        .put("username", passkey.username).put("displayName", passkey.displayName)
        .put("privateKey", passkey.privateKey).put("publicKey", passkey.publicKey).put("createdAt", passkey.createdAt))

    /**
     * Call inside the commit transaction, after the snapshot is stored. Applies [MergeJoinRules] to
     * every item in [local] and signs what this device brings in. Returns the summary and the photos
     * to publish after the commit ([publishPhotos]).
     */
    suspend fun apply(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, local: Local,
        state: SnapshotSyncState, host: BackupData, now: Long = System.currentTimeMillis()): Pair<Summary, List<VaultPhoto>> {
        val lineage = SnapshotLineage.parse(state.lineage)
        val dao = database.dao()
        val sync = database.syncDao()
        val vaultId = state.vaultId
        val me = identityStore.getOrCreate().deviceId
        val keyring = state.keyring()
        val contentKey = keyring.writing()
        try {
            val records = state.records.associateBy { it.entityType to it.entityId }
            val tombstones = state.tombstones.associateBy { it.entityType to it.entityId }
            val hostOld = lineage?.let { (it.records + it.tombstones).associate { row -> (row.entityType to row.entityId) to row.recordVersionJson } }.orEmpty()
            val viaMembership = lineage != null && local.shared && local.vaultId == lineage.fromVaultId
            val viaLineage = lineage != null && local.lineage.any { it.fromVaultId == lineage.fromVaultId }
            val shared = viaMembership || viaLineage
            fun joinerOld(key: Pair<String, String>): RecordVersion? = when {
                viaMembership -> local.records[key]
                viaLineage -> local.lineage.firstOrNull { it.entityType == key.first && it.entityId == key.second }?.recordVersionJson
                else -> null
            }?.let(RecordVersion::parse)
            // Through saved lineage the old version is from when this device left: it counts only while
            // the item is still the copy it had then (a missing digest counts as edited).
            val digests = if (viaLineage && !viaMembership) contentDigests(local) else emptyMap()
            fun joinerUnchanged(key: Pair<String, String>): Boolean = viaMembership || !viaLineage ||
                local.lineage.firstOrNull { it.entityType == key.first && it.entityId == key.second }
                    ?.contentDigest?.takeIf { it.isNotEmpty() }?.let { it == digests[key] } == true
            fun hostState(key: Pair<String, String>, present: Boolean) = when {
                present -> MergeJoinRules.HostState.PRESENT
                tombstones.containsKey(key) -> MergeJoinRules.HostState.DELETED
                else -> MergeJoinRules.HostState.ABSENT
            }
            fun outcome(type: String, id: String, mine: JSONObject, theirs: JSONObject?) = MergeJoinRules.decide(
                theirs != null && MergeJoinRules.sameContent(type, mine, theirs), hostState(type to id, theirs != null), shared,
                joinerOld(type to id), hostOld[type to id]?.let(RecordVersion::parse), joinerUnchanged(type to id))

            val hostGroups = host.groups.associateBy { it.id }
            val hostEntries = host.entries.associateBy { it.id }
            val hostLinks = host.links.groupBy({ it.entryId }, { it.groupId })
            val hostPasskeys = host.passkeys.associateBy { it.id }
            val groupOutcomes = local.groups.associate { it.id to outcome("group", it.id, groupPayload(it), hostGroups[it.id]?.let(::groupPayload)) }
            val entryOutcomes = local.entries.associate { (entry, groups) ->
                entry.id to outcome("entry", entry.id, entryPayload(entry, groups),
                    hostEntries[entry.id]?.let { entryPayload(it, hostLinks[it.id].orEmpty()) })
            }
            val passkeyOutcomes = local.passkeys.associate { it.id to outcome("passkey", it.id, passkeyPayload(it), hostPasskeys[it.id]?.let(::passkeyPayload)) }
            val mine = setOf(MergeJoinRules.Outcome.ADD, MergeJoinRules.Outcome.CONFLICT)
            val finalGroups = hostGroups.keys + groupOutcomes.filterValues { it in mine }.keys
            val finalEntries = hostEntries.keys + entryOutcomes.filterValues { it in mine }.keys

            var summary = Summary()
            fun count(outcome: MergeJoinRules.Outcome) {
                summary = when (outcome) {
                    MergeJoinRules.Outcome.SAME, MergeJoinRules.Outcome.KEEP_HOST -> summary.copy(alreadyThere = summary.alreadyThere + 1)
                    MergeJoinRules.Outcome.ADD -> summary.copy(added = summary.added + 1)
                    MergeJoinRules.Outcome.CONFLICT -> summary.copy(conflicts = summary.conflicts + 1)
                    MergeJoinRules.Outcome.DROP -> summary.copy(dropped = summary.dropped + 1)
                }
            }

            /**
             * Signs this device's copy. For a conflict the version is fresh for this device, so it is
             * CONCURRENT with the group's at every device, and both copies show for review. False when
             * no reviewable conflict can be made (the group's copy has no signed version here).
             */
            suspend fun sign(type: String, id: String, payload: JSONObject, outcome: MergeJoinRules.Outcome): Boolean {
                val key = type to id
                val record = records[key]
                if (outcome == MergeJoinRules.Outcome.ADD) {
                    val version = RecordVersion(mapOf(me to Math.addExact(record?.let { RecordVersion.parse(it.recordVersionJson).counters[me] } ?: 0L, 1L)))
                    SyncPayloadCrypto.signAndStore(database, identityStore, vaultId, contentKey, type, id, ChangeKind.UPSERT,
                        payload, version, record?.revision ?: 0L)
                    sync.upsertRecordState(SyncRecordStateEntity(type, id, (record?.revision ?: 0L) + 1L, version.toJson()))
                    return true
                }
                record ?: return false
                val hostVersion = RecordVersion.parse(record.recordVersionJson)
                val remoteHash = tombstones[key]?.takeIf { hostState(key, false) == MergeJoinRules.HostState.DELETED &&
                    RecordVersion.parse(it.recordVersionJson) == hostVersion }?.changeHash
                    ?: state.operations.firstOrNull { it.entityType == type && it.entityId == id &&
                        RecordVersion.parse(it.recordVersionJson) == hostVersion }?.hash ?: return false
                val version = RecordVersion(mapOf(me to Math.addExact(hostVersion.counters[me] ?: 0L, 1L)))
                if (version.relationTo(hostVersion) != VersionRelation.CONCURRENT) return false
                val change = SyncPayloadCrypto.signAndStore(database, identityStore, vaultId, contentKey, type, id,
                    ChangeKind.UPSERT, payload, version, record.revision)
                sync.upsertRecordState(SyncRecordStateEntity(type, id, record.revision + 1L, version.toJson()))
                sync.insertConflict(SyncConflictEntity(UUID.randomUUID().toString(), type, id, change.hash, remoteHash,
                    version.toJson(), hostVersion.toJson(), Instant.ofEpochMilli(now).toString()))
                return true
            }

            for (group in local.groups.sortedBy { it.id }) {
                val outcome = requireNotNull(groupOutcomes[group.id])
                count(outcome)
                if (outcome !in mine) continue
                val previous = hostGroups[group.id]
                dao.insertGroup(group)
                if (!sign("group", group.id, groupPayload(group), outcome)) {
                    // The group's copy has no signed version: keep it, as an older build would.
                    if (previous != null) dao.insertGroup(previous) else dao.deleteGroupById(group.id)
                    summary = summary.copy(conflicts = summary.conflicts - 1, alreadyThere = summary.alreadyThere + 1)
                }
            }
            // Codes first, so a login keeps its link to a code that came along.
            val ordered = local.entries.sortedWith(compareBy({ it.first.type != com.privatevault.app.data.EntryType.AUTHENTICATOR }, { it.first.id }))
            val keptPhotos = mutableListOf<VaultPhoto>()
            val hostPhotoIds = host.photos.mapTo(hashSetOf()) { it.id }
            val hostCovers = host.photos.filter { it.isCover }.mapTo(hashSetOf()) { it.entryId }
            for ((original, groups) in ordered) {
                val outcome = requireNotNull(entryOutcomes[original.id])
                count(outcome)
                when (outcome) {
                    MergeJoinRules.Outcome.DROP -> {
                        dao.insertTrash(TrashedEntry(entryId = original.id, type = original.type, title = original.title,
                            entryJson = with(SyncEntryCodec) { original.toTrashJson() }, groupIds = groups.joinToString("\n"),
                            deletedAt = now, deletedElsewhere = true))
                        continue
                    }
                    MergeJoinRules.Outcome.SAME -> {}
                    MergeJoinRules.Outcome.KEEP_HOST -> continue
                    MergeJoinRules.Outcome.ADD, MergeJoinRules.Outcome.CONFLICT -> {
                        val entry = original.copy(linkedAuthenticatorId = original.linkedAuthenticatorId
                            .takeIf { it in finalEntries }.orEmpty())
                        val links = groups.filter { it in finalGroups }.toSet()
                        val previous = hostEntries[entry.id]
                        dao.saveEntryExact(entry, links)
                        if (!sign("entry", entry.id, entryPayload(entry, links), outcome)) {
                            // No reviewable conflict: the group's copy stays, this copy goes to Recently deleted.
                            if (previous != null) dao.saveEntryExact(previous, hostLinks[previous.id].orEmpty().toSet())
                            else dao.deleteEntryRow(entry.id)
                            dao.insertTrash(TrashedEntry(entryId = original.id, type = original.type, title = original.title,
                                entryJson = with(SyncEntryCodec) { original.toTrashJson() }, groupIds = groups.joinToString("\n"),
                                deletedAt = now, deletedElsewhere = true))
                            summary = summary.copy(conflicts = summary.conflicts - 1, dropped = summary.dropped + 1)
                            continue
                        }
                    }
                }
                // Photos are matched by ID: one the group already has (or deleted) stays the group's.
                local.photos.filter { it.entryId == original.id && it.id !in hostPhotoIds &&
                    !tombstones.containsKey("photo" to it.id) }.forEach { photo ->
                    val kept = if (photo.isCover && original.id in hostCovers) photo.copy(isCover = false) else photo
                    dao.insertPhoto(kept)
                    keptPhotos += kept
                }
            }
            for (passkey in local.passkeys.sortedBy { it.id }) {
                val outcome = requireNotNull(passkeyOutcomes[passkey.id])
                count(outcome)
                if (outcome !in mine) continue
                val previous = hostPasskeys[passkey.id]
                dao.deletePasskey(passkey.id)
                dao.insertPasskeys(listOf(passkey))
                if (!sign("passkey", passkey.id, passkeyPayload(passkey), outcome)) {
                    dao.deletePasskey(passkey.id)
                    if (previous != null) dao.insertPasskeys(listOf(previous))
                    summary = summary.copy(conflicts = summary.conflicts - 1, alreadyThere = summary.alreadyThere + 1)
                }
            }
            return summary to keptPhotos
        } finally { keyring.wipe() }
    }

    /**
     * After the commit: signs the photos of merged items, so they reach the group. A stop before this
     * leaves them on this device only; they are still in the vault.
     */
    suspend fun publishPhotos(context: Context, database: VaultDatabase, photos: List<VaultPhoto>, localKey: ByteArray) {
        if (photos.isEmpty()) return
        val writer = LocalPhotoChangeWriter(database, AndroidDeviceIdentityStore(context),
            LanSyncService.store(context).photoBlobs, EncryptedPhotoStore(context))
        photos.forEach { photo -> runCatching { database.dao().photo(photo.id)?.let { writer.save(it, localKey) } } }
    }

    /**
     * The host, before it sends a snapshot to a device that may bring items: signs a first version of
     * each of its own items that has none yet (items from before the group, never edited since). A
     * joiner's other copy of such an item is then CONCURRENT with it on every device, so it becomes a
     * conflict to review instead of replacing the item. The new changes carry the same content.
     */
    suspend fun adoptUntracked(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, localKey: ByteArray): Int =
        database.withTransaction {
            val dao = database.dao()
            val sync = database.syncDao()
            val vaultId = requireNotNull(dao.settings()).vaultId
            val me = identityStore.getOrCreate().deviceId
            database.requireLocalSyncAuthor(vaultId, me)
            val tracked = sync.recordStates().mapTo(hashSetOf()) { it.entityType to it.entityId }
            val keyring = database.syncKeyring(localKey)
            val key = keyring.writing()
            var signed = 0
            try {
                suspend fun adopt(type: String, id: String, payload: JSONObject) {
                    if ((type to id) in tracked) return
                    val version = RecordVersion(mapOf(me to 1L))
                    SyncPayloadCrypto.signAndStore(database, identityStore, vaultId, key, type, id, ChangeKind.UPSERT, payload, version, 0L)
                    sync.upsertRecordState(SyncRecordStateEntity(type, id, 1L, version.toJson()))
                    signed++
                }
                dao.allGroupsWithEntries().map { it.group }.sortedBy { it.id }.forEach { adopt("group", it.id, groupPayload(it)) }
                dao.allEntries().sortedBy { it.entry.id }.forEach { adopt("entry", it.entry.id, entryPayload(it.entry, dao.groupIdsOf(it.entry.id))) }
                dao.allPasskeys().sortedBy { it.id }.forEach { adopt("passkey", it.id, passkeyPayload(it)) }
            } finally { keyring.wipe() }
            signed
        }

    /**
     * A local backup before a merge join writes into a populated vault. It opens with this device's
     * master password, like any Nuvori backup. Backups older than [BACKUP_DAYS] are removed.
     */
    suspend fun backupBeforeJoin(context: Context, database: VaultDatabase, password: CharArray, localKey: ByteArray,
        now: Long = System.currentTimeMillis()): File {
        val folder = backupFolder(context).apply { mkdirs() }
        pruneBackups(context, now)
        val target = File(folder, "before-join-$now.pvault")
        val partial = File(folder, "before-join-$now.partial")
        try {
            FileOutputStream(partial).use { output ->
                VaultBackupManager(context, database.dao(), EncryptedPhotoStore(context)).export(output, password, localKey)
                output.fd.sync()
            }
            check(partial.renameTo(target)) { "Could not save the backup before joining" }
        } finally { partial.delete() }
        return target
    }

    fun backupFolder(context: Context) = File(context.filesDir, "join-backups")

    fun pruneBackups(context: Context, now: Long = System.currentTimeMillis()) {
        backupFolder(context).listFiles().orEmpty().filter {
            it.isFile && now - it.lastModified() > BACKUP_DAYS * 24 * 60 * 60 * 1000
        }.forEach { it.delete() }
    }
}

/** "Mark ‹device› as lost": freezes the old group on this device. Only a member that does not manage it. */
internal suspend fun VaultDatabase.markManagerLost(identityStore: AndroidDeviceIdentityStore,
    now: Long = System.currentTimeMillis()): SyncLostManagerEntity = withTransaction {
    val identity = identityStore.getOrCreate()
    val vaultId = requireNotNull(dao().settings()).vaultId
    val sync = syncDao()
    val events = sync.membershipEvents(vaultId)
    val verified = SyncMembershipManager.verify(events.map { it.toEvent() })
    require(verified.members.any { it.deviceId == identity.deviceId && it.status == MemberStatus.ACTIVE.name } &&
        verified.members.count { it.status == MemberStatus.ACTIVE.name } >= 2) {
        "This device is not in an active shared sync group"
    }
    require(verified.managerDeviceId != identity.deviceId) { "This device manages the group" }
    val head = sync.deviceHead(vaultId, verified.managerDeviceId)
    SyncLostManagerEntity(vaultId, verified.managerDeviceId, events.size.toLong(), head?.sequence ?: 0L,
        head?.hash ?: GENESIS_HASH, now).also { sync.saveLostManager(it) }
}

/** The managing device is back: the freeze ends. Nothing else changed. */
internal suspend fun VaultDatabase.clearManagerLost() = syncDao().clearLostManager()
