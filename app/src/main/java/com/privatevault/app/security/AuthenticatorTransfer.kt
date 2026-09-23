package com.privatevault.app.security

import com.google.protobuf.CodedInputStream
import com.google.gson.JsonParser
import org.bouncycastle.util.encoders.Base32
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class AuthenticatorTransferPage(
    val accounts: List<TotpSetup>,
    val batchId: Int = 0,
    val batchSize: Int = 1,
    val batchIndex: Int = 0
)

internal fun parseAuthenticatorTransferText(value: String): AuthenticatorTransferPage {
    require(value.length <= 1_048_576)
    val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    require(lines.size in 1..100)
    return AuthenticatorTransferPage(lines.map(Totp::parse))
}

internal fun isEncryptedOneAuthExport(value: String): Boolean = runCatching {
    JsonParser.parseString(value).asJsonObject.get("format")?.asString == "oneauth_04"
}.getOrDefault(false)

internal fun parseEncryptedOneAuthExport(value: String, password: CharArray): AuthenticatorTransferPage {
    require(value.length <= 1_048_576)
    val salt: ByteArray
    val data: ByteArray
    try {
        val envelope = JsonParser.parseString(value).asJsonObject
        require(envelope.get("format")?.asString == "oneauth_04")
        require(envelope.get("nonce")?.asLong?.let { it >= 0 } == true)
        salt = Base64.getDecoder().decode(envelope.get("enc_salt").asString)
        data = Base64.getDecoder().decode(envelope.get("data").asString)
        require(salt.size == 12 && data.size in 29..1_048_576)
    } catch (_: Exception) {
        throw IllegalArgumentException("Unsupported OneAuth export file.")
    }
    val spec = PBEKeySpec(password, salt, 100_000, 256)
    var key = ByteArray(0)
    var plain = ByteArray(0)
    return try {
        key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            doFinal(data, 12, data.size - 12)
        }
        val root = JsonParser.parseString(plain.toString(Charsets.UTF_8)).asJsonObject
        val accounts = mutableListOf<TotpSetup>()
        root.getAsJsonArray("groups").forEach { group ->
            group.asJsonObject.getAsJsonArray("tpa_secrets").forEach { item ->
                require(accounts.size < 100)
                val record = item.asJsonObject
                require(record.get("type").asString.equals("totp", true))
                val issuer = record.get("issuer").asString.trim()
                val label = record.get("label").asString.trim()
                require(label.isNotBlank() && label.length <= 200 && issuer.length <= 200 && (issuer + label).none(Char::isISOControl))
                val secret = Totp.normalizeSecret(record.get("secret").asString)
                val algorithm = record.get("algorithm").asString.uppercase(Locale.ROOT)
                val digits = record.get("digits").asInt
                val period = record.get("period").asInt
                Totp.validate(secret, algorithm, digits, period)
                accounts += TotpSetup(issuer, label, secret, algorithm, digits, period)
            }
        }
        require(accounts.isNotEmpty())
        AuthenticatorTransferPage(accounts)
    } catch (_: Exception) {
        throw IllegalArgumentException("Incorrect export password or unsupported OneAuth file.")
    } finally {
        spec.clearPassword()
        salt.fill(0)
        data.fill(0)
        key.fill(0)
        plain.fill(0)
    }
}

internal fun parseAuthenticatorTransferQr(value: String): AuthenticatorTransferPage {
    require(value.length <= 16_384) { "Transfer QR is too large." }
    if (value.startsWith("otpauth://", true)) return AuthenticatorTransferPage(listOf(Totp.parse(value)))
    return try {
        val uri = URI(value.trim())
        require(uri.scheme == "otpauth-migration" && uri.host == "offline" && uri.fragment == null)
        val params = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
        require(params.size == 1 && params[0].size == 2 && params[0][0] == "data")
        val encoded = URLDecoder.decode(params[0][1].replace("+", "%2B"), "UTF-8")
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            require(bytes.size <= 16_384)
            val input = CodedInputStream.newInstance(bytes)
            val accounts = mutableListOf<TotpSetup>()
            var version = 0
            var batchSize = 1
            var batchIndex = 0
            var batchId = 0
            while (true) {
                val tag = input.readTag()
                if (tag == 0) break
                when (tag) {
                    10 -> { require(accounts.size < 100); accounts += parseTransferAccount(input.readBytes().toByteArray()) }
                    16 -> version = input.readInt32()
                    24 -> batchSize = input.readInt32()
                    32 -> batchIndex = input.readInt32()
                    40 -> batchId = input.readInt32()
                    else -> require(input.skipField(tag))
                }
            }
            require(version == 1 && accounts.isNotEmpty() && batchSize in 1..100 && batchIndex in 0 until batchSize)
            return AuthenticatorTransferPage(accounts, batchId, batchSize, batchIndex)
        } finally { bytes.fill(0) }
    } catch (_: Exception) {
        throw IllegalArgumentException("Use a supported Google Authenticator transfer QR or TOTP setup QR.")
    }
}

private fun parseTransferAccount(bytes: ByteArray): TotpSetup {
    var secret = ByteArray(0)
    return try {
    val input = CodedInputStream.newInstance(bytes)
    var name = ""
    var issuer = ""
    var algorithm = 1
    var digits = 1
    var type = 0
    while (true) {
        val tag = input.readTag()
        if (tag == 0) break
        when (tag) {
            10 -> { secret.fill(0); secret = input.readBytes().toByteArray() }
            18 -> name = input.readStringRequireUtf8()
            26 -> issuer = input.readStringRequireUtf8()
            32 -> algorithm = input.readEnum()
            40 -> digits = input.readEnum()
            48 -> type = input.readEnum()
            else -> require(input.skipField(tag))
        }
    }
    require(type == 2 && secret.size in 10..640 && name.isNotBlank() && name.length <= 200 && issuer.length <= 200)
    require((name + issuer).none(Char::isISOControl))
    val account = if (issuer.isNotBlank() && name.startsWith("$issuer:")) name.substringAfter(':').trim() else name.trim()
    require(account.isNotBlank())
    val base32 = Base32.toBase32String(secret).trimEnd('=')
    val hash = when (algorithm) { 0, 1 -> "SHA1"; 2 -> "SHA256"; 3 -> "SHA512"; else -> error("Unsupported algorithm") }
    val length = when (digits) { 0, 1 -> 6; 2 -> 8; else -> error("Unsupported digits") }
    Totp.validate(base32, hash, length, 30)
    TotpSetup(issuer, account, base32, hash, length, 30)
    } finally { secret.fill(0); bytes.fill(0) }
}
