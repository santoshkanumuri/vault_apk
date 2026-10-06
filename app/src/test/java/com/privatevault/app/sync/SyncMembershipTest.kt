package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SyncMembershipTest {
    private val managerSeed = ByteArray(32) { 1 }
    private val memberSeed = ByteArray(32) { 2 }
    private val managerKey = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(DeviceIdentityCrypto.publicKey(managerSeed))
    private val memberKey = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(DeviceIdentityCrypto.publicKey(memberSeed))

    @Test fun signedAdmissionAndTransferHaveOneOrderedHistory() {
        val genesis = SyncMembershipEvent.sign("vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
            "manager", "manager", managerKey, 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        val add = SyncMembershipEvent.sign("vault", 2, genesis.hash, MembershipAction.ADD,
            "manager", "member", memberKey, 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        val offer = SyncMembershipEvent.sign("vault", 3, add.hash, MembershipAction.OFFER_TRANSFER,
            "manager", "member", "", 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        val acceptance = SyncMembershipEvent.sign("vault", 4, offer.hash, MembershipAction.ACCEPT_TRANSFER,
            "member", "member", "", 1) { DeviceIdentityCrypto.sign(memberSeed, it) }
        val forgedAcceptance = SyncMembershipEvent.sign("vault", 4, offer.hash,
            MembershipAction.ACCEPT_TRANSFER, "member", "member", "", 1) {
            DeviceIdentityCrypto.sign(managerSeed, it)
        }
        val transfer = SyncMembershipEvent.sign("vault", 5, acceptance.hash, MembershipAction.TRANSFER,
            "manager", "member", "", 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        assertTrue(runCatching { SyncMembershipManager.verify(listOf(genesis, add, transfer)) }.isFailure)
        assertTrue(runCatching { SyncMembershipManager.verify(listOf(genesis, add, offer,
            forgedAcceptance)) }.isFailure)
        val pending = SyncMembershipManager.verify(listOf(genesis, add, offer, acceptance))
        assertEquals("manager", pending.managerDeviceId)
        assertTrue(pending.transferAccepted)
        val accepted = SyncMembershipManager.verify(listOf(genesis, add, offer, acceptance, transfer))
        assertEquals("member", accepted.managerDeviceId)
        assertEquals(null, accepted.pendingTransferDeviceId)
        assertEquals(2, accepted.members.count { it.status == MemberStatus.ACTIVE.name })
        val forged = SyncMembershipEvent.sign("vault", 6, transfer.hash, MembershipAction.ADD,
            "manager", "forged", memberKey, 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        assertTrue(runCatching { SyncMembershipManager.verify(listOf(genesis, add, offer,
            acceptance, transfer, forged)) }.isFailure)
        assertTrue(runCatching { SyncMembershipManager.verify(listOf(genesis, add.copy(subjectPublicKey = managerKey))) }.isFailure)
    }

    @Test fun eightActiveMembersIsTheLimitAndRevocationFreesOneSlot() {
        val genesis = SyncMembershipEvent.sign("vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
            "manager", "manager", managerKey, 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        val events = mutableListOf(genesis)
        repeat(MAX_ACTIVE_SYNC_DEVICES - 1) { index ->
            val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                DeviceIdentityCrypto.publicKey(ByteArray(32) { (index + 3).toByte() }))
            events += SyncMembershipEvent.sign("vault", events.size + 1L, events.last().hash,
                MembershipAction.ADD, "manager", "member-$index", publicKey, 1) {
                DeviceIdentityCrypto.sign(managerSeed, it)
            }
        }
        val extra = SyncMembershipEvent.sign("vault", events.size + 1L, events.last().hash,
            MembershipAction.ADD, "manager", "extra", memberKey, 1) {
            DeviceIdentityCrypto.sign(managerSeed, it)
        }
        assertEquals(MAX_ACTIVE_SYNC_DEVICES, SyncMembershipManager.verify(events).members.size)
        assertTrue(runCatching { SyncMembershipManager.verify(events + extra) }.isFailure)
        val removal = SyncMembershipEvent.sign("vault", events.size + 1L, events.last().hash,
            MembershipAction.REMOVE, "manager", "member-0", "", 2) {
            DeviceIdentityCrypto.sign(managerSeed, it)
        }
        val replacement = SyncMembershipEvent.sign("vault", events.size + 2L, removal.hash,
            MembershipAction.ADD, "manager", "extra", memberKey, 2) {
            DeviceIdentityCrypto.sign(managerSeed, it)
        }
        assertEquals(MAX_ACTIVE_SYNC_DEVICES, SyncMembershipManager.verify(events + removal + replacement).members
            .count { it.status == MemberStatus.ACTIVE.name })
    }

    @Test fun managerCanCancelAnUnacceptedTransfer() {
        val genesis = SyncMembershipEvent.sign("vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
            "manager", "manager", managerKey, 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        val add = SyncMembershipEvent.sign("vault", 2, genesis.hash, MembershipAction.ADD,
            "manager", "member", memberKey, 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        val offer = SyncMembershipEvent.sign("vault", 3, add.hash, MembershipAction.OFFER_TRANSFER,
            "manager", "member", "", 1) { DeviceIdentityCrypto.sign(managerSeed, it) }
        val cancelled = SyncMembershipEvent.sign("vault", 4, offer.hash,
            MembershipAction.CANCEL_TRANSFER, "manager", "member", "", 1) {
            DeviceIdentityCrypto.sign(managerSeed, it)
        }
        val accepted = SyncMembershipManager.verify(listOf(genesis, add, offer, cancelled))
        assertEquals("manager", accepted.managerDeviceId)
        assertEquals(null, accepted.pendingTransferDeviceId)
    }
}
