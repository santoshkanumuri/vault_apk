package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncMembershipEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Leaving, removal and role-transfer choices in device lifecycle situations (lost, replaced, reset). */
class MembershipLifecycleTest {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val managerSeed = ByteArray(32) { 1 }
    private val phoneSeed = ByteArray(32) { 2 }
    private val pcSeed = ByteArray(32) { 3 }
    private val managerKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(managerSeed))
    private val phoneKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(phoneSeed))
    private val pcKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(pcSeed))
    private val signManager: (ByteArray) -> ByteArray = { DeviceIdentityCrypto.sign(managerSeed, it) }

    private val genesis = SyncMembershipEvent.sign("vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
        "manager", "manager", managerKey, 1, signManager)
    private val addPhone = SyncMembershipEvent.sign("vault", 2, genesis.hash, MembershipAction.ADD,
        "manager", "phone", phoneKey, 1, signManager)
    private val addPc = SyncMembershipEvent.sign("vault", 3, addPhone.hash, MembershipAction.ADD,
        "manager", "pc", pcKey, 1, signManager)
    private val removePhone = SyncMembershipEvent.sign("vault", 4, addPc.hash, MembershipAction.REMOVE,
        "manager", "phone", "", 2, signManager)

    private fun entities(vararg events: SyncMembershipEvent) = events.map { SyncMembershipEventEntity.from(it) }

    @Test fun onlyMembersSendALeaveRequestTheManagerJustStartsItsOwnGroup() {
        val history = listOf(genesis, addPhone, addPc)
        // The manager used to fail here ("The managing device cannot leave"), so its
        // "Start a separate group on this device instead" button never worked.
        assertFalse(MembershipChoices.leaveSendsRequest(history, "manager"))
        assertTrue(MembershipChoices.leaveSendsRequest(history, "phone"))
        assertTrue(MembershipChoices.leaveSendsRequest(history, "pc"))
        assertTrue(runCatching { MembershipChoices.leaveSendsRequest(history, "stranger") }.isFailure)
        // A removed device has nothing left to leave: its detach runs from the removal notice instead.
        assertTrue(runCatching { MembershipChoices.leaveSendsRequest(history + removePhone, "phone") }.isFailure)
    }

    @Test fun aWindowsPcIsNeverOfferedTheManagingRole() {
        val members = SyncMembershipManager.verify(listOf(genesis, addPhone, addPc)).members
            .filter { it.deviceId != "manager" }
        assertEquals(listOf("phone"), MembershipChoices.managingRoleCandidates(members, setOf("pc")).map { it.deviceId })
        // Without platform knowledge every active member stays a candidate, as before.
        assertEquals(listOf("phone", "pc"), MembershipChoices.managingRoleCandidates(members, emptySet()).map { it.deviceId })
        val removed = SyncMembershipManager.verify(listOf(genesis, addPhone, addPc, removePhone)).members
            .filter { it.deviceId != "manager" }
        assertEquals(emptyList<SyncMembershipEntity>(),
            MembershipChoices.managingRoleCandidates(removed, setOf("pc")))
    }

    @Test fun aMirrorOlderThanTheVaultCannotMakeARemovedDeviceActiveAgain() {
        val stored = entities(genesis, addPhone, addPc, removePhone)
        val staleMirror = entities(genesis, addPhone, addPc)
        val projected = newerMembership(stored, staleMirror)
        assertEquals(MemberStatus.REVOKED.name, projected.members.single { it.deviceId == "phone" }.status)
        assertEquals(removePhone.hash, projected.head)
        // A newer mirror still wins, as before.
        val fromMirror = newerMembership(staleMirror, stored)
        assertEquals(MemberStatus.REVOKED.name, fromMirror.members.single { it.deviceId == "phone" }.status)
        // Histories that disagree are refused.
        val otherAdd = SyncMembershipEvent.sign("vault", 3, addPhone.hash, MembershipAction.ADD,
            "manager", "tablet", encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { 9 })), 1, signManager)
        assertTrue(runCatching { newerMembership(stored, entities(genesis, addPhone, otherAdd)) }.isFailure)
    }

    @Test fun aRemovedDeviceCanRejoinOnlyWithANewIdentity() {
        val removed = listOf(genesis, addPhone, addPc, removePhone)
        val sameId = SyncMembershipEvent.sign("vault", 5, removePhone.hash, MembershipAction.ADD,
            "manager", "phone", phoneKey, 2, signManager)
        val sameKey = SyncMembershipEvent.sign("vault", 5, removePhone.hash, MembershipAction.ADD,
            "manager", "phone-2", phoneKey, 2, signManager)
        val fresh = SyncMembershipEvent.sign("vault", 5, removePhone.hash, MembershipAction.ADD,
            "manager", "phone-2", encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { 8 })), 2, signManager)
        assertTrue(runCatching { SyncMembershipManager.verify(removed + sameId) }.isFailure)
        assertTrue(runCatching { SyncMembershipManager.verify(removed + sameKey) }.isFailure)
        assertEquals(3, SyncMembershipManager.verify(removed + fresh).members.count { it.status == MemberStatus.ACTIVE.name })
    }

    @Test fun aLeaveRequestSignedBeforeTheIdentityChangeStillRemovesTheOldEntry() {
        // The phone signs its leave with its old identity, then switches to a new one and pairs
        // again as "phone-2". The manager still accepts the old request and removes only "phone".
        val history = listOf(genesis, addPhone, addPc)
        val request = LeaveRequest("vault", "phone", addPc.hash, 1, 1_000_000)
        val signature = DeviceIdentityCrypto.sign(phoneSeed, request.signingBytes())
        val newSeed = ByteArray(32) { 7 }
        val rejoin = SyncMembershipEvent.sign("vault", 4, addPc.hash, MembershipAction.ADD,
            "manager", "phone-2", encoder.encodeToString(DeviceIdentityCrypto.publicKey(newSeed)), 1, signManager)
        val decision = LeaveRequestHandler.handleFromDrive(request, signature, history + rejoin, setOf(1L),
            "manager", 1_000_000, signManager)
        val appended = (decision as LeaveDecision.Left).append
        val after = SyncMembershipManager.verify(history + rejoin + appended).members
        assertEquals(MemberStatus.REVOKED.name, after.single { it.deviceId == "phone" }.status)
        assertEquals(MemberStatus.ACTIVE.name, after.single { it.deviceId == "phone-2" }.status)
        // The new key can't make the old request: it does not verify as "phone".
        val forged = DeviceIdentityCrypto.sign(newSeed, request.signingBytes())
        assertEquals(LeaveDecision.Reject, LeaveRequestHandler.handleFromDrive(request, forged, history + rejoin,
            setOf(1L), "manager", 1_000_000, signManager))
    }

    @Test fun joiningAgainDropsOnlyALeaveRequestOfTheSameIdentity() {
        assertTrue(MembershipChoices.dropLeaveAfterJoin("phone", "phone"))
        assertFalse(MembershipChoices.dropLeaveAfterJoin("phone", "phone-2"))
    }

    @Test fun emptyingOnLeaveNeedsEveryOwnChangeOnAnotherDevice() {
        val members = SyncMembershipManager.verify(listOf(genesis, addPhone, addPc, removePhone)).members
        fun progress(sequence: Long) = SyncProgress(mapOf("pc" to SyncChainHead(sequence, if (sequence == 0L) GENESIS_HASH else "a".repeat(64))), emptyMap())
        // Nothing written yet: nothing to lose.
        assertTrue(MembershipChoices.changesHeldElsewhere("pc", 0, members, emptyMap()))
        // Never reported, or behind: emptying would lose edits.
        assertFalse(MembershipChoices.changesHeldElsewhere("pc", 5, members, emptyMap()))
        assertFalse(MembershipChoices.changesHeldElsewhere("pc", 5, members, mapOf("manager" to progress(4))))
        // One active device holds them all.
        assertTrue(MembershipChoices.changesHeldElsewhere("pc", 5, members, mapOf("manager" to progress(5))))
        // A removed device's report does not count.
        assertFalse(MembershipChoices.changesHeldElsewhere("pc", 5, members, mapOf("phone" to progress(9))))
    }

    @Test fun aManagerLeftAloneStillPublishesARemovalThroughGoogleDrive() {
        val removeAll = SyncMembershipEvent.sign("vault", 5, removePhone.hash, MembershipAction.REMOVE,
            "manager", "pc", "", 3, signManager)
        val alone = SyncMembershipManager.verify(listOf(genesis, addPhone, addPc, removePhone, removeAll)).members
        assertTrue(MembershipChoices.cloudPassNeeded("manager", alone, driveHasCurrentMembership = false))
        // Once the drive holds that history, a lone device stops spending passes.
        assertFalse(MembershipChoices.cloudPassNeeded("manager", alone, driveHasCurrentMembership = true))
        // A device that was never in a shared group has nothing to publish.
        val single = SyncMembershipManager.verify(listOf(genesis)).members
        assertFalse(MembershipChoices.cloudPassNeeded("manager", single, driveHasCurrentMembership = false))
        // With another active member, passes run as before; a removed device itself never runs one.
        val shared = SyncMembershipManager.verify(listOf(genesis, addPhone, addPc, removePhone)).members
        assertTrue(MembershipChoices.cloudPassNeeded("manager", shared, driveHasCurrentMembership = true))
        assertFalse(MembershipChoices.cloudPassNeeded("phone", shared, driveHasCurrentMembership = false))
    }

    @Test fun removalNoticesUseTheSameEpochRuleAsWindows() {
        // The last four previous epochs, with no time limit (Windows lan_sync.rs uses the same range).
        assertEquals(listOf(9L, 8L, 7L, 6L), TransportEpochs.retained(10))
        assertEquals(emptyList<Long>(), TransportEpochs.retained(1))
        // Only the battery rule for keeping the service up looks at time.
        val day = 24L * 60 * 60 * 1000
        assertEquals(listOf(9L), TransportEpochs.keepServiceFor(10, mapOf(6L to 0L, 7L to 0L, 8L to 0L, 9L to 100 * day), 150 * day))
    }
}
