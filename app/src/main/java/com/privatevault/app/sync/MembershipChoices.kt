package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEntity

/** Decisions behind the Devices & sync membership buttons, kept out of the ViewModel so they can be tested. */
internal object MembershipChoices {
    /**
     * True when leaving the group must first sign a leave request for the managing device. The
     * managing device itself never can: a REMOVE of the manager is invalid in the signed history
     * ([SyncMembershipManager.verify]), so it has nobody to ask and only starts its separate group.
     * The old group then keeps its members but has no managing device.
     */
    fun leaveSendsRequest(history: List<SyncMembershipEvent>, localDeviceId: String): Boolean {
        val verified = SyncMembershipManager.verify(history)
        require(verified.members.any { it.deviceId == localDeviceId && it.status == MemberStatus.ACTIVE.name }) {
            "This device is not in an active shared sync group"
        }
        return verified.managerDeviceId != localDeviceId
    }

    /**
     * The devices that can take the managing role: active Android members. Windows ([windowsIds],
     * the peers that announced the Windows platform) cannot sign an acceptance, so an offer to a
     * PC would stay pending, and a pending offer blocks every removal.
     */
    fun managingRoleCandidates(devices: List<SyncMembershipEntity>, windowsIds: Set<String>): List<SyncMembershipEntity> =
        devices.filter { it.status == MemberStatus.ACTIVE.name && it.deviceId !in windowsIds }

    /**
     * After a join as [joinedDeviceId], a pending leave request signed by [pendingDeviceId] is
     * dropped only when it is the same identity: the manager would otherwise remove the device that
     * just joined (a request made by a build before identity rotation). A request of an earlier,
     * retired identity is kept, because it removes that stale entry from the group.
     */
    fun dropLeaveAfterJoin(pendingDeviceId: String, joinedDeviceId: String): Boolean = pendingDeviceId == joinedDeviceId

    /**
     * "Leave and empty this phone" deletes this device's copy, so it is allowed only when every
     * change this device wrote ([localWritten], its own chain length) already reached at least one
     * other active member, by that member's last reported progress. Otherwise those edits would
     * exist nowhere after the leave.
     */
    fun changesHeldElsewhere(localDeviceId: String, localWritten: Long, members: List<SyncMembershipEntity>,
        peerProgress: Map<String, SyncProgress>): Boolean = localWritten <= 0L || members.any { member ->
        member.status == MemberStatus.ACTIVE.name && member.deviceId != localDeviceId &&
            (peerProgress[member.deviceId]?.received?.get(localDeviceId)?.sequence ?: 0L) >= localWritten
    }

    /**
     * Whether a Google Drive pass is worth running. Normally only with another active member. A
     * managing device left alone after removing everyone still runs passes until the drive holds its
     * current membership history ([driveHasCurrentMembership]): a removed device that syncs only
     * through Google Drive learns its removal from that file.
     */
    fun cloudPassNeeded(localDeviceId: String, members: List<SyncMembershipEntity>,
        driveHasCurrentMembership: Boolean): Boolean {
        val active = members.filter { it.status == MemberStatus.ACTIVE.name }
        if (active.none { it.deviceId == localDeviceId }) return false
        if (active.size >= 2) return true
        return members.any { it.status == MemberStatus.REVOKED.name } && !driveHasCurrentMembership
    }
}
