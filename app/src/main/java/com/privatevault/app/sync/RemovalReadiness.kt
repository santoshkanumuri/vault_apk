package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEntity

/**
 * After a device is removed, peers stop relaying its changes (every receiver rejects a removed
 * author). A peer that has not yet received all of them never gets the rest, so the manager is
 * told before it signs the removal.
 */
internal object RemovalReadiness {
    /**
     * Active devices, other than this one and [removedDeviceId], whose last reported progress is
     * behind what this device holds from [removedDeviceId]. A peer that never reported counts as
     * behind when there is anything to receive.
     */
    fun peersBehind(removedDeviceId: String, localDeviceId: String, members: List<SyncMembershipEntity>,
        localReceived: Map<String, SyncChainHead>, peerProgress: Map<String, SyncProgress>): List<SyncMembershipEntity> {
        val held = localReceived[removedDeviceId]?.sequence ?: 0L
        if (held == 0L) return emptyList()
        return members.filter { member ->
            member.status == MemberStatus.ACTIVE.name && member.deviceId != localDeviceId &&
                member.deviceId != removedDeviceId &&
                (peerProgress[member.deviceId]?.received?.get(removedDeviceId)?.sequence ?: 0L) < held
        }
    }
}
