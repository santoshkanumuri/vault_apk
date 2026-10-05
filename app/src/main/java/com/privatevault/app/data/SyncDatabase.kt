package com.privatevault.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.privatevault.app.sync.DeviceMembership
import com.privatevault.app.sync.MemberStatus
import com.privatevault.app.sync.MAX_ACTIVE_SYNC_DEVICES
import com.privatevault.app.sync.MembershipAction
import com.privatevault.app.sync.SyncMembershipEvent

@Entity(tableName = "sync_peers", primaryKeys = ["vaultId", "deviceId"])
data class SyncPeerEntity(
    val vaultId: String,
    val deviceId: String,
    val publicKey: String,
    val transportSecret: String,
)

@Entity(tableName = "sync_vault_state")
data class SyncVaultStateEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val vaultId: String,
    val contentKey: String,
    val keyEpoch: Long = 1,
    val transportSecret: String = "",
)

@Entity(tableName = "sync_legacy_operations")
data class SyncLegacyOperationEntity(
    @androidx.room.PrimaryKey val mutationId: String,
    val vaultId: String,
    val signedOperationJson: String,
)

@Entity(tableName = "sync_membership_events", primaryKeys = ["vaultId", "sequence"],
    indices = [Index(value = ["hash"], unique = true)])
data class SyncMembershipEventEntity(
    val vaultId: String,
    val sequence: Long,
    val formatVersion: Int,
    val previousHash: String,
    val action: String,
    val issuerDeviceId: String,
    val subjectDeviceId: String,
    val subjectPublicKey: String,
    val keyEpoch: Long,
    val signature: String,
    val hash: String,
) {
    internal fun toEvent() = SyncMembershipEvent(formatVersion, vaultId, sequence, previousHash,
        MembershipAction.valueOf(action), issuerDeviceId, subjectDeviceId, subjectPublicKey,
        keyEpoch, signature, hash)

    internal companion object {
        fun from(event: SyncMembershipEvent) = SyncMembershipEventEntity(event.vaultId, event.sequence,
            event.formatVersion, event.previousHash, event.action.name, event.issuerDeviceId,
            event.subjectDeviceId, event.subjectPublicKey, event.keyEpoch, event.signature, event.hash)
    }
}

@Entity(tableName = "sync_memberships", primaryKeys = ["vaultId", "deviceId"])
data class SyncMembershipEntity(
    val vaultId: String,
    val deviceId: String,
    val displayName: String,
    val identityPublicKey: String,
    val status: String,
    val addedByDeviceId: String,
    val membershipSequence: Long,
    val keyEpoch: Long,
) {
    fun toMembership() = DeviceMembership(vaultId, deviceId, displayName, identityPublicKey,
        MemberStatus.valueOf(status), addedByDeviceId, membershipSequence, keyEpoch)

    companion object {
        fun from(member: DeviceMembership): SyncMembershipEntity {
            member.validate()
            return SyncMembershipEntity(member.vaultId, member.deviceId, member.displayName,
                member.identityPublicKey, member.status.name, member.addedByDeviceId,
                member.membershipSequence, member.keyEpoch)
        }
    }
}

@Entity(
    tableName = "sync_operations",
    indices = [
        Index(value = ["vaultId", "deviceId", "sequence"], unique = true),
        Index(value = ["hash"], unique = true),
        Index(value = ["entityType", "entityId"]),
    ],
)
data class SyncOperationEntity(
    @androidx.room.PrimaryKey val mutationId: String,
    val formatVersion: Int,
    val vaultId: String,
    val deviceId: String,
    val sequence: Long,
    val previousHash: String,
    val entityType: String,
    val entityId: String,
    val kind: String,
    val baseRevision: Long,
    val recordVersionJson: String,
    val occurredAtUtc: String,
    val payloadCiphertext: String,
    val payloadNonce: String,
    val deviceSignature: String,
    val hash: String,
) {
    init {
        require(mutationId.isNotBlank() && vaultId.isNotBlank() && deviceId.isNotBlank())
        require(sequence > 0 && formatVersion > 0)
        require(entityType.isNotBlank() && entityId.isNotBlank())
        require(kind == "upsert" || kind == "delete")
        require(recordVersionJson.isNotBlank() && deviceSignature.isNotBlank() && hash.isNotBlank())
    }
}

@Entity(tableName = "sync_device_heads", primaryKeys = ["vaultId", "deviceId"])
data class SyncDeviceHeadEntity(
    val vaultId: String,
    val deviceId: String,
    val sequence: Long,
    val hash: String,
)

@Entity(tableName = "sync_record_states", primaryKeys = ["entityType", "entityId"])
data class SyncRecordStateEntity(
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val recordVersionJson: String,
)

@Entity(
    tableName = "sync_tombstones",
    indices = [Index(value = ["entityType", "entityId"], unique = true)],
)
data class SyncTombstoneEntity(
    @androidx.room.PrimaryKey val tombstoneId: String,
    val entityType: String,
    val entityId: String,
    val deletedByDeviceId: String,
    val deleteSequence: Long,
    val recordVersionJson: String,
    val deletedAtUtc: String,
    val changeHash: String,
)

@Entity(
    tableName = "sync_attachment_manifests",
    indices = [Index(value = ["ownerEntityType", "ownerEntityId"])],
)
data class SyncAttachmentManifestEntity(
    @androidx.room.PrimaryKey val attachmentId: String,
    val ownerEntityType: String,
    val ownerEntityId: String,
    val encryptedFileName: String,
    val ciphertextHash: String,
    val sizeBytes: Long,
    val keyEpoch: Long,
)

@Entity(
    tableName = "sync_conflicts",
    indices = [Index(value = ["entityType", "entityId"])],
)
data class SyncConflictEntity(
    @androidx.room.PrimaryKey val conflictId: String,
    val entityType: String,
    val entityId: String,
    val localChangeHash: String,
    val remoteChangeHash: String,
    val localVersionJson: String,
    val remoteVersionJson: String,
    val detectedAtUtc: String,
    val resolvedAtUtc: String = "",
)

@Entity(
    tableName = "sync_peer_acknowledgements",
    primaryKeys = ["vaultId", "peerDeviceId", "sourceDeviceId"],
)
data class SyncPeerAcknowledgementEntity(
    val vaultId: String,
    val peerDeviceId: String,
    val sourceDeviceId: String,
    val sequence: Long,
    val acknowledgedAtUtc: String,
)

@Dao
abstract class SyncDao {
    @Upsert
    abstract suspend fun savePeer(peer: SyncPeerEntity)

    @Query("SELECT * FROM sync_peers WHERE vaultId = :vaultId")
    abstract suspend fun peers(vaultId: String): List<SyncPeerEntity>

    @Upsert
    abstract suspend fun saveVaultState(state: SyncVaultStateEntity)

    @Query("SELECT * FROM sync_vault_state WHERE id = 1")
    abstract suspend fun vaultState(): SyncVaultStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun archiveOperation(operation: SyncLegacyOperationEntity)

    @Query("SELECT * FROM sync_legacy_operations ORDER BY mutationId")
    abstract suspend fun archivedOperations(): List<SyncLegacyOperationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertMembershipEvent(event: SyncMembershipEventEntity)

    @Query("SELECT * FROM sync_membership_events WHERE vaultId = :vaultId ORDER BY sequence")
    abstract suspend fun membershipEvents(vaultId: String): List<SyncMembershipEventEntity>

    @Query("SELECT * FROM sync_memberships WHERE vaultId = :vaultId ORDER BY deviceId")
    abstract suspend fun memberships(vaultId: String): List<SyncMembershipEntity>

    @Query("SELECT * FROM sync_record_states")
    abstract suspend fun recordStates(): List<SyncRecordStateEntity>

    @Query("SELECT * FROM sync_tombstones")
    abstract suspend fun tombstones(): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_conflicts")
    abstract suspend fun conflicts(): List<SyncConflictEntity>

    @Query("UPDATE sync_conflicts SET resolvedAtUtc = :resolvedAt WHERE conflictId = :conflictId")
    abstract suspend fun resolveConflict(conflictId: String, resolvedAt: String)

    @Query("SELECT * FROM sync_attachment_manifests")
    abstract suspend fun attachments(): List<SyncAttachmentManifestEntity>

    @Query("SELECT * FROM sync_attachment_manifests WHERE attachmentId = :id")
    abstract suspend fun attachment(id: String): SyncAttachmentManifestEntity?

    @Query("SELECT * FROM sync_attachment_manifests WHERE ownerEntityType = :ownerType AND ownerEntityId = :ownerId")
    abstract suspend fun attachmentsForOwner(ownerType: String, ownerId: String): List<SyncAttachmentManifestEntity>

    @Query("DELETE FROM sync_attachment_manifests WHERE attachmentId = :id")
    abstract suspend fun deleteAttachmentManifest(id: String)

    @Query("DELETE FROM sync_attachment_manifests WHERE ownerEntityType = :ownerType AND ownerEntityId = :ownerId")
    abstract suspend fun deleteAttachmentManifestsForOwner(ownerType: String, ownerId: String)

    @Query("SELECT COUNT(*) FROM sync_memberships WHERE vaultId = :vaultId AND status = 'ACTIVE'")
    abstract suspend fun activeMembershipCount(vaultId: String): Int

    @Upsert
    protected abstract suspend fun saveMembership(membership: SyncMembershipEntity)

    @Transaction
    open suspend fun upsertMembership(membership: SyncMembershipEntity) {
        membership.toMembership().validate()
        val previous = membership(membership.vaultId, membership.deviceId)
        if (membership.status == MemberStatus.ACTIVE.name && previous?.status != MemberStatus.ACTIVE.name) {
            require(activeMembershipCount(membership.vaultId) < MAX_ACTIVE_SYNC_DEVICES) {
                "This vault already has $MAX_ACTIVE_SYNC_DEVICES active Android devices"
            }
        }
        saveMembership(membership)
    }

    @Query("SELECT * FROM sync_memberships WHERE vaultId = :vaultId AND deviceId = :deviceId")
    abstract suspend fun membership(vaultId: String, deviceId: String): SyncMembershipEntity?

    @Upsert
    abstract suspend fun upsertEntryForSync(entry: VaultEntry)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertOperation(operation: SyncOperationEntity)

    @Upsert
    abstract suspend fun upsertDeviceHead(head: SyncDeviceHeadEntity)

    @Upsert
    abstract suspend fun upsertRecordState(state: SyncRecordStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTombstone(tombstone: SyncTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAttachmentManifest(manifest: SyncAttachmentManifestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertConflict(conflict: SyncConflictEntity)

    @Upsert
    abstract suspend fun upsertPeerAcknowledgement(acknowledgement: SyncPeerAcknowledgementEntity)

    @Query("SELECT * FROM sync_operations ORDER BY deviceId, sequence")
    abstract suspend fun operations(): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_operations WHERE vaultId = :vaultId AND deviceId = :deviceId AND sequence > :afterSequence ORDER BY sequence LIMIT :limit")
    abstract suspend fun operationsAfter(vaultId: String, deviceId: String, afterSequence: Long,
        limit: Int): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_device_heads WHERE vaultId = :vaultId ORDER BY deviceId")
    abstract suspend fun deviceHeads(vaultId: String): List<SyncDeviceHeadEntity>

    @Query("SELECT * FROM sync_operations WHERE mutationId = :mutationId")
    abstract suspend fun operation(mutationId: String): SyncOperationEntity?

    @Query("SELECT * FROM sync_operations WHERE entityType = :entityType AND entityId = :entityId ORDER BY rowid DESC LIMIT 1")
    abstract suspend fun lastOperationForEntity(entityType: String, entityId: String): SyncOperationEntity?

    @Query("SELECT * FROM sync_operations WHERE entityType = :entityType AND entityId = :entityId ORDER BY rowid DESC")
    abstract suspend fun operationsForEntity(entityType: String, entityId: String): List<SyncOperationEntity>

    @Query("SELECT * FROM sync_device_heads WHERE vaultId = :vaultId AND deviceId = :deviceId")
    abstract suspend fun deviceHead(vaultId: String, deviceId: String): SyncDeviceHeadEntity?

    @Query("SELECT * FROM sync_record_states WHERE entityType = :entityType AND entityId = :entityId")
    abstract suspend fun recordState(entityType: String, entityId: String): SyncRecordStateEntity?

    @Query("SELECT * FROM sync_tombstones WHERE entityType = :entityType AND entityId = :entityId")
    abstract suspend fun tombstone(entityType: String, entityId: String): SyncTombstoneEntity?

    @Transaction
    open suspend fun saveEntryAndOperation(
        entry: VaultEntry,
        operation: SyncOperationEntity,
        head: SyncDeviceHeadEntity,
    ) {
        require(operation.kind == "upsert")
        require(operation.entityType == "entry" && operation.entityId == entry.id)
        require(operation.vaultId == head.vaultId && operation.deviceId == head.deviceId)
        require(operation.sequence == head.sequence && operation.hash == head.hash)
        upsertEntryForSync(entry)
        insertOperation(operation)
        upsertDeviceHead(head)
    }
}
