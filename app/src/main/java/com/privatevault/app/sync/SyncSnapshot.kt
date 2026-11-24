package com.privatevault.app.sync

import android.content.Context
import androidx.annotation.Keep
import androidx.room.withTransaction
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.config.TinkConfig
import com.google.gson.Gson
import com.privatevault.app.backup.PreparedRestore
import com.privatevault.app.backup.VaultBackupManager
import com.privatevault.app.data.*
import com.privatevault.app.security.EncryptedPhotoStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Keep
internal data class SnapshotSyncState(
    val version: Int,
    val vaultId: String,
    val contentKey: String,
    val transportSecret: String,
    val members: List<SyncMembershipEntity>,
    val membershipEvents: List<SyncMembershipEventEntity>,
    val heads: List<SyncDeviceHeadEntity>,
    val records: List<SyncRecordStateEntity>,
    val operations: List<SyncOperationEntity>,
    val tombstones: List<SyncTombstoneEntity>,
    val conflicts: List<SyncConflictEntity>,
    /**
     * Version 4 only: the keys of later epochs (none yet), the lineage of the group the host came from
     * (only to a joiner that announced [MergeJoinRules.FEATURE], see [SnapshotLineage]), and the signed checkpoint.
     */
    val contentKeys: Map<String, String>? = null,
    val transportSecrets: Map<String, String>? = null,
    val lineage: com.google.gson.JsonElement? = null,
    val checkpoint: SyncCheckpoint? = null,
)

/**
 * The host can't send its history in a form the joining device reads: the device announced no
 * [SyncSnapshots.CHECKPOINT_FEATURE], and the full history no longer fits snapshot v3 (too large,
 * or older changes are no longer held). The text reaches both devices.
 */
internal class JoinerUpdateNeeded(device: String) :
    IllegalStateException("Update Nuvori on $device to add it. Your sync history is too large for this version.")

internal object SyncSnapshots {
    const val CHECKPOINT_FEATURE = SyncCheckpoints.FEATURE

    /** True when each author's held changes run 1..head without a gap, as snapshot v3 requires. */
    fun fullHistory(heads: List<SyncDeviceHeadEntity>, summaries: List<SyncOperationSummary>): Boolean {
        val byAuthor = summaries.groupBy { it.deviceId }
        return heads.all { head ->
            val own = byAuthor[head.deviceId].orEmpty()
            own.size.toLong() == head.sequence && own.withIndex().all { (index, change) -> change.sequence == index + 1L }
        } && byAuthor.keys.all { author -> heads.any { it.deviceId == author } }
    }
}

/** Used only inside a confirmed pairing session, never as an ordinary sync batch. */
class SyncSnapshot(private val context: Context, private val database: VaultDatabase,
    private val photos: EncryptedPhotoStore) {
    /**
     * Sends snapshot v4 (a signed checkpoint with only the changes the joiner must keep) to a device
     * that announced [SyncSnapshots.CHECKPOINT_FEATURE] in its pairing hello ([joinerFeatures]),
     * else v3 with the full history when it still fits. The hello is not authenticated: stripping
     * the feature can only force v3 or a refusal.
     */
    suspend fun export(output: OutputStream, localKey: ByteArray, pairingKey: ByteArray,
        joiningMember: SyncMembershipEntity, windowsPeer: Boolean = false,
        joinerFeatures: Set<String> = emptySet()): SyncMembershipEventEntity? {
        require(pairingKey.size == 32)
        val windowsIds = LanSyncService.store(context).snapshot()?.clientOnlyPeers.orEmpty()
        val staged = File.createTempFile("sync-enrollment-", ".bin", context.cacheDir)
        // A joiner that may bring items: every item of the host gets a signed version first ([MergeJoin.adoptUntracked]).
        val merge = MergeJoinRules.FEATURE in joinerFeatures && SyncSnapshots.CHECKPOINT_FEATURE in joinerFeatures
        if (merge) MergeJoin.adoptUntracked(database, AndroidDeviceIdentityStore(context), localKey)
        val stagingCipher = if (windowsPeer) {
            TinkConfig.register()
            KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM_HKDF_1MB"))
                .getPrimitive(StreamingAead::class.java)
        } else null
        try {
            val admission = database.withTransaction {
                FileOutputStream(staged).use { file ->
                    val stored = stagingCipher?.newEncryptingStream(file, STAGING_AAD) ?: file
                    stored.use { target ->
                        val vaultId = requireNotNull(database.dao().settings()).vaultId
                        val group = database.syncDao().vaultState()
                        require(group?.vaultId == vaultId && group.transportSecret.isNotBlank()) {
                            "Prepare the sync group before enrolling another phone"
                        }
                        require(joiningMember.vaultId == vaultId && joiningMember.status == MemberStatus.ACTIVE.name)
                        val members = database.syncDao().memberships(vaultId)
                        val existing = members.firstOrNull { it.deviceId == joiningMember.deviceId }
                        require(existing == null || existing.status == MemberStatus.ACTIVE.name &&
                            existing.identityPublicKey == joiningMember.identityPublicKey) {
                            "Enrolled device identity changed or was removed"
                        }
                        requireDeviceCapacity(members.filter { it.status == MemberStatus.ACTIVE.name }
                            .mapTo(hashSetOf()) { it.deviceId },
                            windowsIds,
                            joiningMember.deviceId, windowsPeer)
                        val identityStore = AndroidDeviceIdentityStore(context)
                        val identity = identityStore.getOrCreate()
                        val history = database.syncDao().membershipEvents(vaultId)
                        val verified = SyncMembershipManager.verify(history.map { it.toEvent() })
                        require(verified.managerDeviceId == identity.deviceId &&
                            joiningMember.addedByDeviceId == identity.deviceId &&
                            joiningMember.keyEpoch == verified.keyEpoch) { "Only the managing phone can enroll a device" }
                        val event = if (existing == null) {
                            require(joiningMember.membershipSequence == history.size + 1L)
                            SyncMembershipEventEntity.from(SyncMembershipEvent.sign(vaultId,
                                history.size + 1L, verified.head, MembershipAction.ADD, identity.deviceId,
                                joiningMember.deviceId, joiningMember.identityPublicKey, verified.keyEpoch,
                                identityStore::sign))
                        } else null
                        val events = if (event == null) history else history + event
                        SyncMembershipManager.verify(events.map { it.toEvent() })
                        val dao = database.syncDao()
                        val checkpointed = SyncSnapshots.CHECKPOINT_FEATURE in joinerFeatures
                        val joiner = if (windowsPeer) "the new PC" else "the new device"
                        val heads = dao.deviceHeads(vaultId)
                        val records = dao.recordStates()
                        val tombstones = dao.tombstones()
                        val conflicts = dao.conflicts()
                        val summaries = dao.operationSummaries(vaultId)
                        val operations = if (checkpointed) {
                            val keep = KeepSet.compute(summaries.map { KeepSet.Change(it.deviceId, it.sequence, it.hash,
                                it.entityType, it.entityId, it.kind, it.recordVersionJson, it.occurredAtUtc) },
                                records, tombstones, conflicts)
                            keep.toList().chunked(500).flatMap { dao.operationsByHashes(it) }
                                .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
                        } else {
                            // An older joiner reads only the full history from change 1.
                            if (!SyncSnapshots.fullHistory(heads, summaries)) throw JoinerUpdateNeeded(joiner)
                            dao.operations()
                        }
                        val memberList = if (existing == null) members + joiningMember else members
                        // After a rotation the joiner needs the keys of later epochs; only a build that reads them gets them.
                        val epochKeys = dao.epochKeys().filter { it.vaultId == vaultId }
                        if (epochKeys.isNotEmpty() && (!checkpointed || EpochKeys.FEATURE !in joinerFeatures))
                            throw IllegalStateException("Update Nuvori on $joiner first.")
                        val contentKey = database.syncContentKey(localKey)
                        val state = if (checkpointed) {
                            val open = conflicts.filter { it.resolvedAtUtc.isEmpty() }
                            val checkpoint = SyncCheckpoints.sign(vaultId, identity.deviceId, events,
                                heads.map { CheckpointHead(it.deviceId, it.sequence, it.hash) },
                                SyncCheckpoints.digests(operations.map { it.hash }, records, tombstones, open),
                                System.currentTimeMillis(), identityStore::sign)
                            val lineage = if (merge) SnapshotLineage.of(dao.lineage())?.let { Gson().toJsonTree(it) } else null
                            SnapshotSyncState(4, vaultId, encode(contentKey), group.transportSecret, memberList, events,
                                heads, records, operations, tombstones, open,
                                epochKeys.associate { it.epoch.toString() to it.contentKey },
                                epochKeys.associate { it.epoch.toString() to it.transportSecret }, lineage, checkpoint)
                        } else SnapshotSyncState(3, vaultId, encode(contentKey), group.transportSecret, memberList, events,
                            heads, records, operations, tombstones, conflicts)
                        contentKey.fill(0)
                        val magic = if (checkpointed) MAGIC_V4 else MAGIC
                        val plain = Gson().toJson(state).toByteArray(Charsets.UTF_8)
                        if (plain.size > MAX_METADATA) {
                            plain.fill(0)
                            if (!checkpointed) throw JoinerUpdateNeeded(joiner)
                            error("Sync history is too large for enrollment")
                        }
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                            init(Cipher.ENCRYPT_MODE, SecretKeySpec(pairingKey, "AES"))
                            updateAAD(magic)
                        }
                        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
                        val data = DataOutputStream(target)
                        data.write(magic)
                        data.write(cipher.iv)
                        data.writeInt(encrypted.size)
                        data.write(encrypted)
                        val backup = VaultBackupManager(context, database.dao(), photos)
                        if (windowsPeer) backup.exportForWindowsPairing(data, localKey)
                        else {
                            val password = encode(pairingKey).toCharArray()
                            try { backup.export(data, password, localKey) }
                            finally { password.fill('\u0000') }
                        }
                        event
                    }
                }
            }
            staged.inputStream().use { file ->
                (stagingCipher?.newDecryptingStream(file, STAGING_AAD) ?: file).use { it.copyTo(output) }
            }
            return admission
        } finally {
            staged.delete()
        }
    }

    suspend fun prepare(input: InputStream, localKey: ByteArray, pairingKey: ByteArray,
        expectedVaultId: String, localDeviceId: String, approvedPeer: DeviceIdentity,
        peerFeatures: Set<String> = emptySet()): PreparedSyncSnapshot {
        require(pairingKey.size == 32)
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        if (magic.contentEquals(SNAPSHOT_FAILURE)) throw readSnapshotFailure(data)
        val version = when {
            magic.contentEquals(MAGIC) -> 3
            magic.contentEquals(MAGIC_V4) -> 4
            else -> throw IllegalArgumentException("Unsupported sync snapshot")
        }
        val nonce = ByteArray(12).also(data::readFully)
        val size = data.readInt()
        require(size in 16..MAX_METADATA + 16) { "Sync snapshot metadata is too large" }
        val ciphertext = ByteArray(size).also(data::readFully)
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(pairingKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(magic)
            doFinal(ciphertext)
        }
        val state = try { Gson().fromJson(plaintext.toString(Charsets.UTF_8), SnapshotSyncState::class.java) }
        finally { plaintext.fill(0) }
        require(state.version == version) { "Unsupported sync snapshot" }
        validateSnapshotState(state, expectedVaultId, localDeviceId, approvedPeer,
            allowLineage = MergeJoinRules.FEATURE in peerFeatures)
        val password = encode(pairingKey).toCharArray()
        val prepared = try { VaultBackupManager(context, database.dao(), photos).prepareRestore(data, password, localKey) }
        finally { password.fill('\u0000') }
        return try {
            require(prepared.data.vaultId == expectedVaultId) { "Snapshot vault mismatch" }
            if (version == 4) {
                // Each present item must have its witness: the vault copy and the history agree.
                val present = prepared.data.entries.map { "entry" to it.id } + prepared.data.groups.map { "group" to it.id } +
                    prepared.data.passkeys.map { "passkey" to it.id }
                require(SyncCheckpoints.unwitnessed(state.records, state.operations, present.toSet()).isEmpty()) {
                    "Snapshot items do not match the sync history"
                }
            }
            PreparedSyncSnapshot(state, prepared)
        } catch (failure: Exception) { prepared.close(); throw failure }
    }

    /**
     * Stores the snapshot in one transaction. With [merge] (the host announced [MergeJoinRules.FEATURE])
     * this device keeps its items: they are read first and merged by ID ([MergeJoin.apply]); the caller
     * makes a local backup before. Returns what the merge did, and the photos to publish afterwards.
     */
    internal suspend fun commit(prepared: PreparedSyncSnapshot, merge: Boolean = false,
        identityStore: AndroidDeviceIdentityStore = AndroidDeviceIdentityStore(context)): Pair<MergeJoin.Summary, List<VaultPhoto>>? {
        val restore = prepared.restore
        check(!restore.committed && !restore.committing && !restore.closed) { "Enrollment is no longer available" }
        restore.committing = true
        var merged: Pair<MergeJoin.Summary, List<VaultPhoto>>? = null
        try {
            database.withTransaction {
                val local = if (merge) MergeJoin.readLocal(database) else null
                if (local == null) require(database.dao().allEntries().isEmpty() && database.dao().allPasskeys().isEmpty() &&
                    database.dao().allGroupsWithEntries().isEmpty()) { "Join from an empty vault" }
                val localSettings = requireNotNull(database.dao().settings())
                val snapshot = restore.data
                val state = prepared.state
                database.dao().replaceAll(snapshot.entries, snapshot.groups, snapshot.links, snapshot.photos,
                    localSettings.copy(vaultId = state.vaultId, watchSyncEnabled = false), snapshot.passkeys,
                    passkeyEvent = com.privatevault.app.data.PasskeyEventKind.SYNCED_IN,
                    passkeyEventDetail = com.privatevault.app.passkeys.PasskeyLedger.JOINED_SYNC)
                val sync = database.syncDao()
                sync.saveVaultState(SyncVaultStateEntity(vaultId = state.vaultId, contentKey = state.contentKey,
                    transportSecret = state.transportSecret))
                state.membershipEvents.forEach { sync.insertMembershipEvent(it) }
                state.members.forEach { sync.upsertMembership(it) }
                state.operations.forEach { sync.insertOperation(it) }
                state.heads.forEach { sync.upsertDeviceHead(it) }
                state.records.forEach { sync.upsertRecordState(it) }
                state.tombstones.forEach { sync.upsertTombstone(it) }
                state.conflicts.forEach { sync.insertConflict(it) }
                // The base: changes below these heads are missing on purpose, not lost.
                state.contentKeys.orEmpty().forEach { (epoch, key) ->
                    sync.saveEpochKey(SyncEpochKeyEntity(state.vaultId, epoch.toLong(), key,
                        requireNotNull(state.transportSecrets?.get(epoch))))
                }
                state.checkpoint?.let { checkpoint ->
                    sync.saveCheckpoint(SyncCheckpointEntity(state.vaultId, Gson().toJson(checkpoint), "{}",
                        System.currentTimeMillis()))
                }
                // A freeze of the group this device leaves now ends with it.
                sync.clearLostManager()
                if (local != null) {
                    // What this device keeps of the group it leaves, for a later merge (only a shared group).
                    if (local.shared && local.vaultId != state.vaultId) {
                        sync.clearLineage()
                        sync.insertLineage(MergeJoin.lineageRows(local))
                    }
                    merged = MergeJoin.apply(database, identityStore, local, state, snapshot)
                }
            }
            restore.committed = true
            // Appearance changes arrive in the history above without being applied; take their value.
            // Best effort: the vault is already joined, and the next appearance change corrects it.
            runCatching { AppearanceSync.adoptFromSnapshot(context, prepared.state) }
            // Site icons too, the same way.
            runCatching { SiteIconSync.adoptFromSnapshot(database, prepared.state) }
            // And sign-in methods: the merge of each entity's latest changes, unless a delete is the latest.
            runCatching { SignInMethodSync.adoptFromSnapshot(database, prepared.state) }
        } finally {
            restore.committing = false
            if (!restore.committed) restore.close()
        }
        return merged
    }

    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private companion object {
        val MAGIC = "NUVSYNC3".toByteArray(Charsets.US_ASCII)
        val MAGIC_V4 = "NUVSYNC4".toByteArray(Charsets.US_ASCII)
        val STAGING_AAD = "nuvori-windows-staging-v1".toByteArray(Charsets.US_ASCII)
        const val MAX_METADATA = 16 * 1024 * 1024
    }
}

/**
 * Checks snapshot metadata before anything is stored: keys, the signed membership, this device and
 * the paired peer as active members, then the history. Version 3 holds every change as an unbroken
 * chain from change 1; version 4 holds the keep set under a checkpoint ([SyncCheckpoints.validate]).
 */
internal fun validateSnapshotState(state: SnapshotSyncState, vaultId: String, deviceId: String, peer: DeviceIdentity,
    allowLineage: Boolean = false) {
    require((state.version == 3 || state.version == 4) && state.vaultId == vaultId)
    @Suppress("SENSELESS_COMPARISON")
    require(state.members != null && state.membershipEvents != null && state.heads != null && state.records != null &&
        state.operations != null && state.tombstones != null && state.conflicts != null) { "Invalid sync snapshot" }
    require(Base64.getUrlDecoder().decode(state.contentKey).size == 32)
    require(Base64.getUrlDecoder().decode(state.transportSecret).size == 32)
    val verified = SyncMembershipManager.verify(state.membershipEvents.map { it.toEvent() })
    require(verified.events.first().vaultId == vaultId)
    require(state.members.size >= 2 && state.members.map { it.deviceId }.distinct().size == state.members.size)
    require(state.members.count { it.status == MemberStatus.ACTIVE.name } in 2..MAX_ACTIVE_SYNC_DEVICES) {
        "Too many active Android devices"
    }
    state.members.forEach { require(it.vaultId == vaultId && it.keyEpoch >= 1L); it.toMembership().validate() }
    require(state.members.size == verified.members.size && state.members.all { stored ->
        verified.members.any { it.deviceId == stored.deviceId &&
            it.identityPublicKey == stored.identityPublicKey && it.status == stored.status &&
            it.membershipSequence == stored.membershipSequence && it.keyEpoch == stored.keyEpoch }
    }) { "Snapshot membership is not signed" }
    require(state.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
    require(state.members.any { it.deviceId == peer.deviceId && it.identityPublicKey == peer.publicKeyBase64Url &&
        it.status == MemberStatus.ACTIVE.name }) { "Pairing identity changed" }
    state.records.forEach { RecordVersion.parse(it.recordVersionJson).validate() }
    if (state.version == 4) {
        val checkpoint = requireNotNull(state.checkpoint) { "Snapshot checkpoint is missing" }
        // Keys of rotated epochs (epoch-keys-v1, which this build reads): 2..current, 32 bytes each, in pairs.
        val contentKeys = state.contentKeys.orEmpty()
        val transportSecrets = state.transportSecrets.orEmpty()
        require(contentKeys.keys == transportSecrets.keys && contentKeys.size <= 1024 &&
            (contentKeys + transportSecrets).all { (epoch, value) -> epoch.toLongOrNull()?.let { it in 2..verified.keyEpoch } == true &&
                Base64.getUrlDecoder().decode(value).size == 32 }) { "Unsupported sync snapshot" }
        // A lineage only from a host that announced the merge join, and only a valid one.
        if (allowLineage) SnapshotLineage.parse(state.lineage)
        else require(state.lineage == null || state.lineage.isJsonNull) { "Unsupported sync snapshot" }
        state.heads.forEach { require(it.vaultId == vaultId) }
        SyncCheckpoints.validate(checkpoint, verified, peer.deviceId,
            state.heads.map { CheckpointHead(it.deviceId, it.sequence, it.hash) }, state.operations,
            state.records, state.tombstones, state.conflicts)
        return
    }
    require(state.checkpoint == null) { "Unsupported sync snapshot" }
    val members = state.members.associateBy { it.deviceId }
    val heads = mutableMapOf<String, SyncDeviceHeadEntity>()
    state.operations.sortedWith(compareBy({ it.deviceId }, { it.sequence })).forEach { operation ->
        require(operation.vaultId == vaultId)
        val member = requireNotNull(members[operation.deviceId])
        val change = operation.toSyncChange()
        change.validate()
        require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey),
            change.signingBytes(), Base64.getUrlDecoder().decode(change.deviceSignature)))
        val previous = heads[operation.deviceId]
        require(operation.sequence == (previous?.sequence ?: 0L) + 1 &&
            operation.previousHash == (previous?.hash ?: GENESIS_HASH)) { "Snapshot history is incomplete" }
        heads[operation.deviceId] = SyncDeviceHeadEntity(vaultId, operation.deviceId, operation.sequence, operation.hash)
    }
    require(state.heads.size == heads.size && state.heads.toSet() == heads.values.toSet())
}

class PreparedSyncSnapshot internal constructor(internal val state: SnapshotSyncState,
    internal val restore: PreparedRestore) : AutoCloseable {
    override fun close() = restore.close()
}

internal fun SyncOperationEntity.toSyncChange() = SyncChangeRecord(formatVersion, vaultId, deviceId, sequence,
    previousHash, mutationId, entityType, entityId, ChangeKind.valueOf(kind.uppercase()), baseRevision,
    RecordVersion.parse(recordVersionJson), occurredAtUtc, payloadCiphertext, payloadNonce, deviceSignature, hash)
