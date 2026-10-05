package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.privatevault.app.sync.MembershipVectors as V

class MembershipNoticesTest {
    private val signManager = { bytes: ByteArray -> DeviceIdentityCrypto.sign(V.managerSeed, bytes) }
    private val now = V.CREATED_AT + 60_000
    private fun entities(events: List<SyncMembershipEvent>) = events.map { SyncMembershipEventEntity.from(it) }

    private fun handle(frame: MembershipWire.LeaveFrame = V.leaveFrame(), history: List<SyncMembershipEvent> = V.clientHistory(),
        server: String = V.MANAGER_ID, at: Long = now, challenge: ByteArray = V.challenge,
        epochs: Set<Long> = setOf(1L)) =
        LeaveRequestHandler.handle(frame, challenge, V.rootSecret, history, epochs, server, at, signManager)

    // Removal notice (client side).

    @Test fun removedDeviceLearnsFromASignedHistoryThatExtendsItsOwn() {
        val notice = RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.CLIENT_ID, entities(V.removedHistory()))
        notice as RemovalNotice.Removed
        assertEquals(V.MANAGER_ID, notice.issuerDeviceId)
        assertEquals(4L, notice.removeSequence)
    }

    @Test fun anotherMemberCatchesUpInsteadOfLeaving() {
        val notice = RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.OTHER_ID, entities(V.removedHistory()))
        assertTrue(notice is RemovalNotice.CatchUp)
    }

    @Test fun truncatedOrEqualHistoryIsRejected() {
        val removed = entities(V.removedHistory())
        assertThrows(IllegalArgumentException::class.java) {
            RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.CLIENT_ID, removed.take(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.CLIENT_ID, removed.take(2))
        }
    }

    @Test fun forgedRemovalSignedByANonManagerIsRejected() {
        val history = V.clientHistory()
        val forged = SyncMembershipEvent.sign(V.VAULT_ID, 4, history.last().hash, MembershipAction.REMOVE,
            V.MANAGER_ID, V.CLIENT_ID, "", 2) { DeviceIdentityCrypto.sign(V.otherSeed, it) }
        assertThrows(IllegalArgumentException::class.java) {
            RemovalNoticeVerifier.verify(entities(history), V.CLIENT_ID, entities(history + forged))
        }
    }

    @Test fun historyFromADifferentGroupIsRejectedEvenWhenItIsValidlySigned() {
        // An attacker's own group that removes a device with the same ID.
        val attackerSeed = ByteArray(32) { 9 }
        val sign = { bytes: ByteArray -> DeviceIdentityCrypto.sign(attackerSeed, bytes) }
        val genesis = SyncMembershipEvent.sign(V.VAULT_ID, 1, GENESIS_HASH, MembershipAction.GENESIS, "evil", "evil",
            V.publicKey(attackerSeed), 1, sign)
        val add = SyncMembershipEvent.sign(V.VAULT_ID, 2, genesis.hash, MembershipAction.ADD, "evil", V.CLIENT_ID,
            V.publicKey(V.clientSeed), 1, sign)
        val other = SyncMembershipEvent.sign(V.VAULT_ID, 3, add.hash, MembershipAction.ADD, "evil", "x",
            V.publicKey(V.otherSeed), 1, sign)
        val remove = SyncMembershipEvent.sign(V.VAULT_ID, 4, other.hash, MembershipAction.REMOVE, "evil", V.CLIENT_ID,
            "", 2, sign)
        assertThrows(IllegalArgumentException::class.java) {
            RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.CLIENT_ID, entities(listOf(genesis, add, other, remove)))
        }
    }

    @Test fun tamperedEventIsRejected() {
        val removed = entities(V.removedHistory())
        val tampered = removed.dropLast(1) + removed.last().copy(subjectDeviceId = V.OTHER_ID)
        assertThrows(IllegalArgumentException::class.java) {
            RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.OTHER_ID, tampered)
        }
    }

    @Test fun removalNoticeHistoryStopsAtTheRotationAndLaggingDevicesStepThroughEpochs() {
        val removedClient = V.removedHistory()
        // Later: the manager adds a new device, then removes the other member.
        val newcomer = SyncMembershipEvent.sign(V.VAULT_ID, 5, removedClient.last().hash, MembershipAction.ADD,
            V.MANAGER_ID, "newcomer", V.publicKey(ByteArray(32) { 8 }), 2, signManager)
        val removeOther = SyncMembershipEvent.sign(V.VAULT_ID, 6, newcomer.hash, MembershipAction.REMOVE,
            V.MANAGER_ID, V.OTHER_ID, "", 3, signManager)
        val full = entities(removedClient + newcomer + removeOther)
        // The removed client proves epoch 1 and sees its REMOVE but not the newcomer.
        val forClient = MembershipWire.historyForEpoch(full, 1)
        assertEquals(4, forClient.size)
        assertTrue(RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.CLIENT_ID, forClient) is RemovalNotice.Removed)
        // The other device, still on epoch 1, catches up to epoch 2, then learns its own removal.
        val step1 = RemovalNoticeVerifier.verify(entities(V.clientHistory()), V.OTHER_ID, forClient)
        assertTrue(step1 is RemovalNotice.CatchUp)
        val step2 = RemovalNoticeVerifier.verify(forClient, V.OTHER_ID, MembershipWire.historyForEpoch(full, 2))
        assertTrue(step2 is RemovalNotice.Removed)
        assertEquals(full, MembershipWire.historyForEpoch(full, 3))
    }

    // Leave request (server side).

    @Test fun managerAutoRemovesTheDeviceOnAValidLeaveRequest() {
        val decision = handle()
        decision as LeaveDecision.Left
        assertEquals(1, decision.append.size)
        val remove = decision.append.single()
        assertEquals(MembershipAction.REMOVE, remove.action)
        assertEquals(V.CLIENT_ID, remove.subjectDeviceId)
        assertEquals(2L, remove.keyEpoch)
        val verified = SyncMembershipManager.verify(V.clientHistory() + decision.append)
        assertEquals(MemberStatus.REVOKED.name, verified.members.first { it.deviceId == V.CLIENT_ID }.status)
    }

    @Test fun aNonManagerAnswersNotManager() {
        assertEquals(LeaveDecision.NotManager, handle(server = V.OTHER_ID))
    }

    @Test fun replayedFrameFromAnotherSessionIsRejected() {
        val otherChallenge = ByteArray(32) { 42 }
        assertEquals(LeaveDecision.Reject, handle(challenge = otherChallenge))
    }

    @Test fun expiredOrFutureRequestsAreRejected() {
        assertEquals(LeaveDecision.Reject, handle(at = V.CREATED_AT + LeaveRequestHandler.MAX_AGE_MILLIS + 1))
        assertEquals(LeaveDecision.Reject, handle(at = V.CREATED_AT - LeaveRequestHandler.MAX_CLOCK_SKEW_MILLIS - 1))
    }

    @Test fun badSignatureWrongVaultOrUnknownHeadAreRejected() {
        val frame = V.leaveFrame()
        assertEquals(LeaveDecision.Reject, handle(frame.copy(signature = DeviceIdentityCrypto.sign(V.otherSeed,
            frame.request.signingBytes()))))
        val wrongVault = frame.request.copy(vaultId = "other-vault")
        assertEquals(LeaveDecision.Reject, handle(frame.copy(request = wrongVault,
            signature = DeviceIdentityCrypto.sign(V.clientSeed, wrongVault.signingBytes()))))
        val unknownHead = frame.request.copy(membershipHead = "f".repeat(64))
        assertEquals(LeaveDecision.Reject, handle(frame.copy(request = unknownHead,
            signature = DeviceIdentityCrypto.sign(V.clientSeed, unknownHead.signingBytes()))))
        assertEquals(LeaveDecision.Reject, handle(epochs = setOf(2L)))
    }

    @Test fun repeatedRequestAfterRemovalIsIdempotent() {
        val removed = V.removedHistory()
        // The device proves the retained epoch-1 secret; nothing new is signed.
        assertEquals(LeaveDecision.Left(emptyList()), handle(history = removed, epochs = setOf(2L, 1L)))
    }

    @Test fun managerCannotLeaveThisWay() {
        val history = V.clientHistory()
        val request = LeaveRequest(V.VAULT_ID, V.MANAGER_ID, history.last().hash, 1, V.CREATED_AT)
        val frame = MembershipWire.LeaveFrame(TransportEpochs.probeProof(V.rootSecret, V.challenge), request,
            DeviceIdentityCrypto.sign(V.managerSeed, request.signingBytes()))
        assertEquals(LeaveDecision.Reject, handle(frame))
    }

    @Test fun pendingTransferToTheLeavingDeviceIsCancelledFirstAndOtherTransfersAnswerBusy() {
        val history = V.clientHistory()
        val offerToClient = SyncMembershipEvent.sign(V.VAULT_ID, 4, history.last().hash,
            MembershipAction.OFFER_TRANSFER, V.MANAGER_ID, V.CLIENT_ID, "", 1, signManager)
        val left = handle(history = history + offerToClient) as LeaveDecision.Left
        assertEquals(listOf(MembershipAction.CANCEL_TRANSFER, MembershipAction.REMOVE), left.append.map { it.action })
        val offerToOther = SyncMembershipEvent.sign(V.VAULT_ID, 4, history.last().hash,
            MembershipAction.OFFER_TRANSFER, V.MANAGER_ID, V.OTHER_ID, "", 1, signManager)
        assertEquals(LeaveDecision.Busy, handle(history = history + offerToOther))
    }

    @Test fun malformedLeaveFramesAreRejectedAndOrdinaryProofsPassThrough() {
        assertNull(MembershipWire.parseLeaveFrame("japWRQooSB-haVwaC3ueqW5yzohT_ZEC2hU9o5BIwZg".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { MembershipWire.parseLeaveFrame("leave1.abc".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            MembershipWire.parseLeaveFrame("leave1.a.b.c.d".toByteArray())
        }
        val frame = V.leaveFrame()
        val text = MembershipWire.leaveFrame(frame.proof, frame.request, frame.signature)
        assertTrue(text.length < MembershipWire.CONTROL_FRAME)
        assertEquals(frame.request, MembershipWire.parseLeaveFrame(text.toByteArray())?.request)
    }

    // Epoch secrets and retention.

    @Test fun epochOneKeepsTodaysSecretAndLaterEpochsDiffer() {
        assertEquals(V.rootSecret, TransportEpochs.secret(V.rootSecret, V.VAULT_ID, 1))
        val second = TransportEpochs.secret(V.rootSecret, V.VAULT_ID, 2)
        assertTrue(second != V.rootSecret && second != TransportEpochs.secret(V.rootSecret, V.VAULT_ID, 3))
        assertTrue(second != TransportEpochs.secret(V.rootSecret, "other-vault", 2))
    }

    @Test fun onlyTheLastFourEpochsWithinNinetyDaysAreRetained() {
        val day = 24L * 60 * 60 * 1000
        val retired = mapOf(1L to 0L, 2L to 0L, 3L to 100 * day, 4L to 100 * day, 5L to 100 * day)
        assertEquals(listOf(5L, 4L, 3L), TransportEpochs.retained(6, retired, 150 * day))
        assertEquals(emptyList<Long>(), TransportEpochs.retained(1, emptyMap(), 0))
        assertEquals(listOf(1L), TransportEpochs.retained(2, emptyMap(), 0))
    }

    @Test fun removalNoticesAreRateLimited() {
        val limiter = NoticeRateLimiter(limit = 10, windowMillis = 60_000)
        assertTrue((1..10).all { limiter.allow(1_000) })
        assertTrue(!limiter.allow(2_000))
        assertTrue(limiter.allow(61_001))
    }
}
