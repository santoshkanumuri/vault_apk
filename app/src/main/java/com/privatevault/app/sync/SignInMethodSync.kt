package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SignInMethod
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** The natural key of a stored sign-in method row. */
internal data class SignInMethodKey(val site: String, val provider: String, val accountKey: String)

/**
 * Sign-in methods on this phone (protocol [SyncFeatures.SIGN_IN_METHODS], rules in [SignInMethodSyncRules]).
 *
 * Nuvori for Windows notices them; this phone stores what arrives ([apply]), takes the group's methods when it joins
 * ([adoptFromSnapshot]), and deletes one when the user asks ([delete]). A delete is written at once while every other
 * active member announced the feature. Otherwise the row is hidden and waits ([SignInMethod.pendingDelete]), and
 * [flush] writes it when the gate opens: at unlock, when a sync pass starts and when a peer's features arrive.
 */
internal object SignInMethodSync {
    private const val FLUSH_LIMIT = 50
    private val writing = Mutex()

    private fun SignInMethod.method() = SignInMethodSyncRules.Method(site, provider, account, firstUsedAt, lastUsedAt, useCount)

    /** The row a delete of [entityId] removes: by its stored id, else by the id recomputed under [contentKey]. */
    suspend fun rowFor(database: VaultDatabase, contentKey: ByteArray, entityId: String): SignInMethodKey? {
        val dao = database.signInMethods()
        val row = dao.byEntity(entityId) ?: (dao.all() + dao.pendingDeletes(Int.MAX_VALUE)).firstOrNull {
            SignInMethodSyncRules.entityId(contentKey, it.site, it.provider, it.account) == entityId
        }
        return row?.let { SignInMethodKey(it.site, it.provider, it.accountKey) }
    }

    /**
     * Applies one verified change inside the applier's transaction ([IncomingEntryChangeApplier]). [incoming] is the
     * method of an upsert, null for a delete; [row] is the row a delete removes. Every outcome but obsolete stores the
     * merged version, so this phone's next delete dominates every version seen here.
     */
    suspend fun apply(database: VaultDatabase, change: SyncChangeRecord, incoming: SignInMethodSyncRules.Method?,
        row: SignInMethodKey?, state: SyncRecordStateEntity?, localVersion: RecordVersion, relation: VersionRelation): IncomingResult {
        val dao = database.signInMethods()
        val syncDao = database.syncDao()
        val stored = incoming?.let { dao.byKey(it.site, it.provider, it.accountKey) }
            ?: row?.let { dao.byKey(it.site, it.provider, it.accountKey) }
        val tombstone = syncDao.tombstone(change.entityType, change.entityId)
        val local = when {
            stored?.pendingDelete == true -> SignInMethodSyncRules.Local.PendingDelete(stored.method(), stored.updatedAtUtc, stored.updatedByDevice)
            stored != null -> SignInMethodSyncRules.Local.Live(stored.method(), stored.updatedAtUtc, stored.updatedByDevice)
            tombstone != null -> SignInMethodSyncRules.Local.Deleted(tombstone.deletedAtUtc, tombstone.deletedByDeviceId)
            else -> SignInMethodSyncRules.Local.None
        }
        val outcome = SignInMethodSyncRules.decide(relation, local, incoming, change.occurredAtUtc, change.deviceId)
        if (outcome == SignInMethodSyncRules.Outcome.Obsolete) return IncomingResult.OBSOLETE
        val merged = SignInMethodSyncRules.mergeVersions(localVersion, change.recordVersion)
        syncDao.upsertRecordState(SyncRecordStateEntity(change.entityType, change.entityId,
            maxOf(state?.revision ?: 0L, change.baseRevision) + 1L, merged.toJson()))
        return when (outcome) {
            is SignInMethodSyncRules.Outcome.Store -> {
                val method = outcome.method
                dao.save(SignInMethod(method.site, method.provider, method.accountKey, change.entityId, method.account,
                    method.firstUsedAt, method.lastUsedAt, method.useCount, outcome.at, outcome.device))
                dao.deleteTombstone(change.entityId)
                dao.trim()
                IncomingResult.APPLIED
            }
            SignInMethodSyncRules.Outcome.Delete -> {
                stored?.let { dao.deleteEntity(it.entityId) }
                syncDao.upsertTombstone(SyncTombstoneEntity(change.mutationId, change.entityType, change.entityId,
                    change.deviceId, change.sequence, merged.toJson(), change.occurredAtUtc, change.hash))
                IncomingResult.APPLIED
            }
            else -> IncomingResult.OBSOLETE
        }
    }

    /**
     * A device that joined from a snapshot holds the group's sign-in method changes without applying them. It stores
     * what each entity settles on ([SignInMethodSyncRules.current]). Rows from an earlier group go first: their ids
     * belong to another content key. Best effort: a later change corrects it.
     */
    suspend fun adoptFromSnapshot(database: VaultDatabase, state: SnapshotSyncState) {
        val keyring = state.keyring()
        val contentKey = Base64.getUrlDecoder().decode(state.contentKey)
        try {
            val choices = SignInMethodSyncRules.current(state.operations) { operation ->
                val plaintext = IncomingEntryChangeApplier.decryptBytes(operation.toSyncChange(), keyring)
                try {
                    val json = plaintext.toString(Charsets.UTF_8)
                    if (operation.kind == "upsert") SignInMethodSyncRules.parsePayload(json, operation.entityId, contentKey)
                    else { require(SignInMethodSyncRules.isDelete(json)); null }
                } finally { plaintext.fill(0) }
            }
            database.withTransaction {
                val dao = database.signInMethods()
                dao.clear()
                choices.forEach { choice ->
                    val method = choice.method ?: return@forEach
                    dao.save(SignInMethod(method.site, method.provider, method.accountKey, choice.entityId, method.account,
                        method.firstUsedAt, method.lastUsedAt, method.useCount, choice.operation.occurredAtUtc, choice.operation.deviceId))
                }
                dao.trim()
            }
        } finally { contentKey.fill(0); keyring.wipe() }
    }

    /**
     * Deletes one method because the user asked. With the gate open ([LockedSyncStore.signInMethodSyncOpen]) the
     * delete is written now and returns true (the caller publishes it); otherwise the row is hidden and waits for
     * [flush]. Outside a sync group the row just goes.
     */
    suspend fun delete(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, store: LockedSyncStore,
        vaultKey: ByteArray, method: SignInMethod, clock: Clock = Clock.systemUTC()): Boolean = writing.withLock {
        val open = store.signInMethodSyncOpen()
        val identity = identityStore.getOrCreate()
        database.withTransaction {
            val dao = database.signInMethods()
            val row = dao.byKey(method.site, method.provider, method.accountKey) ?: return@withTransaction false
            val vaultId = database.dao().settings()?.vaultId.orEmpty()
            val group = database.syncDao().vaultState()
            if (group == null || group.vaultId != vaultId) {
                dao.deleteEntity(row.entityId)
                return@withTransaction false
            }
            database.requireLocalSyncAuthor(vaultId, identity.deviceId)
            if (!open) {
                dao.save(row.copy(pendingDelete = true, updatedAtUtc = Instant.now(clock).toString(), updatedByDevice = identity.deviceId))
                return@withTransaction false
            }
            val written = writeDelete(database, identityStore, identity, vaultId, vaultKey, row, Instant.now(clock).toString())
            dao.deleteEntity(row.entityId)
            written
        }
    }

    /** Writes the deletes that waited for the gate. True when it wrote one; the caller publishes them. */
    suspend fun flush(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, store: LockedSyncStore,
        vaultKey: ByteArray, clock: Clock = Clock.systemUTC()): Boolean = writing.withLock {
        if (database.signInMethods().pendingDeletes(1).isEmpty() || !store.signInMethodSyncOpen()) return@withLock false
        val identity = identityStore.getOrCreate()
        database.withTransaction {
            val vaultId = database.dao().settings()?.vaultId.orEmpty()
            if (database.syncDao().vaultState()?.vaultId != vaultId) return@withTransaction false
            database.requireLocalSyncAuthor(vaultId, identity.deviceId)
            var written = false
            database.signInMethods().pendingDeletes(FLUSH_LIMIT).forEach { row ->
                // The time of the user's delete, so a later use elsewhere still wins.
                val at = row.updatedAtUtc.takeIf { AppearanceSyncRules.instant(it) != null } ?: Instant.now(clock).toString()
                written = writeDelete(database, identityStore, identity, vaultId, vaultKey, row, at) || written
                database.signInMethods().deleteEntity(row.entityId)
            }
            written
        }
    }

    /**
     * Signs a delete of [row] as this device's next link, with the version of the record state plus one for this
     * device, and keeps the tombstone. False when the group holds no live record of it (nothing to delete).
     */
    private suspend fun writeDelete(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore,
        identity: DeviceIdentity, vaultId: String, vaultKey: ByteArray, row: SignInMethod, occurredAt: String): Boolean {
        val syncDao = database.syncDao()
        val contentKey = database.syncContentKey(vaultKey)
        try {
            val entityId = SignInMethodSyncRules.entityId(contentKey, row.site, row.provider, row.account)
            val type = SignInMethodSyncRules.ENTITY_TYPE
            val state = syncDao.recordState(type, entityId) ?: return false
            if (syncDao.tombstone(type, entityId) != null) return false
            val head = syncDao.deviceHead(vaultId, identity.deviceId)
            val sequence = (head?.sequence ?: 0L) + 1L
            val version = RecordVersion(RecordVersion.parse(state.recordVersionJson).counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            })
            val mutationId = UUID.randomUUID().toString()
            val payload = SignInMethodSyncRules.deletePayload()
            // The entity ID stays the epoch-1 HMAC; the payload uses the newest content key.
            val sealed = SyncPayloadCrypto.sealFor(database, vaultKey, type, vaultId, mutationId, entityId, payload)
            val change = SyncChangeRecord.createSigned(vaultId, identity.deviceId, sequence, head?.hash ?: GENESIS_HASH,
                mutationId, type, entityId, ChangeKind.DELETE, state.revision, version, occurredAt,
                sealed.ciphertext, sealed.nonce, identityStore::sign)
            syncDao.insertOperation(SyncOperationEntity(change.mutationId, change.formatVersion, change.vaultId, change.deviceId,
                change.sequence, change.previousHash, change.entityType, change.entityId, change.kind.name.lowercase(),
                change.baseRevision, change.recordVersion.toJson(), change.occurredAtUtc, change.payloadCiphertext,
                change.payloadNonce, change.deviceSignature, change.hash))
            syncDao.upsertDeviceHead(SyncDeviceHeadEntity(vaultId, identity.deviceId, sequence, change.hash))
            syncDao.upsertRecordState(SyncRecordStateEntity(type, entityId, state.revision + 1L, version.toJson()))
            syncDao.upsertTombstone(SyncTombstoneEntity(mutationId, type, entityId, identity.deviceId, sequence,
                version.toJson(), occurredAt, change.hash))
            return true
        } finally { contentKey.fill(0) }
    }
}
