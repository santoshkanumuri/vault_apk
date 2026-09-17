package com.privatevault.app.security

import org.bouncycastle.util.encoders.Base32
import org.junit.Assert.*
import org.junit.Test

class TotpTest {
    @Test fun matchesRfc6238VectorsForAllAlgorithms() {
        val times = listOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)
        val vectors = mapOf(
            "SHA1" to listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130"),
            "SHA256" to listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706"),
            "SHA512" to listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826")
        )
        vectors.forEach { (algorithm, expected) ->
            val length = when (algorithm) { "SHA1" -> 20; "SHA256" -> 32; else -> 64 }
            val secret = Base32.toBase32String("1234567890".repeat(7).take(length).toByteArray())
            times.forEachIndexed { i, time -> assertEquals(expected[i], Totp.code(secret, algorithm, 8, 30, time)) }
        }
    }

    @Test fun importsQrSettingsAndRejectsAmbiguousOrUnsupportedInputs() {
        val uri = "otpauth://totp/Example:user%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&algorithm=SHA256&digits=8&period=60"
        val setup = Totp.parse(uri)
        assertEquals("Example", setup.issuer)
        assertEquals("user@example.com", setup.account)
        assertEquals("SHA256", setup.algorithm)
        assertEquals(8, setup.digits)
        assertEquals(60, setup.period)
        for (bad in listOf(uri.replace("totp/", "hotp/"), uri + "&secret=JBSWY3DPEHPK3PXP", uri.replace("issuer=Example", "issuer=Other"), uri.replace("digits=8", "digits=9"))) {
            val failure = assertThrows(IllegalArgumentException::class.java) { Totp.parse(bad) }
            assertFalse(failure.message.orEmpty().contains("JBSWY"))
        }
        assertEquals("TotpSetup(redacted)", setup.toString())
    }

    @Test fun clockIntervalAndLeadingZerosArePreserved() {
        val secret = Base32.toBase32String("12345678901234567890".toByteArray())
        assertEquals("07081804", Totp.code(secret, "SHA1", 8, 30, 1111111109L))
        assertEquals(Totp.code(secret, "SHA1", 6, 30, 30), Totp.code(secret, "SHA1", 6, 30, 59))
        assertEquals(Totp.code(secret, "SHA1", 6, 30, 30), Totp.code(secret, "SHA1", 6, 60, 60))
    }
}
