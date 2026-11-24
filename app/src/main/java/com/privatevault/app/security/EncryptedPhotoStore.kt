package com.privatevault.app.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class EncryptedPhotoStore(context: Context) {
    private val directory = File(context.filesDir, "photos").apply { mkdirs() }

    /** Saves [input] in photo format version 2 ([PhotoCipher]): a key derived from [key], never the vault key itself. */
    fun encrypt(input: InputStream, fileName: String, key: ByteArray) {
        File(directory, fileName).outputStream().use { output ->
            PhotoCipher.encrypting(output, key).use { encrypted -> input.copyTo(encrypted, 64 * 1024) }
        }
    }

    fun encrypt(bitmap: Bitmap, fileName: String, key: ByteArray, lossless: Boolean = false) {
        val bytes = ByteArrayOutputStream().use { output ->
            val format = if (!lossless) Bitmap.CompressFormat.JPEG else if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            check(bitmap.compress(format, if (lossless) 100 else 92, output)) { "Could not encode photo." }
            output.toByteArray()
        }
        bytes.inputStream().use { encrypt(it, fileName, key) }
        bytes.fill(0)
    }

    fun createThumbnail(sourceFileName: String, thumbnailFileName: String, key: ByteArray) {
        val bytes = decryptedBytes(sourceFileName, key)
        try {
            val thumbnail = decodePhoto(bytes, 480)
            try { encrypt(thumbnail, thumbnailFileName, key) } finally { thumbnail.recycle() }
        } finally { bytes.fill(0) }
    }

    fun transform(sourceFileName: String, destinationFileName: String, thumbnailFileName: String,
        key: ByteArray, rotateDegrees: Float = 0f, crop: PhotoCrop? = null) {
        require(sourceFileName != destinationFileName)
        val bytes = decryptedBytes(sourceFileName, key)
        try {
            val decoded = decodePhoto(bytes)
            val rotated = if (rotateDegrees != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotateDegrees) }, true) else decoded
            val transformed = if (crop != null) {
                val bounds = crop.pixels(rotated.width, rotated.height)
                Bitmap.createBitmap(rotated, bounds[0], bounds[1], bounds[2], bounds[3])
            } else rotated
            try {
                encrypt(transformed, destinationFileName, key, lossless = true)
                createThumbnail(destinationFileName, thumbnailFileName, key)
            } catch (failure: Exception) {
                delete(destinationFileName)
                delete(thumbnailFileName)
                throw failure
            }
            if (transformed !== rotated) transformed.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
        } finally { bytes.fill(0) }
    }

    fun decryptedBytes(fileName: String, key: ByteArray): ByteArray {
        return openDecrypted(fileName, key).use { it.readBytes() }
    }

    /** Opens a photo saved in either format version ([PhotoCipher]). */
    fun openDecrypted(fileName: String, key: ByteArray): InputStream {
        val input = File(directory, fileName).inputStream()
        return try { PhotoCipher.decrypting(input, key) } catch (failure: Exception) { input.close(); throw failure }
    }

    /**
     * Saves every photo still in format version 1 again in version 2 (security review L-10), one at
     * a time and through a temporary file that is checked before it replaces the original, so a
     * crash or a damaged photo never loses a working copy. Stops early when [keepGoing] turns false
     * (the vault locked). Returns how many photos it upgraded.
     */
    fun upgradeToPhotoKey(key: ByteArray, keepGoing: () -> Boolean = { true }): Int {
        var upgraded = 0
        directory.listFiles()?.filter { it.isFile && !it.name.startsWith(UPGRADE_PREFIX) }?.forEach { file ->
            if (!keepGoing()) return upgraded
            val head = ByteArray(PhotoCipher.MAGIC.size)
            val read = runCatching { file.inputStream().use { java.io.DataInputStream(it).readFully(head) } }
            if (read.isFailure || PhotoCipher.isCurrent(head)) return@forEach
            val temporary = File(directory, UPGRADE_PREFIX + file.name)
            try {
                val original = java.security.MessageDigest.getInstance("SHA-256")
                openDecrypted(file.name, key).use { plain ->
                    temporary.outputStream().use { output ->
                        PhotoCipher.encrypting(output, key).use { encrypted ->
                            java.security.DigestInputStream(plain, original).copyTo(encrypted, 64 * 1024)
                        }
                    }
                }
                val copy = java.security.MessageDigest.getInstance("SHA-256")
                openDecrypted(temporary.name, key).use { plain ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = plain.read(buffer); if (n < 0) break; copy.update(buffer, 0, n) }
                    buffer.fill(0)
                }
                check(java.security.MessageDigest.isEqual(original.digest(), copy.digest())) { "Photo upgrade check failed" }
                // The photo may have been deleted meanwhile; never bring it back.
                if (file.exists() && temporary.renameTo(file)) upgraded++
            } catch (_: Exception) {
                // The original stays as it was and opens as before; the next unlock tries again.
            } finally {
                temporary.delete()
            }
        }
        return upgraded
    }

    fun delete(fileName: String) { File(directory, fileName).delete() }
    fun cleanupAbandonedRestore(retained: Set<String>) {
        directory.listFiles()?.filter {
            (it.name.startsWith("restore-") || it.name.startsWith("draft-") || it.name.startsWith(UPGRADE_PREFIX)) && it.name !in retained
        }?.forEach { it.delete() }
    }
    fun encryptedFile(fileName: String): File = File(directory, fileName)

    private companion object {
        const val UPGRADE_PREFIX = "upgrade-"
    }
}

// ImageDecoder applies EXIF orientation. Only display copies are downsampled.
internal fun decodePhoto(bytes: ByteArray, maxEdge: Int = Int.MAX_VALUE): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
        if (scale < 1.0) decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
    }
