package com.privatevault.app.sync

import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.VaultDatabase
import java.time.Instant
import java.util.UUID

/**
 * Protocol `master-password-v1` on this phone ([MasterPasswordRules]): what it reads from the changes it holds,
 * and the two changes it writes. The managing device writes a password check when it changes its master
 * password while it shares the vault. Any other device that has not switched to the current check yet opens
 * with its old password, its sync waits ([LockedSyncStore.masterPasswordSwitchPending]), and it asks for the new
 * password after unlock; once that password passes the check, it wraps its vault key under it and writes a switch.
 * Nothing here holds or sends a password: the check is an Argon2id-based verifier inside the group's encrypted
 * changes (see [MasterPasswordRules]).
 */
internal object MasterPasswordSync {
    /** Shown where sync waits for the switch. */
    const val SWITCH_FIRST = "Enter the new master password from your managing device to sync again."

    /** The check this phone still has to switch to. [managerName] names the device that wrote it. */
    class Pending(val vaultId: String, val current: MasterPasswordRules.Candidate, val managerName: String) {
        val epoch: Long get() = current.check.epoch

        /** True when [password] is the managing device's new password. One Argon2id run: call off the main thread. */
        fun matches(password: CharArray): Boolean = current.check.matches(vaultId, password)
    }

    /**
     * The group as this phone sees it: the current check (null before any change while shared), the switch
     * this phone owes (null when none), and the newest membership event, which a new check names.
     */
    class State(val vaultId: String, val localDeviceId: String, val managerDeviceId: String, val transferTarget: String?,
        val current: MasterPasswordRules.Candidate?, val pending: Pending?, val membershipSequence: Long,
        private val candidates: List<MasterPasswordRules.Candidate>) {
        val manages: Boolean get() = managerDeviceId == localDeviceId
        val transferPending: Boolean get() = transferTarget != null
        val nextEpoch: Long get() = (current?.check?.epoch ?: 0L) + 1L

        /** True when [deviceId] signed a copy of the current check (or there is none to carry over). */
        fun carriedOverBy(deviceId: String): Boolean {
            val check = current?.check ?: return true
            return candidates.any { it.author == deviceId && MasterPasswordRules.isCopyOf(it.check, check) }
        }
    }

    /**
     * Null when this vault has no sync group. Decrypts the checks and switches this phone holds. [heldHash] is the
     * check this phone was last asked to switch to ([LockedSyncStore.masterPasswordSwitchHeld]), which still
     * counts while the managing device has no check of its own yet ([MasterPasswordRules.currentOrHeld]).
     */
    suspend fun state(database: VaultDatabase, vaultKey: ByteArray, localDeviceId: String,
        heldHash: (vaultId: String) -> String? = { null }): State? {
        val syncDao = database.syncDao()
        val vaultId = database.dao().settings()?.vaultId ?: return null
        val events = syncDao.membershipEvents(vaultId).map { it.toEvent() }
        if (events.isEmpty()) return null
        val membership = SyncMembershipManager.verify(events)
        val steps = events.map { it.action.name to it.subjectDeviceId }
        val candidates = mutableListOf<MasterPasswordRules.Candidate>()
        var switched: Long? = null
        val operations = (syncDao.operationsForEntity(MasterPasswordRules.ENTITY_TYPE, vaultId) +
            syncDao.operationsForEntity(MasterPasswordRules.ENTITY_TYPE, localDeviceId)).filter { it.vaultId == vaultId }
        val keyring = database.syncKeyring(vaultKey)
        try {
            operations.forEach { operation ->
                // A change of an epoch whose key this phone lacks stays unread until the key arrives.
                val plaintext = runCatching { SyncPayloadCrypto.decrypt(operation.toChangeRecord(), keyring) }.getOrNull()
                    ?: return@forEach
                val record = try {
                    MasterPasswordRules.read(vaultId, operation.deviceId, operation.entityId, operation.kind == "upsert",
                        plaintext.toString(Charsets.UTF_8))
                } finally { plaintext.fill(0) }
                when (record) {
                    is MasterPasswordRules.Record.CheckRecord -> {
                        candidates += MasterPasswordRules.Candidate(operation.deviceId, operation.hash, record.check)
                        // This phone's own check (written as managing device, or carried over at a transfer)
                        // holds its password as much as a switch does.
                        if (operation.deviceId == localDeviceId) switched = maxOf(switched ?: 0L, record.check.epoch)
                    }
                    is MasterPasswordRules.Record.Switched ->
                        if (operation.deviceId == localDeviceId) switched = maxOf(switched ?: 0L, record.epoch)
                    null -> Unit
                }
            }
        } finally { keyring.wipe() }
        val current = MasterPasswordRules.currentOrHeld(candidates, events.size.toLong(), membership.managerDeviceId,
            runCatching { heldHash(vaultId) }.getOrNull())
        val active = membership.members.any { it.deviceId == localDeviceId && it.status == MemberStatus.ACTIVE.name }
        val pending = current?.takeIf {
            active && !MasterPasswordRules.satisfied(localDeviceId, it, switched, MasterPasswordRules.addedAt(steps, localDeviceId))
        }?.let { Pending(vaultId, it, syncDao.membership(vaultId, it.author)?.displayName.orEmpty()) }
        return State(vaultId, localDeviceId, membership.managerDeviceId, membership.pendingTransferDeviceId,
            current, pending, events.size.toLong(), candidates)
    }

    /**
     * Writes [check] as this phone's password check. Only the managing device writes one, with no transfer
     * pending and for the membership event it names.
     */
    suspend fun writeCheck(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, vaultKey: ByteArray,
        check: MasterPasswordRules.Check) = write(database, identityStore, vaultKey, check.toPayload(), forVault = true) { membership, deviceId ->
        require(membership.managerDeviceId == deviceId && membership.pendingTransferDeviceId == null) {
            "Only the managing device changes the shared master password"
        }
        require(check.membershipSequence == membership.events.size.toLong()) { "The sync group changed. Try again." }
    }

    /**
     * Signs a copy of the current [check] under this phone's own device ID, before it accepts the managing role
     * offered to it ([MasterPasswordRules.isCopyOf]), so the check stays current once the role moves here.
     */
    suspend fun writeCarryOver(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, vaultKey: ByteArray,
        check: MasterPasswordRules.Check) = write(database, identityStore, vaultKey, check.toPayload(), forVault = true) { membership, deviceId ->
        require(membership.pendingTransferDeviceId == deviceId && !membership.transferAccepted) {
            "No managing-role transfer is offered to this device"
        }
        require(check.membershipSequence in 1..membership.events.size.toLong()) { "The sync group changed. Try again." }
    }

    /** Writes this phone's switch to check [epoch]: its vault now opens with that check's password. */
    suspend fun writeSwitched(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, vaultKey: ByteArray,
        epoch: Long) = write(database, identityStore, vaultKey, MasterPasswordRules.switchedPayload(epoch), forVault = false) { _, _ -> }

    /** Mirrors [LocalDeviceNameChangeWriter]: one signed change in this phone's chain, in one transaction. */
    private suspend fun write(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, vaultKey: ByteArray,
        body: JsonObject, forVault: Boolean, allowed: (VerifiedMembership, String) -> Unit) {
        require(vaultKey.size == 32)
        val identity = identityStore.getOrCreate()
        database.withTransaction {
            val vaultId = requireNotNull(database.dao().settings()).vaultId
            val dao = database.syncDao()
            requireNotNull(dao.vaultState()) { "This vault has no sync group" }
            database.requireLocalSyncAuthor(vaultId, identity.deviceId)
            allowed(SyncMembershipManager.verify(dao.membershipEvents(vaultId).map { it.toEvent() }), identity.deviceId)
            val entityId = if (forVault) vaultId else identity.deviceId
            val type = MasterPasswordRules.ENTITY_TYPE
            val head = dao.deviceHead(vaultId, identity.deviceId)
            val state = dao.recordState(type, entityId)
            val current = state?.let { RecordVersion.parse(it.recordVersionJson) } ?: RecordVersion(emptyMap())
            val sequence = (head?.sequence ?: 0L) + 1L
            val version = RecordVersion(current.counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            })
            val mutationId = UUID.randomUUID().toString()
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            val sealed = try {
                SyncPayloadCrypto.sealFor(database, vaultKey, type, vaultId, mutationId, entityId, payload)
            } finally { payload.fill(0) }
            val change = SyncChangeRecord.createSigned(vaultId, identity.deviceId, sequence,
                head?.hash ?: GENESIS_HASH, mutationId, type, entityId,
                ChangeKind.UPSERT, state?.revision ?: 0L, version, Instant.now().toString(),
                sealed.ciphertext, sealed.nonce, identityStore::sign)
            dao.insertOperation(SyncOperationEntity(change.mutationId, change.formatVersion,
                change.vaultId, change.deviceId, change.sequence, change.previousHash,
                change.entityType, change.entityId, change.kind.name.lowercase(), change.baseRevision,
                change.recordVersion.toJson(), change.occurredAtUtc, change.payloadCiphertext,
                change.payloadNonce, change.deviceSignature, change.hash))
            dao.upsertDeviceHead(SyncDeviceHeadEntity(vaultId, identity.deviceId, sequence, change.hash))
            dao.upsertRecordState(SyncRecordStateEntity(type, entityId, change.baseRevision + 1L, version.toJson()))
        }
    }

    private fun SyncOperationEntity.toChangeRecord() = SyncChangeRecord(formatVersion, vaultId, deviceId, sequence,
        previousHash, mutationId, entityType, entityId, if (kind == "upsert") ChangeKind.UPSERT else ChangeKind.DELETE,
        baseRevision, RecordVersion.parse(recordVersionJson), occurredAtUtc,
        payloadCiphertext, payloadNonce, deviceSignature, hash)
}
