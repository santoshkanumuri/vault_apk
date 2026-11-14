package com.privatevault.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.privatevault.app.appearance.AppearanceStore
import com.privatevault.app.appearance.StoredAppearance
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.VaultDatabase
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Writes the pending vault appearance as one signed upsert of ([AppearanceSyncRules.ENTITY_TYPE],
 * [AppearanceSyncRules.ENTITY_ID]), like [LocalDeviceNameChangeWriter]. The caller checks the feature
 * gate first ([AppearanceSyncRules.gateOpen]); see [AppearanceSync.flush].
 */
internal class LocalAppearanceChangeWriter(private val database: VaultDatabase,
    private val identityStore: AndroidDeviceIdentityStore, private val clock: Clock = Clock.systemUTC()) {
    private class Written(val stored: StoredAppearance, val occurredAtUtc: String)

    /**
     * Returns false and writes nothing when no local change is pending, or this device is not an active
     * member of an enrolled group for [vaultId]. After the write the stored value carries this device's
     * ID and the change's `occurredAtUtc` (the time of the local choice), and is no longer pending.
     */
    suspend fun write(context: Context, vaultKey: ByteArray, vaultId: String): Boolean {
        require(vaultKey.size == 32)
        val identity = identityStore.getOrCreate()
        val written = database.withTransaction {
            val dao = database.syncDao()
            if (database.dao().settings()?.vaultId != vaultId) return@withTransaction null
            // Not enrolled: the appearance stays on this device.
            if (dao.vaultState()?.vaultId != vaultId) return@withTransaction null
            if (dao.membership(vaultId, identity.deviceId)?.status != MemberStatus.ACTIVE.name) return@withTransaction null
            // Read inside the transaction: the applier reads and sets the stored value inside its own.
            val stored = AppearanceStore.read(context)
            if (!stored.pendingSync) return@withTransaction null
            val occurredAt = stored.updatedAtUtc?.takeIf { AppearanceSyncRules.instant(it) != null }
                ?: Instant.now(clock).toString()
            val head = dao.deviceHead(vaultId, identity.deviceId)
            val state = dao.recordState(AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID)
            val current = state?.let { RecordVersion.parse(it.recordVersionJson) } ?: RecordVersion(emptyMap())
            val sequence = (head?.sequence ?: 0L) + 1L
            val version = RecordVersion(current.counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            })
            val mutationId = UUID.randomUUID().toString()
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val payload = AppearanceSyncRules.encodePayload(stored.appearance)
            val contentKey = database.syncContentKey(vaultKey)
            val ciphertext = try {
                AppearanceSyncRules.encrypt(contentKey, AppearanceSyncRules.aad(vaultId, mutationId), nonce, payload)
            } finally { payload.fill(0); contentKey.fill(0) }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val change = SyncChangeRecord.createSigned(vaultId, identity.deviceId, sequence,
                head?.hash ?: GENESIS_HASH, mutationId, AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID,
                ChangeKind.UPSERT, state?.revision ?: 0L, version, occurredAt,
                encoder.encodeToString(ciphertext), encoder.encodeToString(nonce), identityStore::sign)
            dao.insertOperation(SyncOperationEntity(change.mutationId, change.formatVersion,
                change.vaultId, change.deviceId, change.sequence, change.previousHash,
                change.entityType, change.entityId, change.kind.name.lowercase(), change.baseRevision,
                change.recordVersion.toJson(), change.occurredAtUtc, change.payloadCiphertext,
                change.payloadNonce, change.deviceSignature, change.hash))
            dao.upsertDeviceHead(SyncDeviceHeadEntity(vaultId, identity.deviceId, sequence, change.hash))
            dao.upsertRecordState(SyncRecordStateEntity(AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID,
                change.baseRevision + 1L, version.toJson()))
            Written(stored, occurredAt)
        } ?: return false
        // A value chosen or received after the read above stays as it is (and pending, if local).
        AppearanceStore.markWritten(context, written.stored.appearance, written.stored.updatedAtUtc,
            written.occurredAtUtc, identity.deviceId)
        return true
    }
}
