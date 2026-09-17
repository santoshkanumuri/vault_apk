package com.privatevault.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom

class PasswordCryptoTest {
    @Test
    fun wrappedVaultKeyRoundTrips() {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val password = "a long memorable test phrase".toCharArray()

        val wrapped = PasswordCrypto.wrap(secret, password, salt)
        val restored = PasswordCrypto.unwrap(wrapped, password, salt)

        assertArrayEquals(secret, restored)
        assertFalse(secret.contentEquals(wrapped.ciphertext))
    }

    @Test(expected = Exception::class)
    fun wrongPasswordCannotUnwrapVaultKey() {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val wrapped = PasswordCrypto.wrap(secret, "correct horse battery staple".toCharArray(), salt)

        PasswordCrypto.unwrap(wrapped, "wrong password entirely".toCharArray(), salt)
    }
}
