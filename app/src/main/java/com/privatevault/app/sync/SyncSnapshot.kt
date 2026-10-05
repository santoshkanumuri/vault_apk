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
)

/** Used only inside a confirmed pairing session, never as an ordinary sync batch. */
class SyncSnapshot(private val context: Context, private val database: VaultDatabase,
    private val photos: EncryptedPhotoStore) {
    suspend fun export(output: OutputStream, localKey: ByteArray, pairingKey: ByteArray,
        joiningMember: SyncMembershipEntity, windowsPeer: Boolean = false): SyncMembershipEventEntity? {
        require(pairingKey.size == 32)
        val staged = File.createTempFile("sync-enrollment-", ".bin", context.cacheDir)
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
                        require(existing != null || members.count { it.status == MemberStatus.ACTIVE.name } < MAX_ACTIVE_SYNC_DEVICES) {
                            "This vault already has $MAX_ACTIVE_SYNC_DEVICES active Android devices"
                        }
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
                        val contentKey = database.syncContentKey(localKey)
                        val state = SnapshotSyncState(3, vaultId, encode(contentKey), group.transportSecret,
                            if (existing == null) members + joiningMember else members,
                            events,
                            database.syncDao().deviceHeads(vaultId), database.syncDao().recordStates(),
                            database.syncDao().operations(), database.syncDao().tombstones(), database.syncDao().conflicts())
                        contentKey.fill(0)
                        val plain = Gson().toJson(state).toByteArray(Charsets.UTF_8)
                        require(plain.size <= MAX_METADATA) { "Sync history is too large for enrollment" }
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                            init(Cipher.ENCRYPT_MODE, SecretKeySpec(pairingKey, "AES"))
                            updateAAD(MAGIC)
                        }
                        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
                        val data = DataOutputStream(target)
                        data.write(MAGIC)
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
        expectedVaultId: String, localDeviceId: String, approvedPeer: DeviceIdentity): PreparedSyncSnapshot {
        require(pairingKey.size == 32)
        val data = DataInputStream(input)
        require(ByteArray(MAGIC.size).also(data::readFully).contentEquals(MAGIC)) { "Unsupported sync snapshot" }
        val nonce = ByteArray(12).also(data::readFully)
        val size = data.readInt()
        require(size in 16..MAX_METADATA + 16) { "Sync snapshot metadata is too large" }
        val ciphertext = ByteArray(size).also(data::readFully)
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(pairingKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(MAGIC)
            doFinal(ciphertext)
        }
        val state = try { Gson().fromJson(plaintext.toString(Charsets.UTF_8), SnapshotSyncState::class.java) }
        finally { plaintext.fill(0) }
        validate(state, expectedVaultId, localDeviceId, approvedPeer)
        val password = encode(pairingKey).toCharArray()
        val prepared = try { VaultBackupManager(context, database.dao(), photos).prepareRestore(data, password, localKey) }
        finally { password.fill('\u0000') }
        return try {
            require(prepared.data.vaultId == expectedVaultId) { "Snapshot vault mismatch" }
            PreparedSyncSnapshot(state, prepared)
        } catch (failure: Exception) { prepared.close(); throw failure }
    }

    suspend fun commit(prepared: PreparedSyncSnapshot) {
        val restore = prepared.restore
        check(!restore.committed && !restore.committing && !restore.closed) { "Enrollment is no longer available" }
        restore.committing = true
        try {
            database.withTransaction {
                require(database.dao().allEntries().isEmpty() && database.dao().allPasskeys().isEmpty() &&
                    database.dao().allGroupsWithEntries().isEmpty()) { "Join from an empty vault" }
                val localSettings = requireNotNull(database.dao().settings())
                val snapshot = restore.data
                val state = prepared.state
                database.dao().replaceAll(snapshot.entries, snapshot.groups, snapshot.links, snapshot.photos,
                    localSettings.copy(vaultId = state.vaultId, watchSyncEnabled = false), snapshot.passkeys)
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
            }
            restore.committed = true
        } finally {
            restore.committing = false
            if (!restore.committed) restore.close()
        }
    }

    private fun validate(state: SnapshotSyncState, vaultId: String, deviceId: String, peer: DeviceIdentity) {
        require(state.version == 3 && state.vaultId == vaultId)
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
        state.records.forEach { RecordVersion.parse(it.recordVersionJson).validate() }
    }

    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private companion object {
        val MAGIC = "NUVSYNC3".toByteArray(Charsets.US_ASCII)
        val STAGING_AAD = "nuvori-windows-staging-v1".toByteArray(Charsets.US_ASCII)
        const val MAX_METADATA = 16 * 1024 * 1024
    }
}

class PreparedSyncSnapshot internal constructor(internal val state: SnapshotSyncState,
    internal val restore: PreparedRestore) : AutoCloseable {
    override fun close() = restore.close()
}

internal fun SyncOperationEntity.toSyncChange() = SyncChangeRecord(formatVersion, vaultId, deviceId, sequence,
    previousHash, mutationId, entityType, entityId, ChangeKind.valueOf(kind.uppercase()), baseRevision,
    RecordVersion.parse(recordVersionJson), occurredAtUtc, payloadCiphertext, payloadNonce, deviceSignature, hash)
