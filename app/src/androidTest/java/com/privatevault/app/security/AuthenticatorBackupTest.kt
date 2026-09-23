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

    @Test fun cardFolderBecomesGroupWithoutLosingItsCard() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "test-card-folder-${UUID.randomUUID()}").apply { mkdirs() }
        val database = VaultDatabase.open(VaultContext(base, root), ByteArray(32) { 3 })
        try {
            val folder = VaultGroup(name = "Travel cards", notes = "Keep together", folderType = EntryType.CARD)
            val card = VaultEntry(type = EntryType.CARD, title = "Travel card")
            database.dao().insertGroup(folder)
            database.dao().saveEntry(card, setOf(folder.id))

            database.dao().convertCardFoldersToGroups()

            val converted = database.dao().allGroupsWithEntries().single().group
            assertEquals(folder.id, converted.id)
            assertEquals(folder.name, converted.name)
            assertEquals(folder.notes, converted.notes)
            assertNull(converted.folderType)
            assertEquals(folder.id, database.dao().entry(card.id)!!.groups.single().id)
            database.dao().saveEntry(card.copy(title = "Updated card"), setOf(folder.id))
            assertEquals(folder.id, database.dao().entry(card.id)!!.groups.single().id)
        } finally {
            database.close()
            root.deleteRecursively()
        }
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
            val (passkey, _) = com.privatevault.app.passkeys.PasskeyCrypto.create(
                com.privatevault.app.passkeys.PasskeyCrypto.request(PasskeyCryptoTest.CREATE, "https://example.com", true), "https://example.com", null)
            source.dao().insertPasskeys(listOf(passkey))
            val sourcePhotos = EncryptedPhotoStore(sourceContext)
            val targetPhotos = EncryptedPhotoStore(targetContext)
            val group = VaultGroup(name = "Example bank", notes = "Group notes")
            source.dao().insertGroup(group)
            val folder = VaultGroup(name = "Private notes", folderType = EntryType.NOTE)
            source.dao().insertGroup(folder)
            val authenticator = VaultEntry(type = EntryType.AUTHENTICATOR, title = "Example", primaryValue = "account", secondaryValue = "JBSWY3DPEHPK3PXP", totpAlgorithm = "SHA256", totpDigits = 8, totpPeriod = 60, favorite = true, notes = "Keep recovery separately", tags = "finance", sortOrder = 4, lastOpenedAt = 123)
            val records = listOf(authenticator.copy(linkedApps = "com.example.bank\ncom.example.other")) + listOf(EntryType.CARD, EntryType.PASSWORD, EntryType.QUESTION, EntryType.NOTE).map {
                VaultEntry(type = it, title = it.name, primaryValue = "sample", secondaryValue = "private", tertiaryValue = "12/30", fourthValue = "123", network = "RuPay", color = 0xFF202020, notes = "Notes",
                    linkedAuthenticatorId = if (it == EntryType.PASSWORD) authenticator.id else "",
                    autofillSignatures = if (it == EntryType.PASSWORD) "com.example.bank=dummy-certificate" else "")
            }
            records.forEach { source.dao().saveEntry(it, setOf(group.id)) }
            source.dao().saveEntry(records.last(), setOf(group.id, folder.id))
            assertTrue(runCatching { source.dao().saveEntry(records.first(), setOf(folder.id)) }.isFailure)
            assertEquals(listOf(authenticator.id), source.dao().authenticatorEntries().map { it.id })
            source.dao().saveSettings(VaultSettings(lightMode = true, nfcEnabled = true,
                backgroundTimeoutMs = 30_000L, inactivityTimeoutMs = 900_000L, masterPasswordIntervalMs = 604_800_000L))
            val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val photoBytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            bitmap.recycle()
            val photo = VaultPhoto(entryId = authenticator.id, encryptedFileName = "original", encryptedThumbnailFileName = "thumb", isCover = true)
            sourcePhotos.encrypt(photoBytes.inputStream(), photo.encryptedFileName, sourceKey)
            sourcePhotos.createThumbnail(photo.encryptedFileName, photo.encryptedThumbnailFileName, sourceKey)
            source.dao().insertPhoto(photo)
            source.dao().saveEntry(records.first().copy(notes = "Updated notes"), setOf(group.id))
            assertEquals(1, source.dao().entry(authenticator.id)!!.photos.size)
            source.dao().insertGroup(group.copy(name = "Renamed bank"))
            assertEquals(records.size + 1, source.dao().allLinks().size)
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
            assertEquals(listOf(passkey), restored.passkeys)
            val assertion = com.privatevault.app.passkeys.PasskeyCrypto.sign(restored.passkeys.single(),
                com.privatevault.app.passkeys.PasskeyCrypto.request(PasskeyCryptoTest.GET, "https://example.com", false), "https://example.com", null)
            val signedResponse = org.json.JSONObject(assertion).getJSONObject("response")
            val verifier = java.security.Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(com.privatevault.app.passkeys.PasskeyCrypto.decode(passkey.publicKey))))
            verifier.update(com.privatevault.app.passkeys.PasskeyCrypto.decode(signedResponse.getString("authenticatorData")))
            verifier.update(java.security.MessageDigest.getInstance("SHA-256").digest(com.privatevault.app.passkeys.PasskeyCrypto.decode(signedResponse.getString("clientDataJSON"))))
            assertTrue(verifier.verify(com.privatevault.app.passkeys.PasskeyCrypto.decode(signedResponse.getString("signature"))))
            assertEquals(expected.entries.toSet(), restored.entries.toSet())
            assertEquals(expected.groups, restored.groups)
            assertEquals(expected.links.toSet(), restored.links.toSet())
            assertEquals(EntryType.NOTE, restored.groups.single { it.id == folder.id }.folderType)
            assertEquals(expected.lightMode, restored.lightMode)
            assertEquals(expected.nfcEnabled, restored.nfcEnabled)
            val restoredPhoto = restored.photos.single()
            assertEquals(photo.id, restoredPhoto.id)
            assertTrue(restoredPhoto.isCover)
            assertArrayEquals(photoBytes, targetPhotos.decryptedBytes(restoredPhoto.encryptedFileName, targetKey))
            val restoredCode = restored.entries.single { it.type == EntryType.AUTHENTICATOR }
            assertEquals(Totp.code(authenticator.secondaryValue, "SHA256", 8, 60, 1234567890), Totp.code(restoredCode.secondaryValue, restoredCode.totpAlgorithm, restoredCode.totpDigits, restoredCode.totpPeriod, 1234567890))
            val login = restored.entries.single { it.type == EntryType.PASSWORD }
            val linkedLogin = login.copy(tertiaryValue = "https://bank.example/login")
            target.dao().saveEntry(linkedLogin, setOf(group.id))
            val loginPhoto = VaultPhoto(entryId = login.id, encryptedFileName = "login-photo", encryptedThumbnailFileName = "login-thumb")
            targetPhotos.encrypt(photoBytes.inputStream(), loginPhoto.encryptedFileName, targetKey)
            targetPhotos.createThumbnail(loginPhoto.encryptedFileName, loginPhoto.encryptedThumbnailFileName, targetKey)
            target.dao().insertPhoto(loginPhoto)
            val beforeUpdate = target.dao().entry(login.id)!!
            target.dao().saveBrowserLogin("https://bank.example", login.primaryValue, "Confirmed browser password", beforeUpdate.entry)
            val afterUpdate = target.dao().entry(login.id)!!
            assertEquals(beforeUpdate.groups, afterUpdate.groups)
            assertEquals(beforeUpdate.photos, afterUpdate.photos)
            assertArrayEquals(photoBytes, targetPhotos.decryptedBytes(loginPhoto.encryptedFileName, targetKey))
            assertEquals(authenticator.id, afterUpdate.entry.linkedAuthenticatorId)
            assertEquals(login.notes, afterUpdate.entry.notes)
            target.dao().saveGeneratedLogin("https://bank.example", login.primaryValue, "Generated dummy password", afterUpdate.entry)
            val generated = target.dao().allEntries().single { it.entry.title == "${login.title} (generated)" }.entry
            assertEquals(afterUpdate, target.dao().entry(login.id))
            assertEquals(authenticator.id, generated.linkedAuthenticatorId)
            assertEquals(afterUpdate.groups, target.dao().entry(generated.id)!!.groups)
            val beforeInvalidGeneration = target.dao().backupSnapshot()
            assertTrue(runCatching { target.dao().saveGeneratedLogin("https://evil.example", login.primaryValue, "Bad", afterUpdate.entry) }.isFailure)
            assertTrue(runCatching { target.dao().saveGeneratedLogin("https://bank.example", "wrong-user", "Bad", afterUpdate.entry) }.isFailure)
            assertTrue(runCatching { target.dao().saveGeneratedLogin("https://bank.example", login.primaryValue, "Bad", beforeUpdate.entry) }.isFailure)
            assertEquals(beforeInvalidGeneration, target.dao().backupSnapshot())
            target.dao().deleteEntryAndLinks(login)
            assertNotNull(target.dao().entry(restoredCode.id))
            target.dao().saveEntry(login, emptySet())
            target.dao().deleteEntryAndLinks(restoredCode)
            assertEquals("", target.dao().entry(login.id)!!.entry.linkedAuthenticatorId)
            val imported = login.copy(id = UUID.randomUUID().toString(), title = "Imported", linkedAuthenticatorId = "", tertiaryValue = "https://example.com/login")
            target.dao().insertEntry(imported)
            val savedImported = target.dao().loginAndCodeEntries().single { sameImportedAccount(it, imported) }
            val browserLogin = target.dao().entry(savedImported.id)!!.entry
            target.dao().saveBrowserLogin("https://example.com", browserLogin.primaryValue, "Updated from browser", browserLogin)
            val updated = target.dao().entry(savedImported.id)!!.entry
            assertEquals("Updated from browser", updated.secondaryValue)
            assertEquals(browserLogin.notes, updated.notes)
            assertEquals(browserLogin.autofillSignatures, updated.autofillSignatures)
            assertTrue(runCatching { target.dao().saveBrowserLogin("https://evil.example", updated.primaryValue, "Bad", updated) }.isFailure)
            assertTrue(runCatching { target.dao().saveBrowserLogin("https://example.com", updated.primaryValue, "Stale", browserLogin) }.isFailure)
            assertEquals(updated, target.dao().entry(savedImported.id)!!.entry)
            val beforeDuplicate = target.dao().backupSnapshot()
            target.dao().saveBrowserLogin("https://example.com", updated.primaryValue, updated.secondaryValue, null)
            assertEquals(beforeDuplicate, target.dao().backupSnapshot())
            target.dao().saveBrowserLogin("https://new.example", "new-user", "New password", null)
            assertTrue(target.dao().loginAndCodeEntries().any { it.primaryValue == "new-user" && it.tertiaryValue == "https://new.example" })
            target.dao().saveNativeLogin("com.example.native", "certificate", "Example app", "native-user", "Native password", null)
            val native = target.dao().loginAndCodeEntries().single { it.primaryValue == "native-user" }
            assertTrue(loginAuthorized(native, "com.example.native", "certificate"))
            val beforeNativeUpdate = target.dao().entry(native.id)!!
            target.dao().saveNativeLogin("com.example.native", "certificate", "Example app", "native-user", "Updated native password", native)
            val nativeUpdated = target.dao().entry(native.id)!!
            assertEquals("Updated native password", nativeUpdated.entry.secondaryValue)
            assertEquals(beforeNativeUpdate.groups, nativeUpdated.groups)
            assertEquals(beforeNativeUpdate.photos, nativeUpdated.photos)
            assertTrue(runCatching { target.dao().saveNativeLogin("com.example.native", "wrong-certificate", "Example app", "native-user", "Bad", nativeUpdated.entry) }.isFailure)
        } finally { source.close(); target.close(); password.fill('\u0000'); sourceKey.fill(0); targetKey.fill(0); root.deleteRecursively() }
    }
}
