package com.privatevault.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.VaultDatabase
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

data class SyncConflictReview(val id: String, val type: String, val current: SyncConflictSide,
    val incoming: SyncConflictSide, val currentVersion: String)

/** A choice creates a signed operation whose version includes both reviewed histories. */
internal class SyncConflictResolver(private val database: VaultDatabase, private val identity: AndroidDeviceIdentityStore,
    private val photoBlobs: PhotoSyncBlobs? = null, private val context: Context? = null) {
    suspend fun reviews(localKey: ByteArray): List<SyncConflictReview> {
        val key = database.syncContentKey(localKey)
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
                    currentVersion.recordVersionJson)
            }
        } finally { key.fill(0) }
    }

    suspend fun resolve(id: String, useIncoming: Boolean, localKey: ByteArray,
        expectedVersion: String? = null) = database.withTransaction {
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
        val device = identity.getOrCreate()
        val counters = (local.counters.keys + remote.counters.keys).associateWith {
            maxOf(local.counters[it] ?: 0, remote.counters[it] ?: 0)
        }.toMutableMap()
        counters[device.deviceId] = Math.addExact(counters[device.deviceId] ?: 0L, 1L)
        val head = dao.deviceHead(selected.vaultId, device.deviceId)
        val mutation = UUID.randomUUID().toString()
        val key = database.syncContentKey(localKey)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val encrypted = try {
            val plaintext = IncomingEntryChangeApplier(database).decrypt(selected.toSyncChange(), key)
                .toString().toByteArray(Charsets.UTF_8)
            val payloadKey = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal("nuvori-sync-payload-key-v1".toByteArray(Charsets.UTF_8))
            }
            try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD("nuvori-sync-${selected.entityType}-v1:${selected.vaultId}:$mutation:${selected.entityId}".toByteArray(Charsets.UTF_8))
                    doFinal(plaintext)
                }
            } finally { plaintext.fill(0); payloadKey.fill(0) }
        } finally { key.fill(0) }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val change = SyncChangeRecord.createSigned(selected.vaultId, device.deviceId,
            Math.addExact(head?.sequence ?: 0L, 1L), head?.hash ?: GENESIS_HASH, mutation,
            selected.entityType, selected.entityId, if (selected.kind == "delete") ChangeKind.DELETE else ChangeKind.UPSERT,
            state.revision, RecordVersion(counters), Instant.now().toString(), encoder.encodeToString(encrypted),
            encoder.encodeToString(nonce), identity::sign)
        check(IncomingEntryChangeApplier(database, photoBlobs, context).apply(SyncOperationEntity(change.mutationId, change.formatVersion,
            change.vaultId, change.deviceId, change.sequence, change.previousHash, change.entityType, change.entityId,
            change.kind.name.lowercase(), change.baseRevision, change.recordVersion.toJson(), change.occurredAtUtc,
            change.payloadCiphertext, change.payloadNonce, change.deviceSignature, change.hash), localKey) == IncomingResult.APPLIED)
    }

    private fun describe(operation: SyncOperationEntity, other: SyncOperationEntity, key: ByteArray): String {
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
                    "tertiaryValue" to "Third value", "fourthValue" to "Fourth value",
                    "notes" to "Notes", "tags" to "Tags", "favorite" to "Favorite",
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
