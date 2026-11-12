package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.privatevault.app.sync.MembershipVectors as V

class RemovalCutoffTest {
    private val hash = "ab".repeat(32)
    private val sign = { bytes: ByteArray -> DeviceIdentityCrypto.sign(V.managerSeed, bytes) }

    private fun removal(field: String): List<SyncMembershipEvent> {
        val history = V.clientHistory()
        return history + SyncMembershipEvent.sign(V.VAULT_ID, history.size + 1L, history.last().hash,
            MembershipAction.REMOVE, V.MANAGER_ID, V.CLIENT_ID, field, 2, sign)
    }

    @Test fun aCutoffRoundTripsAndOnlyItsOwnFormatIsAccepted() {
        val head = SyncChainHead(12, hash)
        assertEquals(head, RemovalCutoff.parse(RemovalCutoff.encode(head)))
        assertNull(RemovalCutoff.parse(""))
        for (bad in listOf("cutoff1:0:$hash", "cutoff1:012:$hash", "cutoff1:12:${"AB".repeat(32)}", "cutoff2:12:$hash",
            "cutoff1:12", "a-public-key")) {
            assertFalse(bad, RemovalCutoff.isValidField(bad))
        }
        assertThrows(IllegalArgumentException::class.java) { RemovalCutoff.encode(SyncChainHead(0, GENESIS_HASH)) }
    }

    @Test fun aSignedRemovalMayCarryACutoffButNothingElse() {
        val cutoff = RemovalCutoff.encode(SyncChainHead(3, hash))
        val verified = SyncMembershipManager.verify(removal(cutoff))
        assertEquals(MemberStatus.REVOKED.name, verified.members.single { it.deviceId == V.CLIENT_ID }.status)
        assertEquals(mapOf(V.CLIENT_ID to SyncChainHead(3, hash)), RemovalCutoff.cutoffs(verified.events))
        assertTrue(RemovalCutoff.cutoffs(SyncMembershipManager.verify(removal("")).events).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { SyncMembershipManager.verify(removal("not-a-cutoff")) }
    }

    @Test fun changesUpToTheCutoffAreAcceptedAndNothingAfter() {
        val cutoff = SyncChainHead(3, hash)
        assertTrue(RemovalCutoff.allows(cutoff, 1, "00".repeat(32)))
        assertTrue(RemovalCutoff.allows(cutoff, 3, hash))
        assertFalse("the change at the cutoff must be the one the manager held", RemovalCutoff.allows(cutoff, 3, "cd".repeat(32)))
        assertFalse(RemovalCutoff.allows(cutoff, 4, "00".repeat(32)))
        assertFalse(RemovalCutoff.allows(null, 1, hash))
    }

    @Test fun aLeaveAnsweredByTheManagerCarriesTheCutoffItIsGiven() {
        val cutoff = RemovalCutoff.encode(SyncChainHead(2, hash))
        val decision = LeaveRequestHandler.handle(V.leaveFrame(), V.challenge, V.rootSecret, V.clientHistory(), setOf(1L),
            V.MANAGER_ID, V.CREATED_AT + 60_000, sign) { subject -> if (subject == V.CLIENT_ID) cutoff else "" }
        decision as LeaveDecision.Left
        assertEquals(cutoff, decision.append.last().subjectPublicKey)
        SyncMembershipManager.verify(V.clientHistory() + decision.append)
    }
}
