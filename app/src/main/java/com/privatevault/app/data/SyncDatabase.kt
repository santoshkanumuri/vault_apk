package com.privatevault.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

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

    @Query("SELECT * FROM sync_device_heads WHERE vaultId = :vaultId AND deviceId = :deviceId")
    abstract suspend fun deviceHead(vaultId: String, deviceId: String): SyncDeviceHeadEntity?

    @Query("SELECT * FROM sync_record_states WHERE entityType = :entityType AND entityId = :entityId")
    abstract suspend fun recordState(entityType: String, entityId: String): SyncRecordStateEntity?

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
