package com.privatevault.app.autofill

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Security review L-05: the device copy moves to a newer key without losing what an older version wrote. */
class ProfileCopyCipherTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val plain = """{"vaultId":"v","profiles":[]}""".toByteArray()

    @Test fun aCopyWrittenUnderTheNewKeyOpens() {
        val locked = key()
        assertArrayEquals(plain, ProfileCopyCipher.open(ProfileCopyCipher.seal(plain, locked), listOf({ locked }, { null })))
    }

    @Test fun aCopyAnOlderVersionWroteStillOpensWhenTheNewKeyAlreadyExists() {
        val legacy = key()
        val written = ProfileCopyCipher.seal(plain, legacy)
        assertArrayEquals(plain, ProfileCopyCipher.open(written, listOf({ key() }, { legacy })))
    }

    @Test fun aKeyThatCannotBeReadIsSkipped() {
        val legacy = key()
        val written = ProfileCopyCipher.seal(plain, legacy)
        assertArrayEquals(plain, ProfileCopyCipher.open(written, listOf({ error("locked phone") }, { legacy })))
    }

    @Test fun aChangedCopyOrAMissingKeyOpensNothing() {
        val locked = key()
        val written = ProfileCopyCipher.seal(plain, locked)
        written[written.size - 1] = (written.last().toInt() xor 1).toByte()
        assertTrue(runCatching { ProfileCopyCipher.open(written, listOf({ locked })) }.isFailure)
        assertTrue(runCatching { ProfileCopyCipher.open(ProfileCopyCipher.seal(plain, locked), listOf({ null }, { key() })) }.isFailure)
    }
}
