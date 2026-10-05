package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncAttachmentManifestEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.security.EncryptedPhotoStore
import java.io.File

/** One file in the encrypted photo folder. */
internal data class PhotoFileInfo(val name: String, val lastModified: Long)

internal data class PhotoOrphanPlan(
    /** Transfer manifests of photos that no longer exist here. */
    val manifestIds: Set<String>,
    /** Photo rows whose entry is gone and has a known delete tombstone. */
    val photoRows: List<VaultPhoto>,
    /** Encrypted image and thumbnail files no photo row refers to. */
    val files: Set<String>,
)

/**
 * Decides what the unlock sweep may delete. Only data that is provably unreferenced goes: a photo row
 * is removed only when its entry's delete tombstone is known (never because the entry has not
 * arrived yet), and a file only when no row names it and it is old enough not to be mid-save.
 */
internal object PhotoOrphanRules {
    const val FILE_GRACE_MILLIS = 60L * 60 * 1000

    fun plan(photos: List<VaultPhoto>, entryIds: Set<String>, deletedEntryIds: Set<String>,
        manifests: List<SyncAttachmentManifestEntity>, files: List<PhotoFileInfo>, now: Long): PhotoOrphanPlan {
        val orphanRows = photos.filter { it.entryId !in entryIds && it.entryId in deletedEntryIds }
        val livePhotos = photos - orphanRows.toSet()
        val livePhotoIds = livePhotos.mapTo(hashSetOf()) { it.id }
        val referenced = livePhotos.flatMapTo(hashSetOf()) { listOf(it.encryptedFileName, it.encryptedThumbnailFileName) }
        val strayFiles = files.filter { file ->
            file.name !in referenced && file.name.isNotBlank() &&
                // Drafts and restores have their own cleanup with their own retention rules.
                !file.name.startsWith("draft-") && !file.name.startsWith("restore-") &&
                (file.name.endsWith(".vaultphoto") || file.name.endsWith(".vaultthumb")) &&
                now - file.lastModified > FILE_GRACE_MILLIS
        }.mapTo(hashSetOf()) { it.name }
        return PhotoOrphanPlan(manifests.filter { it.ownerEntityType == "entry" && it.attachmentId !in livePhotoIds }
            .mapTo(hashSetOf()) { it.attachmentId }, orphanRows, strayFiles)
    }
}

/** Runs [PhotoOrphanRules] against the open vault at unlock. Returns how many items it removed. */
internal class PhotoOrphanSweeper(private val database: VaultDatabase, private val photoStore: EncryptedPhotoStore) {
    suspend fun sweep(now: Long = System.currentTimeMillis()): Int {
        val folder: File? = photoStore.encryptedFile("probe").parentFile
        val files = folder?.listFiles().orEmpty().filter { it.isFile }.map { PhotoFileInfo(it.name, it.lastModified()) }
        val plan = database.withTransaction {
            val photos = database.dao().allPhotos()
            val entryIds = database.dao().entryIds().toHashSet()
            val deleted = database.syncDao().tombstones().filter { it.entityType == "entry" }
                .mapTo(hashSetOf()) { it.entityId }
            PhotoOrphanRules.plan(photos, entryIds, deleted, database.syncDao().attachments(), files, now).also { plan ->
                plan.photoRows.forEach { database.dao().deletePhoto(it) }
                plan.manifestIds.forEach { database.syncDao().deleteAttachmentManifest(it) }
            }
        }
        plan.photoRows.forEach {
            photoStore.delete(it.encryptedFileName)
            if (it.encryptedThumbnailFileName.isNotBlank()) photoStore.delete(it.encryptedThumbnailFileName)
        }
        plan.files.forEach(photoStore::delete)
        return plan.manifestIds.size + plan.photoRows.size + plan.files.size
    }
}
