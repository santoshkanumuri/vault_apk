package com.privatevault.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCryptoTest {
    @Test fun sameCodeAndTranscriptEstablishSameSessionKey() {
        val (creator, joiner, transcript) = peers("123456789012345678901234", "123456789012345678901234")
        val keys = exchange(creator, joiner, transcript)
        assertArrayEquals(keys.first, keys.second)
        assertTrue(keys.first.size == 32)
    }

    @Test fun wrongCodeCannotCompleteKeyConfirmation() {
        val (creator, joiner, transcript) = peers("123456789012345678901234", "999956789012345678901234")
        assertTrue(runCatching { exchange(creator, joiner, transcript) }.isFailure)
    }

    @Test fun matchingMasterPasswordsProveEqualityWithoutSendingThePassword() {
        val transcript = PairingTranscript("session-master", "vault-a", "device-a", "public-a",
            "device-b", "public-b")
        val first = PairingCrypto.masterPassword("device-a", "shared master phrase".toCharArray())
        val second = PairingCrypto.masterPassword("device-b", "shared master phrase".toCharArray())
        val keys = exchange(first, second, transcript)
        assertArrayEquals(keys.first, keys.second)
        keys.first.fill(0); keys.second.fill(0)

        val wrong = PairingCrypto.masterPassword("device-b", "different master phrase".toCharArray())
        val host = PairingCrypto.masterPassword("device-a", "shared master phrase".toCharArray())
        assertTrue(runCatching { exchange(host, wrong, transcript) }.isFailure)
    }

    private fun peers(leftCode: String, rightCode: String) = Triple(
        PairingCrypto("device-a", leftCode.toCharArray()), PairingCrypto("device-b", rightCode.toCharArray()),
        PairingTranscript("session-a", "vault-a", "device-a", "public-a", "device-b", "public-b"),
    )

    private fun exchange(a: PairingCrypto, b: PairingCrypto, transcript: PairingTranscript): Pair<ByteArray, ByteArray> {
        val a1 = a.round1()
        val b1 = b.round1()
        a.acceptRound1(b1)
        b.acceptRound1(a1)
        val a2 = a.round2()
        val b2 = b.round2()
        a.acceptRound2(b2)
        b.acceptRound2(a2)
        val a3 = a.round3()
        val b3 = b.round3()
        return a.finish(b3, transcript) to b.finish(a3, transcript)
    }
}
