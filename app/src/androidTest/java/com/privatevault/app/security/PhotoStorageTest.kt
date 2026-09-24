package com.privatevault.app.security

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class PhotoStorageTest {
    @Test fun originalBytesSurviveAndEditsKeepPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedPhotoStore(context)
        val name = "test-${UUID.randomUUID()}.vaultphoto"
        val thumb = "$name.thumb"
        val croppedName = "$name.cropped"
        val croppedThumb = "$croppedName.thumb"
        val rotatedName = "$name.rotated"
        val rotatedThumb = "$rotatedName.thumb"
        val key = ByteArray(32) { it.toByte() }
        val original = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.rgb(31, 92, 173))
        original.setPixel(900, 300, Color.YELLOW)
        val bytes = ByteArrayOutputStream().use { output -> original.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        try {
            store.encrypt(bytes.inputStream(), name, key)
            assertArrayEquals(bytes, store.decryptedBytes(name, key))
            store.createThumbnail(name, thumb, key)
            val preview = decodePhoto(store.decryptedBytes(thumb, key))
            assertEquals(480, preview.width)
            assertEquals(300, preview.height)
            preview.recycle()
            store.transform(name, croppedName, croppedThumb, key, crop = PhotoCrop(left = .5f))
            assertArrayEquals(bytes, store.decryptedBytes(name, key))
            val cropped = decodePhoto(store.decryptedBytes(croppedName, key))
            assertEquals(800, cropped.width)
            assertEquals(1000, cropped.height)
            assertEquals(Color.YELLOW, cropped.getPixel(100, 300))
            assertEquals(original.getPixel(901, 300), cropped.getPixel(101, 300))
            cropped.recycle()
            store.transform(croppedName, rotatedName, rotatedThumb, key, rotateDegrees = 90f)
            val rotated = decodePhoto(store.decryptedBytes(rotatedName, key))
            assertEquals(1000, rotated.width)
            assertEquals(800, rotated.height)
            rotated.recycle()
        } finally {
            original.recycle(); bytes.fill(0); key.fill(0)
            listOf(name, thumb, croppedName, croppedThumb, rotatedName, rotatedThumb).forEach(store::delete)
        }
    }
}
