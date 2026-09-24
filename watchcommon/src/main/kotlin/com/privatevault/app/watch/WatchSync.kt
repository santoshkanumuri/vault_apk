package com.privatevault.app.watch

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.privatevault.app.security.Totp
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class WatchAccount(
    val id: String,
    val name: String,
    val account: String,
    val secret: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
)

data class WatchSnapshot(val vaultId: String, val accounts: List<WatchAccount>)

object WatchSync {
    const val SNAPSHOT_PATH = "/nuvori/watch-codes/v1"
    const val PAIR_PATH = "/nuvori/watch-pair/v1"
    const val PROBE_PATH = "/nuvori/watch-probe/v1"
    private val label = "nuvori-watch-totp-v1".toByteArray(Charsets.UTF_8)
    private val gson = Gson()

    fun keyFromVault(vaultKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(vaultKey, "HmacSHA256"))
        return mac.doFinal(label)
    }

    fun encode(snapshot: WatchSnapshot): ByteArray {
        validate(snapshot)
        return gson.toJson(snapshot).toByteArray(Charsets.UTF_8).also {
            require(it.size <= 90_000) { "Too many authenticator accounts for watch sync." }
        }
    }

    fun decode(bytes: ByteArray): WatchSnapshot {
        require(bytes.size <= 90_000)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root.keySet() == setOf("vaultId", "accounts"))
        val accounts = root.getAsJsonArray("accounts")
        require(accounts.size() <= 500)
        val parsed = gson.fromJson(root, WatchSnapshot::class.java)
        validate(parsed)
        return parsed
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(label)
        return byteArrayOf(1) + nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, payload: ByteArray): ByteArray {
        require(key.size == 32 && payload.size in 30..100_000 && payload[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
        cipher.updateAAD(label)
        return cipher.doFinal(payload, 13, payload.size - 13)
    }

    private fun validate(snapshot: WatchSnapshot) {
        require(snapshot.vaultId.length in 1..100)
        require(snapshot.accounts.size <= 500)
        require(snapshot.accounts.map { it.id }.distinct().size == snapshot.accounts.size)
        snapshot.accounts.forEach {
            require(it.id.length in 1..100 && it.name.length <= 200 && it.account.length <= 200)
            require((it.id + it.name + it.account).none(Char::isISOControl))
            Totp.validate(it.secret, it.algorithm, it.digits, it.period)
        }
    }
}
