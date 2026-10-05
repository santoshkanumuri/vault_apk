package com.privatevault.app.sync

import com.privatevault.app.security.EncryptedPhotoStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class PhotoBlobRef(val hash: String, val size: Long)
internal class MissingPhotoBlobException(val hash: String, val photoId: String = "", val entryId: String = "") :
    Exception("Photo data is still transferring")
internal class PhotoStorageFullException : Exception("Free space is too low to receive this photo")

/** The files here are encrypted with the shared content key, never a phone's local vault key. */
internal class PhotoSyncBlobs(private val directory: File) {
    private val hashPattern = Regex("[a-f0-9]{64}")

    fun create(photos: EncryptedPhotoStore, fileName: String, localKey: ByteArray,
        contentKey: ByteArray, vaultId: String, photoId: String): PhotoBlobRef {
        directory.mkdirs()
        val staged = File.createTempFile("stage-", ".part", directory)
        try {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val key = blobKey(contentKey)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(aad(vaultId, photoId))
                }
                FileOutputStream(staged).use { file ->
                    file.write(nonce)
                    CipherOutputStream(file, cipher).use { encrypted ->
                        photos.openDecrypted(fileName, localKey).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            try {
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= MAX_PHOTO_BYTES) { "Photo is too large to sync" }
                                    encrypted.write(buffer, 0, count)
                                }
                            } finally { buffer.fill(0) }
                        }
                    }
                }
            } finally { key.fill(0) }
            val hash = digest(staged)
            val target = File(directory, hash)
            if (target.exists() && digest(target) != hash) check(target.delete()) { "Could not replace damaged photo data" }
            if (!target.exists()) check(staged.renameTo(target)) { "Could not stage photo for sync" }
            return PhotoBlobRef(hash, target.length())
        } finally { staged.delete() }
    }

    fun has(ref: PhotoBlobRef): Boolean = valid(ref) && file(ref.hash)?.length() == ref.size

    fun materialize(ref: PhotoBlobRef, photos: EncryptedPhotoStore, localKey: ByteArray,
        contentKey: ByteArray, vaultId: String, photoId: String): Pair<String, String> {
        require(has(ref)) { "Photo data is incomplete" }
        val name = "${UUID.randomUUID()}.vaultphoto"
        val thumbnail = "${UUID.randomUUID()}.vaultthumb"
        val key = blobKey(contentKey)
        try {
            File(directory, ref.hash).inputStream().use { input ->
                val nonce = ByteArray(12)
                java.io.DataInputStream(input).readFully(nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(aad(vaultId, photoId))
                }
                CipherInputStream(input, cipher).use { plain -> photos.encrypt(plain, name, localKey) }
            }
            photos.createThumbnail(name, thumbnail, localKey)
            return name to thumbnail
        } catch (failure: Exception) {
            photos.delete(name); photos.delete(thumbnail)
            throw failure
        } finally { key.fill(0) }
    }

    fun partialSize(hash: String): Long {
        require(hashPattern.matches(hash))
        val partial = File(directory, "$hash.part")
        if (partial.length() > MAX_PHOTO_BYTES + 28) {
            check(partial.delete()) { "Could not reset oversized photo transfer" }
            return 0
        }
        return partial.length()
    }

    fun discardPartial(hash: String) {
        require(hashPattern.matches(hash))
        val partial = File(directory, "$hash.part")
        if (partial.exists()) check(partial.delete()) { "Could not reset photo transfer" }
    }

    fun pruneAbandonedPartials(pendingHashes: Set<String>) {
        val oldStageCutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory.listFiles().orEmpty().filter { file ->
            val hash = file.name.removeSuffix(".part")
            file.isFile && file.name.endsWith(".part") &&
                (hashPattern.matches(hash) && hash !in pendingHashes ||
                    file.name.startsWith("stage-") && file.lastModified() < oldStageCutoff)
        }.forEach { check(it.delete()) { "Could not remove abandoned photo transfer" } }
    }

    fun pruneCompleted(retainHashes: Set<String>, olderThan: Long) {
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && hashPattern.matches(file.name) && file.name !in retainHashes &&
                file.lastModified() < olderThan
        }.forEach { check(it.delete()) { "Could not prune acknowledged photo data" } }
    }

    fun file(hash: String): File? {
        require(hashPattern.matches(hash))
        return File(directory, hash).takeIf { it.isFile && it.length() in 28..MAX_PHOTO_BYTES + 28 &&
            digest(it) == hash }
    }

    fun receive(channel: EncryptedSyncChannel, ref: PhotoBlobRef, offset: Long) {
        require(valid(ref) && offset in 0..ref.size)
        directory.mkdirs()
        val partial = File(directory, "${ref.hash}.part")
        require(partial.length() == offset) { "Photo transfer offset changed" }
        if (directory.usableSpace < ref.size - offset) throw PhotoStorageFullException()
        FileOutputStream(partial, true).use { output ->
            var remaining = ref.size - offset
            while (remaining > 0) {
                val bytes = channel.receive()
                require(bytes.size in 1..minOf(64 * 1024L, remaining).toInt()) { "Invalid photo chunk" }
                try { output.write(bytes) } finally { bytes.fill(0) }
                remaining -= bytes.size
            }
            output.fd.sync()
        }
        if (digest(partial) != ref.hash) {
            partial.delete()
            error("Photo data failed verification")
        }
        val target = File(directory, ref.hash)
        if (target.exists() && digest(target) != ref.hash) check(target.delete())
        if (target.exists()) check(partial.delete())
        else check(partial.renameTo(target)) { "Could not finish photo transfer" }
    }

    fun send(channel: EncryptedSyncChannel, file: File, offset: Long) {
        require(offset in 0..file.length())
        file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < offset) skipped += input.skip(offset - skipped).also { require(it > 0) }
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    channel.send(buffer.copyOf(count))
                }
            } finally { buffer.fill(0) }
        }
    }

    private fun valid(ref: PhotoBlobRef) = hashPattern.matches(ref.hash) &&
        ref.size in 28..MAX_PHOTO_BYTES + 28

    private fun blobKey(contentKey: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        require(contentKey.size == 32)
        init(SecretKeySpec(contentKey, "HmacSHA256"))
        doFinal("nuvori-photo-blob-v1".toByteArray(Charsets.US_ASCII))
    }

    private fun aad(vaultId: String, photoId: String) =
        "nuvori-photo-blob-v1:$vaultId:$photoId".toByteArray(Charsets.UTF_8)

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
            } finally { buffer.fill(0) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { const val MAX_PHOTO_BYTES = 128L * 1024 * 1024 }
}
