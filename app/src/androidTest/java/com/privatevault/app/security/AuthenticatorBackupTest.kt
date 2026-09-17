package com.privatevault.app.security

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.backup.VaultBackupManager
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class AuthenticatorBackupTest {
    private class VaultContext(base: Context, val root: File) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name)
    }

    @Test fun completeTransferRejectsBadBackupsAndPreservesPhotosWhenEditing() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "test-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val sourceContext = VaultContext(base, File(root, "source").apply { mkdirs() })
        val targetContext = VaultContext(base, File(root, "target").apply { mkdirs() })
        val sourceKey = ByteArray(32) { 7 }; val targetKey = ByteArray(32) { 9 }
        val source = VaultDatabase.open(sourceContext, sourceKey)
        val target = VaultDatabase.open(targetContext, targetKey)
        val password = "Backup test passphrase".toCharArray()
        try {
            val sourcePhotos = EncryptedPhotoStore(sourceContext)
            val targetPhotos = EncryptedPhotoStore(targetContext)
            val group = VaultGroup(name = "Example bank", notes = "Group notes")
            source.dao().insertGroup(group)
            val authenticator = VaultEntry(type = EntryType.AUTHENTICATOR, title = "Example", primaryValue = "account", secondaryValue = "JBSWY3DPEHPK3PXP", totpAlgorithm = "SHA256", totpDigits = 8, totpPeriod = 60, favorite = true, notes = "Keep recovery separately", tags = "finance", sortOrder = 4, lastOpenedAt = 123)
            val records = listOf(authenticator) + listOf(EntryType.CARD, EntryType.PASSWORD, EntryType.QUESTION, EntryType.NOTE).map {
                VaultEntry(type = it, title = it.name, primaryValue = "sample", secondaryValue = "private", tertiaryValue = "12/30", fourthValue = "123", network = "RuPay", color = 0xFF202020, notes = "Notes")
            }
            records.forEach { source.dao().saveEntry(it, setOf(group.id)) }
            source.dao().saveSettings(VaultSettings(lightMode = true, nfcEnabled = true))
            val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val photoBytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            bitmap.recycle()
            val photo = VaultPhoto(entryId = authenticator.id, encryptedFileName = "original", encryptedThumbnailFileName = "thumb", isCover = true)
            sourcePhotos.encrypt(photoBytes.inputStream(), photo.encryptedFileName, sourceKey)
            sourcePhotos.createThumbnail(photo.encryptedFileName, photo.encryptedThumbnailFileName, sourceKey)
            source.dao().insertPhoto(photo)
            source.dao().saveEntry(authenticator.copy(notes = "Updated notes"), setOf(group.id))
            assertEquals(1, source.dao().entry(authenticator.id)!!.photos.size)
            source.dao().insertGroup(group.copy(name = "Renamed bank"))
            assertEquals(records.size, source.dao().allLinks().size)
            val expected = source.dao().backupSnapshot()
            val bytes = ByteArrayOutputStream().use { output -> VaultBackupManager(sourceContext, source.dao(), sourcePhotos).export(output, password, sourceKey); output.toByteArray() }
            val old = VaultEntry(type = EntryType.NOTE, title = "Do not overwrite", notes = "Old contents")
            target.dao().insertEntry(old)
            // Same photo ID as the incoming backup, to catch accidental overwrites before commit.
            val oldPhoto = photo.copy(entryId = old.id, encryptedFileName = "old-photo", encryptedThumbnailFileName = "old-thumb")
            targetPhotos.encrypt(photoBytes.inputStream(), oldPhoto.encryptedFileName, targetKey)
            targetPhotos.createThumbnail(oldPhoto.encryptedFileName, oldPhoto.encryptedThumbnailFileName, targetKey)
            target.dao().insertPhoto(oldPhoto)
            val manager = VaultBackupManager(targetContext, target.dao(), targetPhotos)
            val before = target.dao().backupSnapshot()
            val tampered = bytes.copyOf().apply { this[lastIndex - 12] = (this[lastIndex - 12].toInt() xor 1).toByte() }
            val unsupported = bytes.copyOf().apply { this[0] = 0 }
            for ((candidate, pass) in listOf(bytes to "wrong password".toCharArray(), tampered to password, bytes.copyOf(bytes.size - 20) to password, unsupported to password)) {
                assertTrue(runCatching { manager.prepareRestore(candidate.inputStream(), pass, targetKey) }.isFailure)
                assertEquals(before, target.dao().backupSnapshot())
                assertArrayEquals(photoBytes, targetPhotos.decryptedBytes(oldPhoto.encryptedFileName, targetKey))
            }
            manager.prepareRestore(bytes.inputStream(), password, targetKey).use { pending ->
                assertEquals(before, target.dao().backupSnapshot())
                // Cancelling after full validation preserves everything.
            }
            assertEquals(before, target.dao().backupSnapshot())
            target.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'Simulated restore failure'); END")
            try {
                manager.prepareRestore(bytes.inputStream(), password, targetKey).use { pending ->
                    assertTrue(runCatching { manager.commitRestore(pending) }.isFailure)
                }
                assertEquals(before, target.dao().backupSnapshot())
                assertArrayEquals(photoBytes, targetPhotos.decryptedBytes(oldPhoto.encryptedFileName, targetKey))
            } finally { target.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore") }
            manager.prepareRestore(bytes.inputStream(), password, targetKey).use { manager.commitRestore(it) }
            val restored = target.dao().backupSnapshot()
            assertEquals(expected.entries.toSet(), restored.entries.toSet())
            assertEquals(expected.groups, restored.groups)
            assertEquals(expected.links.toSet(), restored.links.toSet())
            assertEquals(expected.lightMode, restored.lightMode)
            assertEquals(expected.nfcEnabled, restored.nfcEnabled)
            val restoredPhoto = restored.photos.single()
            assertEquals(photo.id, restoredPhoto.id)
            assertTrue(restoredPhoto.isCover)
            assertArrayEquals(photoBytes, targetPhotos.decryptedBytes(restoredPhoto.encryptedFileName, targetKey))
            val restoredCode = restored.entries.single { it.type == EntryType.AUTHENTICATOR }
            assertEquals(Totp.code(authenticator.secondaryValue, "SHA256", 8, 60, 1234567890), Totp.code(restoredCode.secondaryValue, restoredCode.totpAlgorithm, restoredCode.totpDigits, restoredCode.totpPeriod, 1234567890))
        } finally { source.close(); target.close(); password.fill('\u0000'); sourceKey.fill(0); targetKey.fill(0); root.deleteRecursively() }
    }
}
