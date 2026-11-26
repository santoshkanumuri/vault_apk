package com.privatevault.app.sync

/**
 * One sync status for both transports (Wi-Fi and Google Drive), the same rules as Windows
 * `src/lib/syncOverview.ts`. A success on either transport counts. A device is up to date when its
 * verified progress (a Wi-Fi exchange or a signed Drive `p.` file, both kept in
 * [TransportMirror.peerProgress]) shows it applied this device's latest change, whichever transport
 * carried it. A transport failure is only a quiet note while the other one keeps devices in step.
 */
internal enum class TransportHealth { OFF, OK, FAILED }

/** One active paired device. [unconfirmed]: this device's changes it has not applied yet. */
internal data class PeerSyncFacts(val deviceId: String, val name: String, val unconfirmed: Long,
    /** False until the device reported verified progress once. */
    val reported: Boolean,
    /** The newest Wi-Fi contact or signed Drive progress report; 0 = never. */
    val lastSeen: Long,
    /** The last Wi-Fi attempt to reach this device failed. */
    val wifiFailed: Boolean = false,
    /**
     * This device's own changes that another device already applied but that have not arrived here
     * (H1): a session ended early, or a file or change in its chain is stuck.
     */
    val missingHere: Long = 0L,
    /** Its changes stored here that wait for an app update: the next one is a newer kind of change (M4). */
    val needsUpdate: Long = 0L,
    /** Its changes stored here that wait behind one that couldn't apply (H2). */
    val blocked: Long = 0L)

internal data class SyncFacts(
    val now: Long,
    val peers: List<PeerSyncFacts>,
    val busy: Boolean = false,
    val wifi: TransportHealth = TransportHealth.OK,
    val drive: TransportHealth = TransportHealth.OFF,
    /** Automatic Wi-Fi sync is paused by the user. */
    val paused: Boolean = false,
    /** This device's own last finished Google Drive pass; 0 = never or not connected. */
    val lastDriveSync: Long = 0L,
    /** A Wi-Fi sync problem on this device that is not about reaching a device (storage, a full queue). */
    val localProblem: String? = null,
    val conflicts: Int = 0,
    /** Incoming changes that could not apply. */
    val rejected: Int = 0,
    /** A removal changed the sync keys and this device's new keys have not arrived. */
    val waitingForKeys: Boolean = false,
    /** Changes arrived here and still wait to apply on this device. */
    val waitingHere: Boolean = false,
    /** Google Drive is connected and needs the user to sign in again (M14). */
    val driveSignIn: Boolean = false)

internal enum class PeerAck { UP_TO_DATE, WAITING, SILENT, NOT_CHECKED }

internal enum class OverallSync { SYNCING, ATTENTION, KEYS, CONFLICTS, WAITING, WAITING_HERE, STALE, NOT_YET, UP_TO_DATE }

internal enum class AttentionCause { BOTH_FAILED, WIFI_ONLY_FAILED, DRIVE_ONLY_FAILED, LOCAL_PROBLEM, REJECTED, DEVICE_SILENT,
    /** Changes from another device wait for this app to be updated (M4). */
    APP_UPDATE,
    /** Google Drive needs the user to sign in again (M14). */
    DRIVE_SIGN_IN }

/** The quiet line under the headline. */
internal enum class TransportNote {
    WIFI_FAILED_DRIVE_KEPT_UP, WIFI_FAILED_DRIVE_CARRIES, WIFI_FAILED,
    DRIVE_FAILED_WIFI_KEPT_UP, DRIVE_FAILED_WIFI_CARRIES, DRIVE_FAILED, PAUSED
}

internal data class SyncOverview(
    val state: OverallSync,
    val cause: AttentionCause? = null,
    val peers: Map<String, PeerAck> = emptyMap(),
    /** Devices that have not confirmed this device's latest changes, the longest silent first. */
    val waitingFor: List<PeerSyncFacts> = emptyList(),
    /** This device's changes that not every active device has applied yet, over both transports. */
    val waitingChanges: Long = 0L,
    /** The newest success on either transport: a device heard from, or this device's own Drive pass. */
    val lastSynced: Long = 0L,
    /** Days since any device was heard from (for [OverallSync.STALE]) or since the silent device was. */
    val days: Long = 0L,
    val note: TransportNote? = null,
    /** The devices a [note] is about; empty means "your devices". */
    val noteDevices: List<String> = emptyList(),
    /** Devices with changes another device applied that have not arrived here, the most first. */
    val missingFrom: List<PeerSyncFacts> = emptyList(),
    /** Devices with changes that wait here for an app update. */
    val updateFrom: List<PeerSyncFacts> = emptyList()) {
    val needsAttention get() = state == OverallSync.ATTENTION
}

internal object SyncOverviewRules {
    /** A device unheard for this long, or no device heard from for this long, is a problem. */
    const val STALE_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** This device's changes [peer] has not applied, from verified progress; all of them before its first report. */
    fun unconfirmed(localId: String, local: SyncProgress, peer: SyncProgress?): Long {
        val written = local.applied[localId]?.sequence ?: 0L
        return (written - (peer?.applied?.get(localId)?.sequence ?: 0L)).coerceAtLeast(0L)
    }

    /**
     * A device never heard from (no verified progress, no contact) is [PeerAck.NOT_CHECKED], whatever
     * [PeerSyncFacts.unconfirmed] says: before its first report that count is every change this device
     * ever wrote, not changes it misses (a joiner gets them in its vault copy). A device whose pairing
     * never finished stays here, so it never makes changes look stuck; its card offers to remove it.
     */
    fun peerAck(peer: PeerSyncFacts, now: Long): PeerAck = when {
        peer.lastSeen == 0L && !peer.reported -> PeerAck.NOT_CHECKED
        peer.unconfirmed > 0 -> if (peer.lastSeen > 0 && now - peer.lastSeen > STALE_AFTER_MS) PeerAck.SILENT
            else PeerAck.WAITING
        else -> PeerAck.UP_TO_DATE
    }

    fun overview(facts: SyncFacts): SyncOverview {
        val now = facts.now
        val acks = facts.peers.associate { it.deviceId to peerAck(it, now) }
        val waitingFor = facts.peers.filter { it.unconfirmed > 0 && acks[it.deviceId] != PeerAck.NOT_CHECKED }
            .sortedBy { if (it.lastSeen == 0L) Long.MAX_VALUE else it.lastSeen }
        val waitingChanges = waitingFor.maxOfOrNull { it.unconfirmed } ?: 0L
        val lastHeard = facts.peers.maxOfOrNull { it.lastSeen } ?: 0L
        val lastSynced = maxOf(lastHeard, facts.lastDriveSync)
        val silent = waitingFor.firstOrNull { acks[it.deviceId] == PeerAck.SILENT }
        val inStep = waitingFor.isEmpty()
        val wifiFailed = facts.wifi == TransportHealth.FAILED
        val driveFailed = facts.drive == TransportHealth.FAILED
        val failedNames = facts.peers.filter { it.wifiFailed }.map { it.name }
            .ifEmpty { facts.peers.singleOrNull()?.let { listOf(it.name) } ?: emptyList() }

        val note: TransportNote? = when {
            wifiFailed && driveFailed -> null
            wifiFailed -> when {
                facts.drive == TransportHealth.OK && inStep -> TransportNote.WIFI_FAILED_DRIVE_KEPT_UP
                facts.drive == TransportHealth.OK -> TransportNote.WIFI_FAILED_DRIVE_CARRIES
                else -> TransportNote.WIFI_FAILED
            }
            driveFailed -> when {
                facts.wifi == TransportHealth.OK && inStep -> TransportNote.DRIVE_FAILED_WIFI_KEPT_UP
                facts.wifi == TransportHealth.OK -> TransportNote.DRIVE_FAILED_WIFI_CARRIES
                else -> TransportNote.DRIVE_FAILED
            }
            facts.paused -> TransportNote.PAUSED
            else -> null
        }
        val noteDevices = if (note in WIFI_NOTES) failedNames else emptyList()
        val missingFrom = facts.peers.filter { it.missingHere > 0 }.sortedByDescending { it.missingHere }
        val updateFrom = facts.peers.filter { it.needsUpdate > 0 }.sortedByDescending { it.needsUpdate }
        fun result(state: OverallSync, cause: AttentionCause? = null, days: Long = 0L) = SyncOverview(state, cause, acks,
            waitingFor, waitingChanges, lastSynced, days, note, noteDevices, missingFrom, updateFrom)

        // No transport is left to carry waiting changes: one failed and the other is off or failed too.
        val noTransport = (wifiFailed || driveFailed) && facts.wifi != TransportHealth.OK && facts.drive != TransportHealth.OK
        return when {
            facts.busy -> result(OverallSync.SYNCING)
            // M14: only the user can fix a sign-in, so it is never hidden behind "Up to date".
            facts.driveSignIn -> result(OverallSync.ATTENTION, AttentionCause.DRIVE_SIGN_IN)
            wifiFailed && driveFailed -> result(OverallSync.ATTENTION, AttentionCause.BOTH_FAILED)
            facts.localProblem != null -> result(OverallSync.ATTENTION, AttentionCause.LOCAL_PROBLEM)
            facts.rejected > 0 -> result(OverallSync.ATTENTION, AttentionCause.REJECTED)
            updateFrom.isNotEmpty() -> result(OverallSync.ATTENTION, AttentionCause.APP_UPDATE)
            silent != null -> result(OverallSync.ATTENTION, AttentionCause.DEVICE_SILENT, (now - silent.lastSeen) / DAY_MS)
            noTransport && !inStep -> result(OverallSync.ATTENTION,
                if (wifiFailed) AttentionCause.WIFI_ONLY_FAILED else AttentionCause.DRIVE_ONLY_FAILED)
            facts.waitingForKeys -> result(OverallSync.KEYS)
            facts.conflicts > 0 -> result(OverallSync.CONFLICTS)
            !inStep -> result(OverallSync.WAITING)
            // "Up to date" only when this device also holds everything any device applied (H1).
            facts.waitingHere || missingFrom.isNotEmpty() -> result(OverallSync.WAITING_HERE)
            lastSynced == 0L -> result(OverallSync.NOT_YET)
            facts.peers.isNotEmpty() && lastHeard > 0 && now - lastHeard > STALE_AFTER_MS ->
                result(OverallSync.STALE, days = (now - lastHeard) / DAY_MS)
            facts.peers.isNotEmpty() && lastHeard == 0L -> result(OverallSync.NOT_YET)
            else -> result(OverallSync.UP_TO_DATE)
        }
    }

    private val WIFI_NOTES = setOf(TransportNote.WIFI_FAILED_DRIVE_KEPT_UP, TransportNote.WIFI_FAILED_DRIVE_CARRIES,
        TransportNote.WIFI_FAILED)

    /**
     * The facts from what this device holds. [devices] are the active paired devices (ID to name). Peer
     * progress comes only from verified sources: a Wi-Fi exchange or a signed Drive report of an active
     * member ([CloudSyncPass]); contact times likewise ([LockedSyncStore.recordPeerSeen]).
     */
    fun facts(now: Long, devices: List<Pair<String, String>>, localId: String, local: SyncProgress?,
        peerProgress: Map<String, SyncProgress>, lastContact: Map<String, Long>, lastExchange: Map<String, Long>,
        lan: DeviceSyncStatus, peerStatus: Map<String, DevicePeerStatus>, cloud: CloudSyncStatus,
        conflicts: Int = 0, rejected: Int = 0, waitingForKeys: Boolean = false, queueWaiting: Boolean = false,
        waitingForUpdate: Map<String, Long> = emptyMap(), blockedBehindSetAside: Map<String, Long> = emptyMap()): SyncFacts {
        val reachFailed = lan.phase == DeviceSyncPhase.ATTENTION && isReachFailure(lan.detail)
        val wifi = when {
            reachFailed -> TransportHealth.FAILED
            lan.phase in setOf(DeviceSyncPhase.OFF, DeviceSyncPhase.PAUSED, DeviceSyncPhase.WAITING) -> TransportHealth.OFF
            else -> TransportHealth.OK
        }
        val drive = when {
            !cloud.connected -> TransportHealth.OFF
            cloud.needsSignIn || cloud.lastError != null -> TransportHealth.FAILED
            else -> TransportHealth.OK
        }
        val others = devices.filter { it.first != localId }
        val peers = others.map { (id, name) ->
            val progress = peerProgress[id]
            val status = peerStatus[id]
            PeerSyncFacts(id, name, local?.let { unconfirmed(localId, it, progress) } ?: 0L, progress != null,
                maxOf(lastContact[id] ?: 0L, lastExchange[id] ?: 0L),
                status?.phase == DeviceSyncPhase.ATTENTION && isReachFailure(status.detail),
                local?.let { missingHere(id, it, others.mapNotNull { (peer, _) -> peerProgress[peer] }) } ?: 0L,
                waitingForUpdate[id] ?: 0L, blockedBehindSetAside[id] ?: 0L)
        }
        return SyncFacts(now, peers,
            busy = lan.phase == DeviceSyncPhase.CONNECTING || lan.phase == DeviceSyncPhase.TRANSFERRING,
            wifi = wifi, drive = drive, paused = lan.phase == DeviceSyncPhase.PAUSED,
            lastDriveSync = if (cloud.connected) cloud.lastSyncAt else 0L,
            localProblem = lan.detail.takeIf { lan.phase == DeviceSyncPhase.ATTENTION && !reachFailed && it.isNotBlank() },
            conflicts = conflicts, rejected = rejected, waitingForKeys = waitingForKeys,
            waitingHere = queueWaiting || lan.phase == DeviceSyncPhase.RECEIVED,
            driveSignIn = cloud.connected && cloud.needsSignIn)
    }

    /**
     * Changes by [author] that an active device reports applied but this device has not received:
     * the highest head any of them applied, less what is here.
     */
    fun missingHere(author: String, local: SyncProgress, peers: Collection<SyncProgress>): Long {
        val best = peers.maxOfOrNull { it.applied[author]?.sequence ?: 0L } ?: 0L
        return (best - (local.received[author]?.sequence ?: 0L)).coerceAtLeast(0L)
    }

    /** True for a Wi-Fi failure to reach a device, as [LanSyncService] reports it; other problems stay errors. */
    fun isReachFailure(detail: String): Boolean = detail == LanSyncService.NO_DEVICE_ANSWERED ||
        detail == LanSyncService.CONNECTION_CLOSED || detail == LanSyncService.PEER_CONNECTION_CLOSED
}
