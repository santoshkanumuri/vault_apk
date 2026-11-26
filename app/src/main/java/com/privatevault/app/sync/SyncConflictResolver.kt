package com.privatevault.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.notes.RichNoteMerge
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SyncConflictSide(val description: String, val device: String, val reportedAtUtc: String,
    val deleted: Boolean)

/** [canCombineNotes]: both sides are notes with different text, so "Keep both edits" is offered. */
data class SyncConflictReview(val id: String, val type: String, val current: SyncConflictSide,
    val incoming: SyncConflictSide, val currentVersion: String, val canCombineNotes: Boolean = false)

/**
 * Version arithmetic shared by conflict detection and resolution. Ordering never uses wall-clock
 * time, so every device reaches the same relation for the same pair of versions regardless of the
 * order in which the operations arrived.
 */
internal object ConflictVersions {
    /** How an incoming version relates to this device's record ([local] null when unseen). */
    fun incomingRelation(incoming: RecordVersion, local: RecordVersion?): VersionRelation =
        if (local == null) VersionRelation.AFTER else incoming.relationTo(local)

    /** The pointwise maximum of both histories plus one step for the resolving device. */
    fun resolution(local: RecordVersion, remote: RecordVersion, resolverDeviceId: String): RecordVersion {
        val counters = (local.counters.keys + remote.counters.keys).associateWith {
            maxOf(local.counters[it] ?: 0, remote.counters[it] ?: 0)
        }.toMutableMap()
        counters[resolverDeviceId] = Math.addExact(counters[resolverDeviceId] ?: 0L, 1L)
        return RecordVersion(counters)
    }

    /** The pointwise maximum of both histories: the version of a field merge ([EntryFieldMerge]), with no extra count. */
    fun join(local: RecordVersion, remote: RecordVersion): RecordVersion =
        RecordVersion((local.counters.keys + remote.counters.keys).associateWith {
            maxOf(local.counters[it] ?: 0L, remote.counters[it] ?: 0L)
        })

    /** The pointwise minimum, zero counters dropped: the version of the change both sides started from. */
    fun meet(local: RecordVersion, remote: RecordVersion): RecordVersion =
        RecordVersion((local.counters.keys intersect remote.counters.keys).associateWith {
            minOf(local.counters.getValue(it), remote.counters.getValue(it))
        }.filterValues { it > 0L })

    /** A later change settles an open conflict when it includes both reviewed versions. */
    fun settles(change: RecordVersion, conflictLocal: RecordVersion, conflictRemote: RecordVersion): Boolean =
        listOf(conflictLocal, conflictRemote).all {
            change.relationTo(it) in setOf(VersionRelation.AFTER, VersionRelation.EQUAL)
        }
}

/** A choice creates a signed operation whose version includes both reviewed histories. */
internal class SyncConflictResolver(private val database: VaultDatabase, private val identity: AndroidDeviceIdentityStore,
    private val photoBlobs: PhotoSyncBlobs? = null, private val context: Context? = null) {
    suspend fun reviews(localKey: ByteArray): List<SyncConflictReview> {
        val key = database.syncKeyring(localKey)
        return try {
            database.syncDao().conflicts().filter { it.resolvedAtUtc.isEmpty() }.map { conflict ->
                val operations = database.syncDao().operationsForEntity(conflict.entityType, conflict.entityId)
                val currentVersion = requireNotNull(database.syncDao().recordState(conflict.entityType, conflict.entityId))
                val current = operations.first { RecordVersion.parse(it.recordVersionJson) == RecordVersion.parse(currentVersion.recordVersionJson) }
                val incoming = operations.first { it.hash == conflict.remoteChangeHash }
                suspend fun side(operation: SyncOperationEntity, other: SyncOperationEntity) = SyncConflictSide(
                    describe(operation, other, key),
                    database.syncDao().membership(operation.vaultId, operation.deviceId)?.displayName
                        ?.takeIf(String::isNotBlank)?.let { "$it · ${operation.deviceId.take(8)}" }
                        ?: operation.deviceId.take(8),
                    operation.occurredAtUtc, operation.kind == "delete")
                SyncConflictReview(conflict.conflictId, conflict.entityType, side(current, incoming),
                    side(incoming, current),
                    currentVersion.recordVersionJson, canCombineNotes(conflict.entityType, current, incoming, key))
            }
        } finally { key.wipe() }
    }

    /**
     * [combineNotes] keeps both edits of a note: this device's record with the text and formatting
     * of both versions combined ([RichNoteMerge]). It is sent like any other resolution.
     */
    suspend fun resolve(id: String, useIncoming: Boolean, localKey: ByteArray,
        expectedVersion: String? = null, combineNotes: Boolean = false) = database.withTransaction {
        require(!(combineNotes && useIncoming)) { "Choose one version or keep both edits" }
        val dao = database.syncDao()
        val conflict = requireNotNull(dao.conflicts().firstOrNull { it.conflictId == id && it.resolvedAtUtc.isEmpty() }) {
            "This conflict has already been resolved"
        }
        require(!useIncoming || conflict.entityType != "passkey") {
            "An incoming passkey cannot replace a different credential with the same ID. Keep this phone's passkey and register a new one on the website."
        }
        val state = requireNotNull(dao.recordState(conflict.entityType, conflict.entityId))
        val local = RecordVersion.parse(state.recordVersionJson)
        if (expectedVersion != null) require(local == RecordVersion.parse(expectedVersion)) {
            "This record changed. Review its updated versions before choosing."
        }
        val remote = RecordVersion.parse(conflict.remoteVersionJson)
        val operations = dao.operationsForEntity(conflict.entityType, conflict.entityId)
        val selected = if (useIncoming) operations.first { it.hash == conflict.remoteChangeHash }
            else operations.first { RecordVersion.parse(it.recordVersionJson) == local }
        val resolution = ConflictVersions.resolution(local, remote, identity.getOrCreate().deviceId)
        val key = database.syncKeyring(localKey)
        val chosen = try {
            val chosen = IncomingEntryChangeApplier(database).decrypt(selected.toSyncChange(), key)
            if (combineNotes) {
                val incoming = operations.first { it.hash == conflict.remoteChangeHash }
                require(canCombineNotes(conflict.entityType, selected, incoming, key)) { "These versions cannot be combined" }
                val other = IncomingEntryChangeApplier(database).decrypt(incoming.toSyncChange(), key).getJSONObject("entry")
                val entry = chosen.getJSONObject("entry")
                val (plain, json) = RichNoteMerge.combineStored(entry.optString("notes"), entry.optString("fourthValue"),
                    other.optString("notes"), other.optString("fourthValue"))
                entry.put("notes", plain).put("fourthValue", json).put("updatedAt", System.currentTimeMillis())
            }
            chosen
        } finally { key.wipe() }
        writeChange(selected, state, resolution, chosen, if (selected.kind == "delete") ChangeKind.DELETE else ChangeKind.UPSERT,
            localKey, announce = false)
    }

    /**
     * M2: merges every open conflict of an entry or folder whose two versions changed different
     * fields ([EntryFieldMerge]), as a signed change of this device that every peer applies, older
     * versions included. Runs after each apply pass. A conflict that can't merge stays for the user,
     * unchanged. Returns how many merged.
     */
    suspend fun autoMerge(localKey: ByteArray): Int {
        var merged = 0
        val open = database.syncDao().conflicts()
            .filter { it.resolvedAtUtc.isEmpty() && it.entityType in EntryFieldMerge.ENTITY_TYPES }
        for (conflict in open) {
            val done = try { mergeOne(conflict.conflictId, localKey) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (done) merged++
        }
        return merged
    }

    private suspend fun mergeOne(id: String, localKey: ByteArray): Boolean = database.withTransaction {
        val dao = database.syncDao()
        val conflict = dao.conflicts().firstOrNull { it.conflictId == id && it.resolvedAtUtc.isEmpty() }
            ?: return@withTransaction false
        val state = dao.recordState(conflict.entityType, conflict.entityId) ?: return@withTransaction false
        val local = RecordVersion.parse(state.recordVersionJson)
        val remote = RecordVersion.parse(conflict.remoteVersionJson)
        if (local.relationTo(remote) != VersionRelation.CONCURRENT) return@withTransaction false
        val operations = dao.operationsForEntity(conflict.entityType, conflict.entityId)
        val current = operations.firstOrNull { RecordVersion.parse(it.recordVersionJson) == local } ?: return@withTransaction false
        val incoming = operations.firstOrNull { it.hash == conflict.remoteChangeHash } ?: return@withTransaction false
        // The change both sides started from, when this device still holds it.
        val meet = ConflictVersions.meet(local, remote)
        val base = operations.filter { it.kind == "upsert" && meet.counters.isNotEmpty() &&
            RecordVersion.parse(it.recordVersionJson) == meet }.minWithOrNull(compareBy({ it.deviceId }, { it.sequence }))
        // Only an active member writes changes (a removed device keeps its items but stops syncing).
        database.requireLocalSyncAuthor(current.vaultId, identity.getOrCreate().deviceId)
        val key = database.syncKeyring(localKey)
        val merged = (try {
            val applier = IncomingEntryChangeApplier(database)
            EntryFieldMerge.merge(conflict.entityType, base?.let { applier.decrypt(it.toSyncChange(), key) },
                applier.decrypt(current.toSyncChange(), key), applier.decrypt(incoming.toSyncChange(), key))
        } finally { key.wipe() }) ?: return@withTransaction false
        val deleted = !merged.has(if (conflict.entityType == "entry") "entry" else "group")
        // No extra count: a device that merges the same pair writes the same version, and neither
        // sees the other's merge as a new conflict.
        writeChange(current, state, ConflictVersions.join(local, remote), merged,
            if (deleted) ChangeKind.DELETE else ChangeKind.UPSERT, localKey, announce = false)
        if (conflict.entityType == "entry") RemoteEntryChanges.record(conflict.entityId, deleted = deleted, settled = true)
        true
    }

    /** Signs [payload] as this device's next change of [selected]'s record, and applies it here. */
    private suspend fun writeChange(selected: SyncOperationEntity, state: com.privatevault.app.data.SyncRecordStateEntity,
        version: RecordVersion, payload: org.json.JSONObject, kind: ChangeKind, localKey: ByteArray, announce: Boolean) {
        val device = identity.getOrCreate()
        val head = database.syncDao().deviceHead(selected.vaultId, device.deviceId)
        val mutation = UUID.randomUUID().toString()
        val key = database.syncKeyring(localKey)
        val encrypted = try {
            val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
            try { SyncPayloadCrypto.encrypt(key.writing(), selected.entityType, selected.vaultId, mutation, selected.entityId, plaintext) }
            finally { plaintext.fill(0) }
        } finally { key.wipe() }
        val change = SyncChangeRecord.createSigned(selected.vaultId, device.deviceId,
            Math.addExact(head?.sequence ?: 0L, 1L), head?.hash ?: GENESIS_HASH, mutation,
            selected.entityType, selected.entityId, kind,
            state.revision, version, Instant.now().toString(), encrypted.ciphertext, encrypted.nonce, identity::sign)
        check(IncomingEntryChangeApplier(database, photoBlobs, context, announceRemoteChanges = announce).apply(SyncOperationEntity(change.mutationId, change.formatVersion,
            change.vaultId, change.deviceId, change.sequence, change.previousHash, change.entityType, change.entityId,
            change.kind.name.lowercase(), change.baseRevision, change.recordVersion.toJson(), change.occurredAtUtc,
            change.payloadCiphertext, change.payloadNonce, change.deviceSignature, change.hash), localKey) == IncomingResult.APPLIED)
    }

    private fun canCombineNotes(entityType: String, current: SyncOperationEntity, incoming: SyncOperationEntity,
        key: SyncKeyring): Boolean {
        if (entityType != "entry" || current.kind == "delete" || incoming.kind == "delete") return false
        val applier = IncomingEntryChangeApplier(database)
        val a = applier.decrypt(current.toSyncChange(), key).getJSONObject("entry")
        val b = applier.decrypt(incoming.toSyncChange(), key).getJSONObject("entry")
        return a.optString("type") == "NOTE" && b.optString("type") == "NOTE" &&
            (a.optString("notes") != b.optString("notes") || a.optString("fourthValue") != b.optString("fourthValue"))
    }

    private fun describe(operation: SyncOperationEntity, other: SyncOperationEntity, key: SyncKeyring): String {
        if (operation.kind == "delete") return "Deleted"
        val payload = IncomingEntryChangeApplier(database).decrypt(operation.toSyncChange(), key)
        return when (operation.entityType) {
            "entry" -> payload.getJSONObject("entry").let {
                val otherEntry = if (other.kind == "delete") null else
                    IncomingEntryChangeApplier(database).decrypt(other.toSyncChange(), key).getJSONObject("entry")
                val type = it.getString("type")
                val labels = mapOf("title" to "Title",
                    "primaryValue" to if (type == "CARD") "Card number" else if (type == "PASSWORD") "Username" else "Primary value",
                    "secondaryValue" to if (type == "PASSWORD") "Password" else if (type == "QUESTION") "Answer" else if (type == "AUTHENTICATOR") "Code setup key" else "Secondary value",
                    "tertiaryValue" to "Third value",
                    "fourthValue" to if (type == "NOTE") "Formatting" else "Fourth value",
                    "notes" to if (type == "NOTE") "Note text" else "Notes", "tags" to "Tags", "favorite" to "Favorite",
                    "sortOrder" to "Order", "color" to "Color", "network" to "Network",
                    "linkedAuthenticatorId" to "Linked authenticator", "linkedApps" to "Linked apps",
                    "autofillSignatures" to "Autofill signatures", "autofillOrigins" to "Autofill origins",
                    "cardKind" to "Card kind", "totpAlgorithm" to "Code algorithm",
                    "totpDigits" to "Code digits", "totpPeriod" to "Code period")
                val differences = labels.filter { (field, _) ->
                    otherEntry != null && it.optString(field) != otherEntry.optString(field)
                }.values.toList()
                val groupsDiffer = other.kind != "delete" &&
                    payload.optJSONArray("groupIds")?.toString() != IncomingEntryChangeApplier(database)
                        .decrypt(other.toSyncChange(), key).optJSONArray("groupIds")?.toString()
                buildList {
                    add(it.getString("title"))
                    if (type == "PASSWORD" && it.getString("primaryValue").isNotBlank())
                        add("Username: ${it.getString("primaryValue")}")
                    if (differences.isNotEmpty()) add("Different: ${differences.joinToString()}")
                    if (groupsDiffer) add("Different: Groups")
                }.joinToString("\n")
            }
            "group" -> payload.getJSONObject("group").let { group ->
                val otherGroup = if (other.kind == "delete") null else
                    IncomingEntryChangeApplier(database).decrypt(other.toSyncChange(), key).getJSONObject("group")
                val changed = listOf("name" to "Name", "notes" to "Notes", "folderType" to "Folder type")
                    .filter { (field, _) -> otherGroup != null && group.optString(field) != otherGroup.optString(field) }
                    .map { it.second }
                buildList {
                    add(group.getString("name"))
                    if (changed.isNotEmpty()) add("Different: ${changed.joinToString()}")
                }.joinToString("\n")
            }
            "photo" -> payload.getJSONObject("photo").let {
                "Photo ${operation.entityId.take(8)}${if (it.getBoolean("isCover")) " · cover" else ""}\nImage version differs"
            }
            "photo_cover" -> "Cover photo ${payload.getString("coverPhotoId").take(8)}"
            "passkey" -> payload.getJSONObject("passkey").let { "${it.getString("rpId")}\n${it.getString("username")}" }
            else -> error("Unsupported conflict type")
        }
    }
}
