package com.privatevault.app.backup

import com.privatevault.app.security.KdfParams
import com.privatevault.app.sync.CloudError
import com.privatevault.app.sync.CloudException
import com.privatevault.app.sync.CloudFile
import com.privatevault.app.sync.CloudKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import javax.crypto.AEADBadTagException

class DriveBackupTest {
    @get:Rule val folder = TemporaryFolder()

    private val password = "correct horse battery".toCharArray()
    /** More passes than 2.3.1 used, as a timed phone would choose; small, so the tests stay fast. */
    private val kdf = KdfParams.LEGACY.copy(iterations = 4)
    private val phone = "0123456789abcdef"
    private val otherPhone = "fedcba9876543210"

    private fun body(text: String, key: BackupCipher.Key): ByteArray = ByteArrayOutputStream().also { output ->
        BackupCipher.encrypt(output, key).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private fun open(bytes: ByteArray, password: CharArray): String =
        BackupCipher.decrypt(ByteArrayInputStream(bytes), password).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun oneKeyMakesManyBackupsThatEachOpenWithThePassword() {
        BackupCipher.create(password, kdf).use { key ->
            val first = body("vault on Monday", key)
            val second = body("vault on Tuesday", key)
            // Same header (no password needed for later backups), different encrypted bodies.
            assertArrayEquals(first.copyOf(key.header.size), second.copyOf(key.header.size))
            assertFalse(first.copyOfRange(key.header.size, first.size).contentEquals(body("vault on Monday", key).copyOfRange(key.header.size, first.size)))
            assertEquals("vault on Monday", open(first, password))
            assertEquals("vault on Tuesday", open(second, password))
        }
    }

    @Test fun aWrongPasswordOrAChangedFileDoesNotOpen() {
        val bytes = BackupCipher.create(password, kdf).use { body("secret", it) }
        try { open(bytes, "wrong password!!".toCharArray()); fail("opened with a wrong password") }
        catch (_: AEADBadTagException) { }
        val changed = bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        try { open(changed, password); fail("opened a changed file") } catch (_: Exception) { }
    }

    /** The writer as it was before Drive backup (Nuvori 2.2.4), so existing .pvault files keep opening. */
    @Test fun aFileFromTheEarlierExportCodeStillOpens() {
        val keyset = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.KeyTemplates.get("AES128_GCM_HKDF_1MB"))
        val serialized = ByteArrayOutputStream().also {
            com.google.crypto.tink.CleartextKeysetHandle.write(keyset, com.google.crypto.tink.BinaryKeysetWriter.withOutputStream(it))
        }.toByteArray()
        val magic = "PVLT0001".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(16) { 3 }
        val nonce = ByteArray(12) { 5 }
        val wrappingKey = com.privatevault.app.security.PasswordCrypto.derive(password, salt)
        val wrapped = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(wrappingKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD(magic)
            doFinal(serialized)
        }
        val output = ByteArrayOutputStream()
        val data = java.io.DataOutputStream(output)
        data.write(magic); data.write(salt); data.write(nonce); data.writeInt(wrapped.size); data.write(wrapped)
        keyset.getPrimitive(com.google.crypto.tink.StreamingAead::class.java)
            .newEncryptingStream(data, "private-vault-backup-v1".toByteArray()).use { it.write("made by 2.2.4".toByteArray()) }
        assertEquals("made by 2.2.4", open(output.toByteArray(), password))
    }

    @Test fun newFilesRecordTheirArgon2idSettingsInTheHeader() {
        BackupCipher.create(password, kdf).use { key ->
            val bytes = body("timed", key)
            assertEquals("PVLT0002", String(bytes.copyOf(8), Charsets.US_ASCII))
            val settings = java.nio.ByteBuffer.wrap(bytes, 8, 12)
            assertEquals(listOf(65_536, 4, 1), listOf(settings.int, settings.int, settings.int))
            assertEquals(kdf, BackupCipher.settings(key))
            assertEquals("timed", open(bytes, password))

            // The settings are associated data: an edit cannot pass as a wrong password.
            val fewer = bytes.copyOf().also { it[8 + 7] = 3 }
            try { open(fewer, password); fail("opened with edited settings") } catch (_: AEADBadTagException) { }
            // Settings outside the limits are refused before Argon2id runs.
            val huge = bytes.copyOf().also { it[8 + 1] = 0x7f }
            try { open(huge, password); fail("opened with 2 GiB of memory") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun anOlderDriveKeyMovesToTheTimedSettingsAndItsFilesKeepOpening() {
        val file = DriveBackupKeyFile(folder.newFile("old-key.bin").also { it.delete() })
        val vaultKey = ByteArray(32) { 9 }
        val old = BackupCipher.create(password, KdfParams.LEGACY)
        val oldFile = body("before the upgrade", old)
        file.save(old, vaultKey)
        old.close()

        val loaded = requireNotNull(file.load(vaultKey))
        assertEquals(KdfParams.LEGACY, BackupCipher.settings(loaded))
        assertFalse("a wrong password does not open it", BackupCipher.opens(loaded, "wrong password!!".toCharArray()))
        assertTrue(BackupCipher.opens(loaded, password))
        val stronger = BackupCipher.rewrap(loaded, password, kdf)
        loaded.close()
        file.save(stronger, vaultKey)
        val newFile = body("after the upgrade", stronger)
        stronger.close()

        requireNotNull(file.load(vaultKey)).use { assertEquals(kdf, BackupCipher.settings(it)) }
        assertEquals("before the upgrade", open(oldFile, password))
        assertEquals("after the upgrade", open(newFile, password))
    }

    @Test fun aKeyFileWithAFormat1HeaderStillLoads() {
        // The Drive backup key file of 2.3.1: its header starts with PVLT0001.
        val file = DriveBackupKeyFile(folder.newFile("v1-key.bin").also { it.delete() })
        val vaultKey = ByteArray(32) { 4 }
        BackupCipher.create(password, KdfParams.LEGACY).use { key ->
            val header = key.header
            val v1 = "PVLT0001".toByteArray(Charsets.US_ASCII) + header.copyOfRange(20, header.size)
            assertEquals("PVLT0002", String(header.copyOf(8), Charsets.US_ASCII))
            // Rebuild the same keyset as a format 1 header, as the old code wrote it.
            val rebuilt = BackupCipher.decode(java.nio.ByteBuffer.allocate(4 + v1.size + key.keyset().size)
                .putInt(v1.size).put(v1).put(key.keyset()).array())
            file.save(rebuilt, vaultKey)
        }
        requireNotNull(file.load(vaultKey)).use { assertEquals(KdfParams.LEGACY, BackupCipher.settings(it)) }
    }

    @Test fun aShortPasswordIsRefused() {
        try { BackupCipher.create("short".toCharArray(), kdf); fail("short password accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun theKeyFileOpensOnlyWithTheSameVaultKey() {
        val file = DriveBackupKeyFile(folder.newFile("key.bin").also { it.delete() })
        val vaultKey = ByteArray(32) { it.toByte() }
        assertNull(file.load(vaultKey))
        val bytes = BackupCipher.create(password, kdf).use { key ->
            file.save(key, vaultKey)
            body("saved key", key)
        }
        val loaded = file.load(vaultKey)
        assertNotNull(loaded)
        loaded!!.use { assertEquals("again", open(body("again", it), password)) }
        assertEquals("saved key", open(bytes, password))
        assertNull("another vault key", file.load(ByteArray(32) { 7 }))
        file.delete()
        assertFalse(file.exists())
    }

    @Test fun namesRoundTripAndOtherNamesAreIgnored() {
        val name = DriveBackupNames.name(phone, 1_794_000_000_123L)
        assertEquals("nvbak1.0123456789abcdef.1794000000123", name)
        val parsed = DriveBackupNames.parse(CloudFile("id-1", name, 42, "2026-11-14T10:00:00Z"))
        assertEquals(DriveBackupFile("id-1", phone, 1_794_000_000_123L, 42), parsed)
        assertEquals(16, DriveBackupNames.newBackupId().length)
        listOf("nv1.0011223344556677.p.8899aabbccddeeff", "nvbak1.0123456789ABCDEF.1794000000123",
            "nvbak1.0123456789abcdef.179400000012", "nvbak1.0123456789abcdef", "nvbak2.0123456789abcdef.1794000000123",
            "nvbak1.0123456789abcdef.1794000000123.x").forEach {
            assertNull(it, DriveBackupNames.parse(CloudFile("x", it, 1, "2026-11-14T10:00:00Z")))
        }
    }

    @Test fun cloudSyncIgnoresBackupFiles() {
        val keys = requireNotNull(CloudKeys.derive(ByteArray(32) { 1 }, "vault-a"))
        assertNull(keys.parse(DriveBackupNames.name(phone, 1_794_000_000_123L)))
    }

    @Test fun scheduleRunsWhenDueAndWaitsAfterAFailure() {
        val day = DriveBackupPlan.DAY
        val start = 1_794_000_000_000L
        assertTrue("first backup", DriveBackupPlan.due(start, 0, 0, day))
        assertFalse(DriveBackupPlan.due(start + day - 1, start, start, day))
        assertTrue(DriveBackupPlan.due(start + day, start, start, day))
        assertTrue("clock moved back", DriveBackupPlan.due(start - 1, start, start, day))
        // A failed attempt one minute ago: wait, then try again after the retry delay.
        val failedAt = start + day + 60_000
        assertFalse(DriveBackupPlan.due(failedAt + 60_000, start, failedAt, day))
        assertTrue(DriveBackupPlan.due(failedAt + DriveBackupPlan.RETRY_AFTER, start, failedAt, day))
    }

    @Test fun onlyThisPhonesOlderBackupsAreRemoved() {
        val files = listOf(1L, 2L, 3L, 4L).map { file(phone, it) } + listOf(1L, 2L, 3L).map { file(otherPhone, it) } +
            CloudFile("sync", "nv1.0011223344556677.p.8899aabbccddeeff", 10, "2026-11-14T10:00:00Z")
        assertEquals(listOf(2L, 1L), DriveBackupPlan.obsolete(files, phone).map { it.createdAt })
        assertEquals(7, DriveBackupPlan.available(files).size)
        assertEquals(4L, DriveBackupPlan.available(files).first().createdAt)
    }

    @Test fun aBackupUploadsAndKeepsTheTwoNewest() = runBlocking {
        val drive = MemoryDrive()
        listOf(100L, 200L).forEach { drive.put(DriveBackupNames.name(phone, it), byteArrayOf(1)) }
        drive.put(DriveBackupNames.name(otherPhone, 50L), byteArrayOf(2))
        val temporary = folder.newFile("backup.tmp")
        val made = DriveBackupPass.run(drive, phone, 300L, temporary) { it.write("new backup".toByteArray()) }
        assertEquals(300L, made.createdAt)
        assertEquals(10L, made.size)
        assertFalse("temporary file removed", temporary.exists())
        assertEquals(setOf(DriveBackupNames.name(phone, 300L), DriveBackupNames.name(phone, 200L), DriveBackupNames.name(otherPhone, 50L)),
            drive.files.keys.map { it.name }.toSet())
        assertArrayEquals("new backup".toByteArray(), drive.files.entries.first { it.key.id == made.fileId }.value)
    }

    @Test fun aFailedUploadKeepsTheOldBackups() = runBlocking {
        val drive = MemoryDrive().apply { failUpload = true }
        listOf(100L, 200L).forEach { drive.put(DriveBackupNames.name(phone, it), byteArrayOf(1)) }
        val temporary = folder.newFile("backup.tmp")
        try {
            DriveBackupPass.run(drive, phone, 300L, temporary) { it.write("new".toByteArray()) }
            fail("upload error was hidden")
        } catch (failure: CloudException) { assertEquals(CloudError.OFFLINE, failure.error) }
        assertEquals(2, drive.files.size)
        assertFalse(temporary.exists())
    }

    @Test fun aFailedWriteUploadsNothing() = runBlocking {
        val drive = MemoryDrive()
        drive.put(DriveBackupNames.name(phone, 100L), byteArrayOf(1))
        val temporary = folder.newFile("backup.tmp")
        try {
            DriveBackupPass.run(drive, phone, 300L, temporary) { it.write(1); error("vault locked") }
            fail("write error was hidden")
        } catch (_: IllegalStateException) { }
        assertEquals(1, drive.files.size)
        assertFalse(temporary.exists())
    }

    private fun file(backupId: String, createdAt: Long) =
        CloudFile(UUID.randomUUID().toString(), DriveBackupNames.name(backupId, createdAt), 1, Instant.ofEpochMilli(createdAt).toString())

    private class MemoryDrive : BackupDrive {
        val files = LinkedHashMap<CloudFile, ByteArray>()
        var failUpload = false

        fun put(name: String, bytes: ByteArray) {
            files[CloudFile(UUID.randomUUID().toString(), name, bytes.size.toLong(), "2026-11-14T10:00:00Z")] = bytes
        }

        override fun list() = files.keys.toList()
        override fun upload(name: String, size: Long, content: InputStream): CloudFile {
            if (failUpload) throw CloudException(CloudError.OFFLINE)
            val bytes = content.readBytes()
            assertEquals(size, bytes.size.toLong())
            return CloudFile(UUID.randomUUID().toString(), name, size, "2026-11-14T10:00:00Z").also { files[it] = bytes }
        }
        override fun download(id: String, maxBytes: Long, read: (InputStream) -> Unit) =
            read(ByteArrayInputStream(files.entries.first { it.key.id == id }.value))
        override fun delete(id: String) { files.keys.removeAll { it.id == id } }
    }
}
