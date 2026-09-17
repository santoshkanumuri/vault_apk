package com.privatevault.app.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedPhotoStore(context: Context) {
    private val directory = File(context.filesDir, "photos").apply { mkdirs() }

    fun encrypt(input: InputStream, fileName: String, key: ByteArray) {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        File(directory, fileName).outputStream().use { output ->
            output.write(nonce)
            CipherOutputStream(output, cipher).use { encrypted -> input.copyTo(encrypted, 64 * 1024) }
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

    fun transform(fileName: String, thumbnailFileName: String, key: ByteArray, rotateDegrees: Float = 0f, crop: PhotoCrop? = null) {
        val bytes = decryptedBytes(fileName, key)
        try {
            val decoded = decodePhoto(bytes)
            val rotated = if (rotateDegrees != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotateDegrees) }, true) else decoded
            val transformed = if (crop != null) {
                val bounds = crop.pixels(rotated.width, rotated.height)
                Bitmap.createBitmap(rotated, bounds[0], bounds[1], bounds[2], bounds[3])
            } else rotated
            replace(transformed, fileName, key)
            createThumbnail(fileName, thumbnailFileName, key)
            if (transformed !== rotated) transformed.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
        } finally { bytes.fill(0) }
    }

    private fun replace(bitmap: Bitmap, fileName: String, key: ByteArray) {
        val temporary = "$fileName.tmp"
        encrypt(bitmap, temporary, key, lossless = true)
        val destination = File(directory, fileName)
        val staged = File(directory, temporary)
        Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun decryptedBytes(fileName: String, key: ByteArray): ByteArray {
        File(directory, fileName).inputStream().use { input ->
            val nonce = ByteArray(12)
            java.io.DataInputStream(input).readFully(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            }
            return CipherInputStream(input, cipher).use { it.readBytes() }
        }
    }

    fun delete(fileName: String) { File(directory, fileName).delete() }
    fun cleanupAbandonedRestore(retained: Set<String>) {
        directory.listFiles()?.filter { it.name.startsWith("restore-") && it.name !in retained }?.forEach { it.delete() }
    }
    fun encryptedFile(fileName: String): File = File(directory, fileName)
}

// ImageDecoder applies EXIF orientation. Only display copies are downsampled.
internal fun decodePhoto(bytes: ByteArray, maxEdge: Int = Int.MAX_VALUE): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
        if (scale < 1.0) decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
    }
