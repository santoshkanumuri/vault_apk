package com.privatevault.app.backup

import android.content.Context
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.config.TinkConfig
import com.privatevault.app.data.EntryGroupCrossRef
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.CardKind
import com.privatevault.app.data.VaultDao
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.security.EncryptedPhotoStore
import com.privatevault.app.security.PasswordCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.privatevault.app.security.Totp
import java.util.UUID

class VaultBackupManager(
    private val context: Context,
    private val dao: VaultDao,
    private val photoStore: EncryptedPhotoStore
) {
    companion object {
        private val MAGIC = "PVLT0001".toByteArray(Charsets.US_ASCII)
        private val ASSOCIATED_DATA = "private-vault-backup-v1".toByteArray()
        private const val MAX_WRAPPED_KEY = 128 * 1024
    }

    init { TinkConfig.register() }

    suspend fun export(output: OutputStream, password: CharArray, vaultKey: ByteArray) {
        require(password.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        val snapshot = dao.backupSnapshot()
        val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM_HKDF_1MB"))
        val serialized = ByteArrayOutputStream().also {
            CleartextKeysetHandle.write(keyset, BinaryKeysetWriter.withOutputStream(it))
        }.toByteArray()
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val wrappingKey = PasswordCrypto.derive(password, salt)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val wrapped = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MAGIC)
                doFinal(serialized)
            }
        } finally {
            wrappingKey.fill(0)
            serialized.fill(0)
        }

        val data = DataOutputStream(output)
        data.write(MAGIC)
        data.write(salt)
        data.write(nonce)
        data.writeInt(wrapped.size)
        data.write(wrapped)
        val encrypted = keyset.getPrimitive(StreamingAead::class.java)
            .newEncryptingStream(data, ASSOCIATED_DATA)
        ZipOutputStream(encrypted).use { zip ->
            zip.putNextEntry(ZipEntry("vault.json"))
            val metadata = serialize(snapshot)
            try { zip.write(metadata) } finally { metadata.fill(0) }
            zip.closeEntry()
            snapshot.photos.forEach { photo ->
                zip.putNextEntry(ZipEntry("photos/${photo.id}"))
                val bytes = photoStore.decryptedBytes(photo.encryptedFileName, vaultKey)
                try { zip.write(bytes) } finally { bytes.fill(0) }
                zip.closeEntry()
            }
        }
    }

    suspend fun prepareRestore(input: InputStream, password: CharArray, vaultKey: ByteArray): PreparedRestore {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(magic.contentEquals(MAGIC)) { "Unsupported backup format" }
        val salt = ByteArray(16).also(data::readFully)
        val nonce = ByteArray(12).also(data::readFully)
        val wrappedSize = data.readInt()
        require(wrappedSize in 1..MAX_WRAPPED_KEY) { "Invalid backup header" }
        val wrapped = ByteArray(wrappedSize).also(data::readFully)
        val wrappingKey = PasswordCrypto.derive(password, salt)
        val serialized = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MAGIC)
                doFinal(wrapped)
            }
        } finally { wrappingKey.fill(0) }
        val keyset = try {
            CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(serialized))
        } finally { serialized.fill(0) }

        val stagedNames = mutableListOf<String>()
        var json: ByteArray? = null
        try {
            val photoFiles = mutableMapOf<String, String>()
            val decrypted = keyset.getPrimitive(StreamingAead::class.java)
                .newDecryptingStream(data, ASSOCIATED_DATA)
            ZipInputStream(decrypted).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.name.contains("..") && !entry.name.startsWith("/")) { "Invalid backup entry" }
                    when {
                        entry.name == "vault.json" -> {
                            require(json == null) { "Duplicate vault data" }
                            val buffer = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            try {
                                while (true) {
                                    val count = zip.read(chunk)
                                    if (count < 0) break
                                    require(buffer.size() + count <= 16 * 1024 * 1024) { "Backup metadata is too large" }
                                    buffer.write(chunk, 0, count)
                                }
                                json = buffer.toByteArray()
                            } finally { chunk.fill(0); buffer.reset() }
                        }
                        entry.name.startsWith("photos/") -> {
                            val id = entry.name.substringAfter("photos/")
                            require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid photo entry" }
                            require(id !in photoFiles) { "Duplicate photo data" }
                            val name = "restore-${UUID.randomUUID()}.vaultphoto"
                            stagedNames += name
                            photoStore.encrypt(zip, name, vaultKey)
                            photoFiles[id] = name
                        }
                        else -> error("Unsupported backup entry")
                    }
                    zip.closeEntry()
                }
                // ZIP may stop at its central directory before Tink's final authenticated segment.
                val drain = ByteArray(8192)
                try { while (decrypted.read(drain) != -1) { /* authenticate through EOF */ } }
                finally { drain.fill(0) }
            }
            val parsed = parse(json ?: error("Backup has no vault data"))
            val expectedPhotoIds = parsed.photos.map { it.id }.toSet()
            require(photoFiles.keys == expectedPhotoIds) { "Backup photo data is incomplete" }

            val newPhotos = parsed.photos.map { photo ->
                val encryptedName = photoFiles.getValue(photo.id)
                val thumbnailName = "restore-${UUID.randomUUID()}.vaultthumb"
                stagedNames += thumbnailName
                photoStore.createThumbnail(encryptedName, thumbnailName, vaultKey)
                photo.copy(encryptedFileName = encryptedName, encryptedThumbnailFileName = thumbnailName)
            }
            return PreparedRestore(parsed.copy(photos = newPhotos), stagedNames.toList(), photoStore)
        } catch (failure: Exception) {
            stagedNames.forEach(photoStore::delete)
            throw failure
        } finally {
            json?.fill(0)
        }
    }

    suspend fun commitRestore(prepared: PreparedRestore) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        check(!prepared.committed && !prepared.committing && !prepared.closed) { "Restore is no longer available" }
        prepared.committing = true
        try {
            val parsed = prepared.data
            val oldPhotos = dao.backupSnapshot().photos
            dao.replaceAll(parsed.entries, parsed.groups, parsed.links, parsed.photos, com.privatevault.app.data.VaultSettings(lightMode = parsed.lightMode, nfcEnabled = parsed.nfcEnabled), parsed.passkeys)
            prepared.committed = true
            oldPhotos.forEach { photo ->
                runCatching { photoStore.delete(photo.encryptedFileName) }
                if (photo.encryptedThumbnailFileName.isNotBlank()) runCatching { photoStore.delete(photo.encryptedThumbnailFileName) }
            }
        } finally {
            prepared.committing = false
            if (!prepared.committed) prepared.close()
        }
    }

    private fun serialize(snapshot: BackupData): ByteArray {
        val root = JSONObject().put("version", 5)
            .put("lightMode", snapshot.lightMode).put("nfcEnabled", snapshot.nfcEnabled)
        root.put("entries", JSONArray().apply { snapshot.entries.forEach { put(it.toJson()) } })
        root.put("groups", JSONArray().apply { snapshot.groups.forEach { put(it.toJson()) } })
        root.put("links", JSONArray().apply { snapshot.links.forEach { put(JSONObject().put("entryId", it.entryId).put("groupId", it.groupId)) } })
        root.put("photos", JSONArray().apply { snapshot.photos.forEach { put(it.toJson()) } })
        root.put("passkeys", JSONArray().apply { snapshot.passkeys.forEach { key -> put(JSONObject()
            .put("id", key.id).put("rpId", key.rpId).put("userHandle", key.userHandle).put("username", key.username)
            .put("displayName", key.displayName).put("privateKey", key.privateKey).put("publicKey", key.publicKey).put("createdAt", key.createdAt)) } })
        return root.toString().toByteArray().also { require(it.size <= 16 * 1024 * 1024) { "Backup metadata is too large" } }
    }

    private fun parse(bytes: ByteArray): BackupData {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") in 1..5) { "Unsupported backup version" }
        fun <T> JSONArray.mapJson(block: (JSONObject) -> T) = (0 until length()).map { block(getJSONObject(it)) }
        val result = BackupData(
            root.getJSONArray("entries").mapJson { it.toEntry() },
            root.getJSONArray("groups").mapJson { VaultGroup(it.getString("id"), it.getString("name"), it.getString("notes")) },
            root.getJSONArray("links").mapJson { EntryGroupCrossRef(it.getString("entryId"), it.getString("groupId")) },
            root.getJSONArray("photos").mapJson {
                VaultPhoto(
                    id = it.getString("id"),
                    entryId = it.getString("entryId"),
                    encryptedFileName = "",
                    isCover = it.optBoolean("isCover", false),
                    addedAt = it.getLong("addedAt")
                )
            },
            lightMode = root.optBoolean("lightMode", false),
            nfcEnabled = root.optBoolean("nfcEnabled", false),
            passkeys = (if (root.getInt("version") >= 5) root.getJSONArray("passkeys") else JSONArray()).mapJson {
                com.privatevault.app.data.VaultPasskey(it.getString("id"), it.getString("rpId"), it.getString("userHandle"),
                    it.getString("username"), it.getString("displayName"), it.getString("privateKey"), it.getString("publicKey"), it.getLong("createdAt"))
            }
        )
        require(result.passkeys.map { it.id }.distinct().size == result.passkeys.size) { "Duplicate passkeys" }
        result.passkeys.forEach(com.privatevault.app.passkeys.PasskeyCrypto::validateStored)
        require(result.entries.map { it.id }.distinct().size == result.entries.size) { "Duplicate entries" }
        require(result.groups.map { it.id }.distinct().size == result.groups.size) { "Duplicate groups" }
        require(result.photos.map { it.id }.distinct().size == result.photos.size) { "Duplicate photos" }
        require(result.links.distinct().size == result.links.size) { "Duplicate links" }
        val entryIds = result.entries.map { it.id }.toSet()
        val groupIds = result.groups.map { it.id }.toSet()
        require(result.links.all { it.entryId in entryIds && it.groupId in groupIds }) { "Broken group links" }
        require(result.photos.all { it.entryId in entryIds }) { "Orphaned photos" }
        val codeIds = result.entries.filter { it.type == EntryType.AUTHENTICATOR }.map { it.id }.toSet()
        require(result.entries.all { it.linkedAuthenticatorId.isBlank() ||
            (it.type == EntryType.PASSWORD && it.linkedAuthenticatorId in codeIds) }) { "Broken authenticator links" }
        result.entries.filter { it.type == EntryType.AUTHENTICATOR }.forEach {
            Totp.validate(it.secondaryValue, it.totpAlgorithm, it.totpDigits, it.totpPeriod)
        }
        return result
    }

    private fun VaultEntry.toJson() = JSONObject()
        .put("id", id).put("type", type.name).put("title", title)
        .put("primaryValue", primaryValue).put("secondaryValue", secondaryValue)
        .put("tertiaryValue", tertiaryValue).put("fourthValue", fourthValue).put("cardKind", cardKind.name).put("network", network)
        .put("totpAlgorithm", totpAlgorithm).put("totpDigits", totpDigits).put("totpPeriod", totpPeriod)
        .put("linkedApps", linkedApps)
        .put("autofillSignatures", autofillSignatures).put("linkedAuthenticatorId", linkedAuthenticatorId)
        .put("notes", notes).put("color", color).put("tags", tags).put("favorite", favorite)
        .put("lastOpenedAt", lastOpenedAt).put("sortOrder", sortOrder)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun JSONObject.toEntry() = VaultEntry(
        id = getString("id"),
        type = EntryType.valueOf(getString("type")),
        title = getString("title"),
        primaryValue = getString("primaryValue"),
        secondaryValue = getString("secondaryValue"),
        tertiaryValue = getString("tertiaryValue"),
        fourthValue = getString("fourthValue"),
        cardKind = optString("cardKind", CardKind.CREDIT.name).let(CardKind::valueOf),
        network = optString("network", ""),
        totpAlgorithm = optString("totpAlgorithm", "SHA1"),
        totpDigits = optInt("totpDigits", 6),
        totpPeriod = optInt("totpPeriod", 30),
        linkedApps = optString("linkedApps", ""),
        autofillSignatures = optString("autofillSignatures", ""),
        linkedAuthenticatorId = optString("linkedAuthenticatorId", ""),
        notes = getString("notes"),
        color = getLong("color"),
        tags = optString("tags", ""),
        favorite = optBoolean("favorite", false),
        lastOpenedAt = optLong("lastOpenedAt", 0),
        sortOrder = optLong("sortOrder", 0),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt")
    )
    private fun VaultGroup.toJson() = JSONObject().put("id", id).put("name", name).put("notes", notes)
    private fun VaultPhoto.toJson() = JSONObject().put("id", id).put("entryId", entryId).put("isCover", isCover).put("addedAt", addedAt)
}

data class BackupData(
    val entries: List<VaultEntry>,
    val groups: List<VaultGroup>,
    val links: List<EntryGroupCrossRef>,
    val photos: List<VaultPhoto>,
    val lightMode: Boolean = false,
    val nfcEnabled: Boolean = false,
    val passkeys: List<com.privatevault.app.data.VaultPasskey> = emptyList()
)

class PreparedRestore internal constructor(val data: BackupData, private val files: List<String>, private val store: EncryptedPhotoStore) : AutoCloseable {
    internal var committed = false
    internal var committing = false
    internal var closed = false
    override fun close() { if (!committed && !committing) { closed = true; files.forEach(store::delete) } }
}
