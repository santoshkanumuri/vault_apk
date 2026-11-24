package com.privatevault.app.sync

import androidx.annotation.Keep
import com.privatevault.app.data.SyncConflictEntity
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import java.security.MessageDigest
import java.util.Base64

/** One author's newest change named by a checkpoint. */
@Keep
internal data class CheckpointHead(val deviceId: String, val sequence: Long, val hash: String)

/**
 * The managing device's signed statement about an enrollment snapshot v4 (`NUVSYNC4`): which
 * membership history it belongs to, each author's head, and digests of the changes, record states,
 * tombstones and unresolved conflicts it holds. The joiner keeps it as its base, so it knows that
 * changes below it are missing on purpose. Shared vectors: `sync-vectors/checkpoint-v1.json`.
 */
@Keep
internal data class SyncCheckpoint(val format: Int, val vaultId: String, val issuerDeviceId: String,
    val membershipCount: Int, val membershipHead: String, val createdAt: Long, val heads: List<CheckpointHead>,
    val operationsDigest: String, val recordsDigest: String, val tombstonesDigest: String,
    val conflictsDigest: String, val signature: String) {

    /** UTF-8 lines, each ending with a newline; nothing is JSON-canonicalized. */
    fun signingBytes(): ByteArray {
        val listed = heads.filter { it.sequence > 0 }.sortedWith(SyncCheckpoints.utf8Order { it.deviceId })
        val lines = listOf("nuvori-checkpoint-v1", vaultId, issuerDeviceId, membershipCount.toString(),
            membershipHead, createdAt.toString(), "heads ${listed.size}") +
            listed.map { "${it.deviceId}:${it.sequence}:${it.hash}" } +
            listOf("ops $operationsDigest", "records $recordsDigest", "tombstones $tombstonesDigest",
                "conflicts $conflictsDigest")
        return lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
    }
}

internal object SyncCheckpoints {
    /** Announced in the pairing hello by builds that read snapshot v4. */
    const val FEATURE = "checkpoint-enroll-v1"
    const val FORMAT = 1
    private val HASH = Regex("[a-f0-9]{64}")

    data class Digests(val operations: String, val records: String, val tombstones: String, val conflicts: String)

    fun <T> utf8Order(key: (T) -> String): Comparator<T> = Comparator { left, right ->
        val a = key(left).toByteArray(Charsets.UTF_8)
        val b = key(right).toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(a.size, b.size)) {
            val compared = (a[index].toInt() and 0xff) - (b[index].toInt() and 0xff)
            if (compared != 0) return@Comparator compared
        }
        a.size - b.size
    }

    private fun digest(lines: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(lines.sortedWith(utf8Order { it }).joinToString("\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** `dev=n,dev=n`, sorted by device ID. */
    fun canonicalVersion(json: String): String = RecordVersion.parse(json).counters.entries
        .sortedWith(utf8Order { it.key }).joinToString(",") { "${it.key}=${it.value}" }

    fun digests(operations: List<String>, records: List<SyncRecordStateEntity>,
        tombstones: List<SyncTombstoneEntity>, conflicts: List<SyncConflictEntity>) = Digests(
        digest(operations),
        digest(records.map { "${it.entityType}\t${it.entityId}\t${it.revision}\t${canonicalVersion(it.recordVersionJson)}" }),
        digest(tombstones.map { "${it.entityType}\t${it.entityId}\t${it.changeHash}" }),
        digest(conflicts.filter { it.resolvedAtUtc.isEmpty() }
            .map { "${it.conflictId}\t${it.localChangeHash}\t${it.remoteChangeHash}" }))

    fun sign(vaultId: String, issuerDeviceId: String, events: List<SyncMembershipEventEntity>,
        heads: List<CheckpointHead>, digests: Digests, createdAt: Long, signer: (ByteArray) -> ByteArray): SyncCheckpoint {
        val unsigned = SyncCheckpoint(FORMAT, vaultId, issuerDeviceId, events.size, events.last().hash, createdAt,
            heads.filter { it.sequence > 0 }.sortedWith(utf8Order { it.deviceId }), digests.operations,
            digests.records, digests.tombstones, digests.conflicts, "")
        return unsigned.copy(signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer(unsigned.signingBytes())))
    }

    /**
     * Checks a v4 snapshot's checkpoint against its own content ([state]) after the membership in it
     * verified as [verified], in the design's order: the membership it names; the managing device's
     * signature (it must be [peerDeviceId]); each head against its author's own signature; every
     * change's hash, author and signature (no chain continuity: the changes between witnesses are
     * gone on purpose); the digests; and that each tombstone and unresolved conflict refers to an
     * included change. Throws [IllegalArgumentException] for anything else.
     */
    fun validate(checkpoint: SyncCheckpoint, verified: VerifiedMembership, peerDeviceId: String,
        heads: List<CheckpointHead>, operations: List<SyncOperationEntity>, records: List<SyncRecordStateEntity>,
        tombstones: List<SyncTombstoneEntity>, conflicts: List<SyncConflictEntity>) {
        val vaultId = verified.events.first().vaultId
        require(checkpoint.format == FORMAT && checkpoint.vaultId == vaultId) { "Unsupported sync checkpoint" }
        // 1. The membership it names.
        require(checkpoint.membershipCount == verified.events.size &&
            checkpoint.membershipHead == verified.events.last().hash) { "Checkpoint membership mismatch" }
        // 2. Signed by the managing device, which is the paired peer.
        require(checkpoint.issuerDeviceId == verified.managerDeviceId && checkpoint.issuerDeviceId == peerDeviceId) {
            "Checkpoint is not from the managing device"
        }
        val members = verified.members.associateBy { it.deviceId }
        val issuer = requireNotNull(members[checkpoint.issuerDeviceId])
        require(verifySignature(issuer, checkpoint.signingBytes(), checkpoint.signature)) { "Invalid checkpoint signature" }
        val cutoffs = RemovalCutoff.cutoffs(verified.events)
        // 3. Each head is proven by its author's own signature.
        val claimed = checkpoint.heads
        require(claimed.all { it.sequence > 0 && it.hash.matches(HASH) } &&
            claimed.map { it.deviceId }.distinct().size == claimed.size) { "Invalid checkpoint heads" }
        val byHash = HashMap<String, SyncOperationEntity>()
        val mutations = HashSet<String>()
        operations.forEach { operation ->
            require(byHash.put(operation.hash, operation) == null && mutations.add(operation.mutationId)) {
                "Duplicate change in checkpoint"
            }
        }
        val headOf = claimed.associateBy { it.deviceId }
        claimed.forEach { head ->
            val witnesses = operations.filter { it.deviceId == head.deviceId && it.sequence == head.sequence }
            require(witnesses.size == 1 && witnesses[0].hash == head.hash) { "Checkpoint head has no witness" }
        }
        // 4. Every change: hash, author, signature, at or below its author's head.
        operations.forEach { operation ->
            require(operation.vaultId == vaultId)
            val member = requireNotNull(members[operation.deviceId]) { "Unknown change author" }
            // A removed device's change counts up to its cutoff. A removal signed before cutoffs
            // existed has none; its changes are then trusted as snapshot v3 trusts them.
            require(member.status == MemberStatus.ACTIVE.name || operation.deviceId !in cutoffs ||
                RemovalCutoff.allows(cutoffs[operation.deviceId], operation.sequence, operation.hash)) {
                "Change of a removed device"
            }
            val change = operation.toSyncChange()
            change.validate()
            require(verifySignature(member, change.signingBytes(), change.deviceSignature)) { "Invalid change signature" }
            require(operation.sequence <= (headOf[operation.deviceId]?.sequence ?: 0L)) { "Change beyond its author's head" }
        }
        // 5. Digests.
        require(conflicts.all { it.resolvedAtUtc.isEmpty() }) { "Resolved conflict in checkpoint" }
        val expected = digests(operations.map { it.hash }, records, tombstones, conflicts)
        require(expected == Digests(checkpoint.operationsDigest, checkpoint.recordsDigest,
            checkpoint.tombstonesDigest, checkpoint.conflictsDigest)) { "Checkpoint digest mismatch" }
        // 6. The snapshot's heads, tombstones and conflicts agree with the included changes.
        require(heads.toSet() == claimed.toSet() && heads.size == claimed.size) { "Checkpoint heads mismatch" }
        tombstones.forEach { tombstone ->
            val change = requireNotNull(byHash[tombstone.changeHash]) { "Tombstone without its change" }
            require(change.kind == "delete" && change.entityType == tombstone.entityType &&
                change.entityId == tombstone.entityId) { "Tombstone without its change" }
        }
        conflicts.forEach { conflict ->
            require(conflict.remoteChangeHash in byHash &&
                (conflict.localChangeHash == GENESIS_HASH || conflict.localChangeHash in byHash)) {
                "Conflict without its changes"
            }
        }
    }

    /**
     * Records of items present in the vault copy ([present], as (type, id)) whose state no included
     * change explains with an EQUAL version, and deleted ones a present item contradicts. Empty
     * when every item has its witness. Mirrors Windows `snapshot_import::validate_snapshot`.
     */
    fun unwitnessed(records: List<SyncRecordStateEntity>, operations: List<SyncOperationEntity>,
        present: Set<Pair<String, String>>): List<SyncRecordStateEntity> {
        val byEntity = operations.groupBy { it.entityType to it.entityId }
        return records.filter { record ->
            val key = record.entityType to record.entityId
            if (record.entityType !in setOf("entry", "group", "passkey")) return@filter false
            val version = RecordVersion.parse(record.recordVersionJson)
            val matching = byEntity[key].orEmpty().firstOrNull {
                RecordVersion.parse(it.recordVersionJson).relationTo(version) == VersionRelation.EQUAL
            }
            when {
                matching == null -> key in present
                matching.kind == "upsert" -> key !in present
                else -> key in present
            }
        }
    }

    private fun verifySignature(member: SyncMembershipEntity, bytes: ByteArray, signature: String): Boolean =
        runCatching {
            DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey), bytes,
                Base64.getUrlDecoder().decode(signature))
        }.getOrDefault(false)
}

/**
 * The changes a device must keep: one rule for enrollment ([SyncCheckpoints]) and for history
 * compaction. See `scripts/sync-vectors/checkpoint.py` for the rule and the shared vectors.
 */
internal object KeepSet {
    /** A held change without its payload. */
    data class Change(val deviceId: String, val sequence: Long, val hash: String, val entityType: String,
        val entityId: String, val kind: String, val recordVersionJson: String, val occurredAtUtc: String)

    val ORDINARY = setOf("entry", "group", "passkey", "photo", "photo_cover")
    val LAST_WRITER_WINS = setOf(AppearanceSyncRules.ENTITY_TYPE, SiteIconSyncRules.ENTITY_TYPE)
    /** Merged, never a conflict: every change that no other change of the entity includes, upserts and deletes. */
    val MERGED = setOf(SignInMethodSyncRules.ENTITY_TYPE)
    const val DEVICE_NAME = "device_name"

    fun of(operation: SyncOperationEntity) = Change(operation.deviceId, operation.sequence, operation.hash,
        operation.entityType, operation.entityId, operation.kind, operation.recordVersionJson, operation.occurredAtUtc)

    /** The hashes of the changes to keep (rules 1 to 5). */
    fun compute(changes: List<Change>, records: List<SyncRecordStateEntity>, tombstones: List<SyncTombstoneEntity>,
        conflicts: List<SyncConflictEntity>): Set<String> {
        val kept = HashSet<String>()
        // 1. Head witnesses.
        changes.groupBy { it.deviceId }.values.forEach { own -> kept += own.maxBy { it.sequence }.hash }
        val states = records.associateBy { it.entityType to it.entityId }
        val deleted = tombstones.mapTo(hashSetOf()) { it.entityType to it.entityId }
        // 2 and 5. Entity witnesses, or everything when nothing explains the state.
        changes.groupBy { it.entityType to it.entityId }.forEach { (key, group) ->
            val type = key.first
            when {
                type in LAST_WRITER_WINS -> winner(group)?.let { kept += it.hash }
                type in MERGED -> {
                    val versions = group.map { it to RecordVersion.parse(it.recordVersionJson) }
                    versions.filter { (change, version) ->
                        versions.none { (other, otherVersion) -> other.hash != change.hash &&
                            version.relationTo(otherVersion) == VersionRelation.BEFORE }
                    }.mapTo(kept) { it.first.hash }
                }
                type == DEVICE_NAME -> group.groupBy { it.deviceId }.values.forEach { kept += it.maxBy { c -> c.sequence }.hash }
                type in ORDINARY -> {
                    val state = states[key]
                    if (state == null) {
                        if (key !in deleted) group.mapTo(kept) { it.hash }
                    } else {
                        val target = RecordVersion.parse(state.recordVersionJson)
                        val equal = group.filter { RecordVersion.parse(it.recordVersionJson).relationTo(target) == VersionRelation.EQUAL }
                        if (equal.isEmpty()) group.mapTo(kept) { it.hash }
                        else kept += equal.sortedWith(SyncCheckpoints.utf8Order<Change> { it.deviceId }
                            .thenBy { it.sequence }).last().hash
                    }
                }
                else -> group.mapTo(kept) { it.hash }
            }
        }
        // 3. Tombstone witnesses. 4. Unresolved conflict witnesses.
        tombstones.mapTo(kept) { it.changeHash }
        conflicts.filter { it.resolvedAtUtc.isEmpty() }.forEach {
            kept += it.localChangeHash; kept += it.remoteChangeHash
        }
        kept -= GENESIS_HASH
        return kept
    }

    /** The last-writer-wins winner, as [AppearanceSyncRules.current] picks it. */
    private fun winner(group: List<Change>): Change? {
        val candidates = group.filter { it.kind == "upsert" }.map { it to RecordVersion.parse(it.recordVersionJson) }
        return candidates.filter { (_, version) ->
            candidates.none { (_, other) -> version.relationTo(other) == VersionRelation.BEFORE }
        }.map { it.first }.fold(null as Change?) { best, next ->
            if (best == null || AppearanceSyncRules.incomingWins(best.occurredAtUtc, best.deviceId,
                    next.occurredAtUtc, next.deviceId)) next else best
        }
    }
}
