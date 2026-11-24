package com.privatevault.app.backup

import com.privatevault.app.sync.MemberStatus
import com.privatevault.app.sync.SyncMembershipEvent
import com.privatevault.app.sync.SyncMembershipManager

/** What a restore does to this device's sync group. A restore always gives the vault a new identity. */
internal enum class RestoreGroupRole {
    /** Not in a shared group: nothing to tell anyone. */
    NONE,
    /** A member: it signs a leave request first, so the managing device removes it. */
    MEMBER,
    /** The managing device: nobody can remove it, so the group is left without a manager. */
    MANAGER,
}

internal data class RestoreSyncImpact(val role: RestoreGroupRole, val otherDevices: Int = 0) {
    companion object { val NONE = RestoreSyncImpact(RestoreGroupRole.NONE) }
}

internal object RestoreReview {
    /** The role of [localDeviceId] in the signed [history] of its sync group. */
    fun syncImpact(history: List<SyncMembershipEvent>, localDeviceId: String): RestoreSyncImpact {
        if (history.isEmpty()) return RestoreSyncImpact.NONE
        val verified = runCatching { SyncMembershipManager.verify(history) }.getOrNull() ?: return RestoreSyncImpact.NONE
        val active = verified.members.filter { it.status == MemberStatus.ACTIVE.name }
        if (active.none { it.deviceId == localDeviceId } || active.size < 2) return RestoreSyncImpact.NONE
        val role = if (verified.managerDeviceId == localDeviceId) RestoreGroupRole.MANAGER else RestoreGroupRole.MEMBER
        return RestoreSyncImpact(role, active.size - 1)
    }

    /** "12 items, including 3 authenticators": authenticators are items too, so they are not added on top. */
    fun contents(items: Int, codes: Int, groups: Int, photos: Int, passkeys: Int): String {
        val main = count(items, "item") + if (codes > 0) ", including ${count(codes, "authenticator")}" else ""
        return "The backup has $main. It also has ${count(groups, "group")}, ${count(photos, "photo")} and ${count(passkeys, "passkey")}."
    }

    /** The sync part of the restore review. */
    fun syncNote(impact: RestoreSyncImpact): String = when (impact.role) {
        RestoreGroupRole.NONE -> ""
        RestoreGroupRole.MEMBER -> "This device will leave its sync group. Nuvori tells the managing device, which removes it. " +
            "The restored items don't sync. To share them, pair your devices again."
        RestoreGroupRole.MANAGER -> "This device manages its sync group. After the restore, " +
            (if (impact.otherDevices == 1) "the other device stays" else "the other ${impact.otherDevices} devices stay") +
            " in the group without a managing device, so nobody can add or remove devices. " +
            "Move the managing role to another phone first, or restore anyway."
    }

    private fun count(value: Int, noun: String) = "$value ${if (value == 1) noun else noun + "s"}"
}
