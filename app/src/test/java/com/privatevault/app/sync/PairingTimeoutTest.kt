package com.privatevault.app.sync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class PairingTimeoutTest {
    @Test fun confirmationExpiryIsReportedAsFailureRatherThanUserCancellation() = runBlocking {
        val failure = runCatching { awaitPairingApproval(CompletableDeferred(), 10) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse("Expired confirmation must reach the pairing failure handler", failure is CancellationException)
        assertTrue(pairingFailure(failure as Exception, "confirm").contains("confirmation", ignoreCase = true))
    }

    @Test fun userCancellationStillCancelsPairing() = runBlocking {
        val pending = CompletableDeferred<Boolean>()
        pending.cancel()
        val failure = runCatching { awaitPairingApproval(pending, 1000) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun approvedConfirmationContinues() = runBlocking {
        awaitPairingApproval(CompletableDeferred(true), 1000)
    }

    @Test fun handshakeTimeoutDoesNotClaimTheDeviceWasUnreachable() {
        val message = pairingFailure(SocketTimeoutException(), "connecting")
        assertFalse(message.contains("cannot reach"))
        assertTrue(message.contains("handshake", ignoreCase = true))
    }

    @Test fun connectionTimeoutStillReportsAnUnreachableDevice() {
        val message = pairingFailure(PairingConnectTimeout(SocketTimeoutException()), "connecting")
        assertTrue(message.contains("cannot reach"))
    }
}
