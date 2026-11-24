package com.privatevault.app.backup

import com.privatevault.app.sync.DeviceIdentityCrypto
import com.privatevault.app.sync.GENESIS_HASH
import com.privatevault.app.sync.MembershipAction
import com.privatevault.app.sync.SyncMembershipEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The restore review says what the backup holds and what the restore does to this device's sync group. */
class RestoreReviewTest {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val managerSeed = ByteArray(32) { 1 }
    private val sign: (ByteArray) -> ByteArray = { DeviceIdentityCrypto.sign(managerSeed, it) }
    private fun key(seed: Int) = encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { seed.toByte() }))
    private val genesis = SyncMembershipEvent.sign("vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
        "manager", "manager", key(1), 1, sign)
    private val addPhone = SyncMembershipEvent.sign("vault", 2, genesis.hash, MembershipAction.ADD,
        "manager", "phone", key(2), 1, sign)
    private val addPc = SyncMembershipEvent.sign("vault", 3, addPhone.hash, MembershipAction.ADD,
        "manager", "pc", key(3), 1, sign)
    private val removePhone = SyncMembershipEvent.sign("vault", 4, addPc.hash, MembershipAction.REMOVE,
        "manager", "phone", "", 2, sign)

    @Test fun aMemberLeavesWithARequestAndTheManagerLeavesTheGroupWithoutOne() {
        val group = listOf(genesis, addPhone, addPc)
        assertEquals(RestoreSyncImpact(RestoreGroupRole.MEMBER, 2), RestoreReview.syncImpact(group, "phone"))
        assertEquals(RestoreSyncImpact(RestoreGroupRole.MANAGER, 2), RestoreReview.syncImpact(group, "manager"))
    }

    @Test fun aDeviceOutsideAGroupHasNothingToTell() {
        assertEquals(RestoreSyncImpact.NONE, RestoreReview.syncImpact(emptyList(), "phone"))
        // Alone in its own group: the usual state of a device that never paired.
        assertEquals(RestoreSyncImpact.NONE, RestoreReview.syncImpact(listOf(genesis), "manager"))
        // Already removed: it is not in the group any more.
        assertEquals(RestoreSyncImpact.NONE, RestoreReview.syncImpact(listOf(genesis, addPhone, addPc, removePhone), "phone"))
        // A history that does not verify never asks for a signed leave.
        assertEquals(RestoreSyncImpact.NONE, RestoreReview.syncImpact(listOf(addPhone, addPc), "phone"))
    }

    @Test fun codesAreCountedInsideTheItemsNotOnTop() {
        // The old text, "Validated 12 entries ... and 3 authenticators", read as 15 items.
        val text = RestoreReview.contents(12, 3, 0, 0, 0)
        assertEquals("The backup has 12 items, including 3 authenticators. It also has 0 groups, 0 photos and 0 passkeys.", text)
        assertEquals("The backup has 1 item. It also has 1 group, 1 photo and 1 passkey.", RestoreReview.contents(1, 0, 1, 1, 1))
    }

    @Test fun theReviewNamesTheSyncGroupOnlyWhenTheDeviceIsInOne() {
        assertFalse(RestoreReview.syncNote(RestoreSyncImpact.NONE).contains("sync group"))
        val member = RestoreReview.syncNote(RestoreSyncImpact(RestoreGroupRole.MEMBER, 2))
        assertTrue(member.startsWith("This device will leave its sync group. Nuvori tells the managing device"))
        val manager = RestoreReview.syncNote(RestoreSyncImpact(RestoreGroupRole.MANAGER, 1))
        assertTrue(manager.contains("the other device stays in the group without a managing device"))
        assertTrue(manager.contains("Move the managing role to another phone first"))
        assertTrue(RestoreReview.syncNote(RestoreSyncImpact(RestoreGroupRole.MANAGER, 3)).contains("the other 3 devices stay"))
    }
}
