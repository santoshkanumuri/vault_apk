package com.privatevault.app.sync

import com.google.gson.Gson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun matchesRfc8032TestVector() {
        val fixture = loadFixture()
        assertTrue(fixture.formatVersion == DEVICE_IDENTITY_FORMAT_VERSION)
        assertTrue(fixture.algorithm == DEVICE_IDENTITY_ALGORITHM)
        val privateSeed = fixture.privateSeedHex.hexToBytes()
        val publicKey = fixture.publicKeyHex.hexToBytes()
        val message = fixture.messageHex.hexToBytes()
        val signature = fixture.signatureHex.hexToBytes()

        assertArrayEquals(publicKey, DeviceIdentityCrypto.publicKey(privateSeed))
        assertArrayEquals(signature, DeviceIdentityCrypto.sign(privateSeed, message))
        assertTrue(DeviceIdentityCrypto.verify(publicKey, message, signature))
        assertFalse(DeviceIdentityCrypto.verify(publicKey, byteArrayOf(1), signature))
    }

    private fun loadFixture(): DeviceIdentityFixture {
        val json = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("protocol/device-identity-v1.json"),
        ).bufferedReader().use { it.readText() }
        return Gson().fromJson(json, DeviceIdentityFixture::class.java)
    }
}

private data class DeviceIdentityFixture(
    val formatVersion: Int,
    val algorithm: String,
    val privateSeedHex: String,
    val publicKeyHex: String,
    val messageHex: String,
    val signatureHex: String,
)

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
