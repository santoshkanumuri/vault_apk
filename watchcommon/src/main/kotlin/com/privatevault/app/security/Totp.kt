package com.privatevault.app.security

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import org.bouncycastle.util.encoders.Base32
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.crypto.spec.SecretKeySpec

class TotpSetup(val issuer: String, val account: String, val secret: String, val algorithm: String, val digits: Int, val period: Int) {
    override fun toString() = "TotpSetup(redacted)"
}

object Totp {
    fun normalizeSecret(value: String): String {
        val text = value.filterNot(Char::isWhitespace).uppercase(Locale.ROOT).trimEnd('=')
        require(text.length in 16..1024 && text.matches(Regex("[A-Z2-7]+"))) { "Enter a valid Base32 setup key." }
        val decoded = runCatching { Base32.decode(text.padEnd(((text.length + 7) / 8) * 8, '=')) }
            .getOrElse { throw IllegalArgumentException("Enter a valid Base32 setup key.") }
        try {
            require(Base32.toBase32String(decoded).trimEnd('=').equals(text, true)) { "Invalid setup key encoding." }
        } finally { decoded.fill(0) }
        return text
    }

    fun validate(secret: String, algorithm: String, digits: Int, period: Int) {
        normalizeSecret(secret)
        require(algorithm in listOf("SHA1", "SHA256", "SHA512")) { "Unsupported authenticator algorithm." }
        require(digits in 6..8) { "Use 6, 7, or 8 digits." }
        require(period in 1..300) { "Use an interval from 1 to 300 seconds." }
    }

    fun code(secret: String, algorithm: String, digits: Int, period: Int, epochSeconds: Long = Instant.now().epochSecond): String {
        validate(secret, algorithm, digits, period)
        val normalized = normalizeSecret(secret)
        val bytes = Base32.decode(normalized.padEnd(((normalized.length + 7) / 8) * 8, '='))
        return try {
            val hmac = "Hmac$algorithm"
            TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(period.toLong()), digits, hmac)
                .generateOneTimePasswordString(SecretKeySpec(bytes, hmac), Instant.ofEpochSecond(epochSeconds))
        } finally { bytes.fill(0) }
    }

    fun parse(value: String): TotpSetup {
        require(value.length <= 4096) { "Authenticator QR is too large." }
        // Do not expose URI/parser exception messages: they can contain the setup secret.
        return try {
            val uri = URI(value.trim())
            require(uri.scheme.equals("otpauth", true) && uri.host.equals("totp", true))
            require(uri.fragment == null && uri.userInfo == null && uri.port == -1)
            fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
            val params = linkedMapOf<String, String>()
            uri.rawQuery.orEmpty().split('&').forEach { part ->
                val pieces = part.split('=', limit = 2)
                require(pieces.size == 2)
                require(params.put(decode(pieces[0]), decode(pieces[1])) == null)
            }
            val label = decode(uri.rawPath.orEmpty().removePrefix("/").replace("+", "%2B"))
            val labelIssuer = if (':' in label) label.substringBefore(':').trim() else ""
            val account = label.substringAfter(':').trim()
            val issuer = params["issuer"]?.trim().orEmpty().ifBlank { labelIssuer }
            require(labelIssuer.isBlank() || labelIssuer == issuer)
            require(account.isNotBlank() && account.length <= 200 && issuer.length <= 200)
            require((issuer + account).none(Char::isISOControl))
            val secret = normalizeSecret(params["secret"] ?: error("Missing key"))
            val algorithm = params["algorithm"]?.uppercase(Locale.ROOT) ?: "SHA1"
            val digits = params["digits"]?.toInt() ?: 6
            val period = params["period"]?.toInt() ?: 30
            validate(secret, algorithm, digits, period)
            TotpSetup(issuer, account, secret, algorithm, digits, period)
        } catch (_: Exception) {
            throw IllegalArgumentException("Use a standard TOTP setup QR. HOTP and authenticator migration QR codes are not supported.")
        }
    }
}
