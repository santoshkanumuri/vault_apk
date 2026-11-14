package com.privatevault.app.watch

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the phone holds for its watch. Only [Paired] carries a key; the caller wipes it after use. */
sealed interface WatchPairing {
    /** A random key sent by the last Connect or Sync codes now. */
    class Paired(val key: ByteArray) : WatchPairing
    /** Paired before random keys existed, or never paired: the watch may hold the key derived from the vault key. */
    data object Legacy : WatchPairing
    /** Codes were removed from the watch. Nothing is sent until the watch is connected again. */
    data object Removed : WatchPairing
}

/** Wraps the 32-byte watch key under a key derived from the vault key, bound to the vault id. */
object WatchKeyWrap {
    private const val VERSION: Byte = 1
    private const val SIZE = 2 + 12 + 32 + 16
    private val label = "nuvori-watch-keywrap-v1".toByteArray(Charsets.UTF_8)

    fun wrap(vaultId: String, vaultKey: ByteArray, watchKey: ByteArray): ByteArray {
        require(watchKey.size == 32 && vaultId.length in 1..100)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = byteArrayOf(VERSION, WatchPairingStore.PAIRED)
        return header + nonce + cipher(Cipher.ENCRYPT_MODE, vaultId, vaultKey, header, nonce).doFinal(watchKey)
    }

    fun unwrap(vaultId: String, vaultKey: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size == SIZE && blob[0] == VERSION && blob[1] == WatchPairingStore.PAIRED && vaultId.length in 1..100)
        return cipher(Cipher.DECRYPT_MODE, vaultId, vaultKey, blob.copyOfRange(0, 2), blob.copyOfRange(2, 14))
            .doFinal(blob, 14, blob.size - 14).also { check(it.size == 32) }
    }

    private fun cipher(mode: Int, vaultId: String, vaultKey: ByteArray, header: ByteArray, nonce: ByteArray): Cipher {
        require(vaultKey.size >= 32)
        val wrapKey = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(vaultKey, "HmacSHA256")); doFinal(label) }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header + vaultId.toByteArray(Charsets.UTF_8))
            }
        } finally { wrapKey.fill(0) }
    }
}

/**
 * Phone-side record of the watch pairing. Keep [file] in noBackupFilesDir: it never enters backups or
 * device transfer, and the key inside is unreadable without the unlocked vault key.
 * No file means [WatchPairing.Legacy]; a two-byte marker means [WatchPairing.Removed].
 */
class WatchPairingStore(private val file: File) {
    enum class State { NONE, PAIRED, REMOVED }

    /**
     * Reads only the record type. Needs no vault key, so the settings page can show the legacy hint.
     * Any record other than the removal marker counts as [State.PAIRED]; [load] reports a damaged one.
     */
    @Synchronized fun state(): State {
        val bytes = runCatching { read() }.getOrElse { return State.PAIRED } ?: return State.NONE
        try { return if (bytes.contentEquals(byteArrayOf(VERSION, REMOVED))) State.REMOVED else State.PAIRED }
        finally { bytes.fill(0) }
    }

    @Synchronized fun load(vaultId: String, vaultKey: ByteArray): WatchPairing {
        val bytes = read() ?: return WatchPairing.Legacy
        try {
            if (bytes.contentEquals(byteArrayOf(VERSION, REMOVED))) return WatchPairing.Removed
            // Never fall back to the legacy key here: that would send codes to a key this phone replaced.
            val key = runCatching { WatchKeyWrap.unwrap(vaultId, vaultKey, bytes) }.getOrNull()
            return WatchPairing.Paired(requireNotNull(key) { UNREADABLE })
        } finally { bytes.fill(0) }
    }

    @Synchronized fun savePaired(vaultId: String, vaultKey: ByteArray, watchKey: ByteArray) {
        val blob = WatchKeyWrap.wrap(vaultId, vaultKey, watchKey)
        try { write(blob) } finally { blob.fill(0) }
    }

    /** Replaces the wrapped key with the removal marker, so no later snapshot can be made for that key. */
    @Synchronized fun markRemoved() = write(byteArrayOf(VERSION, REMOVED))

    private fun read(): ByteArray? = if (!file.isFile) null else file.readBytes().also {
        require(it.size <= 1024) { UNREADABLE }
    }

    private fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".new")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    companion object {
        internal const val VERSION: Byte = 1
        internal const val PAIRED: Byte = 1
        internal const val REMOVED: Byte = 2
        private const val UNREADABLE = "The watch key on this phone could not be read. Tap Sync codes now with the watch connected."
    }
}
