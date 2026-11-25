package com.privatevault.app

import android.content.res.Resources
import com.privatevault.app.sync.AttentionCause
import com.privatevault.app.sync.OverallSync
import com.privatevault.app.sync.PeerAck
import com.privatevault.app.sync.PeerSyncFacts
import com.privatevault.app.sync.SyncFacts
import com.privatevault.app.sync.SyncOverview
import com.privatevault.app.sync.TransportHealth
import com.privatevault.app.sync.TransportNote

/** Reads the words of the sync status from strings.xml; unit tests read the same file. */
internal interface SyncWords {
    fun text(id: Int, vararg args: Any): String
    fun plural(id: Int, count: Int, vararg args: Any): String
}

internal class ResourceSyncWords(private val resources: Resources) : SyncWords {
    override fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any): String = resources.getQuantityString(id, count, *args)
}

/** The status card's headline, its detail, the quiet transport line and "Last synced". */
internal data class SyncOverviewText(val kind: StatusKind, val title: String, val detail: String,
    val note: String? = null, val lastSynced: String? = null)

/** A device card's chip and its one-line state. */
internal data class PeerAckText(val kind: StatusKind, val label: String, val detail: String)

internal fun syncOverviewText(facts: SyncFacts, overview: SyncOverview, words: SyncWords,
    busyTitle: String? = null, busyDetail: String? = null): SyncOverviewText {
    val waitingName = overview.waitingFor.singleOrNull()?.name
    val noteDevices = overview.noteDevices.singleOrNull() ?: words.text(R.string.sync_your_devices)
    val noteCount = if (overview.noteDevices.size == 1) 1 else 2
    val (kind, title, detail) = when (overview.state) {
        OverallSync.SYNCING -> Triple(StatusKind.PROGRESS, busyTitle ?: words.text(R.string.sync_state_syncing), busyDetail.orEmpty())
        OverallSync.ATTENTION -> Triple(StatusKind.ERROR, words.text(R.string.sync_state_attention), when (overview.cause) {
            AttentionCause.BOTH_FAILED -> words.text(R.string.sync_detail_both_failed)
            AttentionCause.WIFI_ONLY_FAILED -> words.plural(R.plurals.sync_detail_wifi_only_failed, noteCount, noteDevices)
            AttentionCause.DRIVE_ONLY_FAILED -> words.text(R.string.sync_detail_drive_only_failed)
            AttentionCause.DRIVE_SIGN_IN -> words.text(R.string.sync_detail_drive_sign_in)
            AttentionCause.LOCAL_PROBLEM -> facts.localProblem.orEmpty()
            AttentionCause.REJECTED -> facts.peers.filter { it.blocked > 0 }.maxByOrNull { it.blocked }?.let { peer ->
                words.plural(R.plurals.sync_detail_rejected_from, peer.blocked.toCount(), peer.blocked, peer.name)
            } ?: words.plural(R.plurals.sync_detail_rejected, facts.rejected, facts.rejected)
            AttentionCause.APP_UPDATE -> overview.updateFrom.first().let { peer ->
                words.plural(R.plurals.sync_detail_app_update, peer.needsUpdate.toCount(), peer.needsUpdate, peer.name)
            }
            AttentionCause.DEVICE_SILENT, null -> {
                val silent = overview.waitingFor.first { overview.peers[it.deviceId] == PeerAck.SILENT }
                words.text(R.string.sync_detail_device_silent, silent.name, overview.days.toInt())
            }
        })
        OverallSync.KEYS -> Triple(StatusKind.INFO, words.text(R.string.sync_state_keys), words.text(R.string.sync_detail_keys))
        OverallSync.CONFLICTS -> Triple(StatusKind.WARNING, words.text(R.string.sync_state_conflicts),
            words.plural(R.plurals.sync_detail_conflicts, facts.conflicts, facts.conflicts))
        OverallSync.WAITING -> Triple(StatusKind.INFO,
            if (waitingName != null) words.text(R.string.sync_state_waiting_for, waitingName)
            else words.text(R.string.sync_state_waiting_for_devices, overview.waitingFor.size),
            if (waitingName != null) words.plural(R.plurals.sync_detail_waiting_one, overview.waitingChanges.toCount(),
                overview.waitingChanges, waitingName)
            else words.plural(R.plurals.sync_detail_waiting_many, overview.waitingChanges.toCount(), overview.waitingChanges))
        OverallSync.WAITING_HERE -> Triple(StatusKind.WARNING, words.text(R.string.sync_state_waiting_here),
            overview.missingFrom.let { from ->
                val missing = from.sumOf { it.missingHere }
                when {
                    from.isEmpty() -> words.text(R.string.sync_detail_waiting_here)
                    from.size == 1 -> words.plural(R.plurals.sync_detail_missing_from, missing.toCount(), missing, from.single().name)
                    else -> words.plural(R.plurals.sync_detail_missing_many, missing.toCount(), missing)
                }
            })
        OverallSync.STALE -> Triple(StatusKind.WARNING, words.text(R.string.sync_state_stale, overview.days.toInt()),
            words.text(if (facts.drive != TransportHealth.OFF) R.string.sync_detail_stale_drive else R.string.sync_detail_stale_wifi))
        OverallSync.NOT_YET -> Triple(StatusKind.NEUTRAL, words.text(R.string.sync_state_not_yet), words.text(R.string.sync_detail_not_yet))
        OverallSync.UP_TO_DATE -> Triple(StatusKind.SUCCESS, words.text(R.string.sync_state_up_to_date),
            words.text(R.string.sync_detail_up_to_date))
    }
    val note = when (overview.note) {
        TransportNote.WIFI_FAILED_DRIVE_KEPT_UP -> words.plural(R.plurals.sync_note_wifi_failed_drive_kept_up, noteCount, noteDevices)
        TransportNote.WIFI_FAILED_DRIVE_CARRIES -> words.text(R.string.sync_note_wifi_failed_drive_carries, noteDevices)
        TransportNote.WIFI_FAILED -> words.text(R.string.sync_note_wifi_failed, noteDevices)
        TransportNote.DRIVE_FAILED_WIFI_KEPT_UP -> words.text(R.string.sync_note_drive_failed_wifi_kept_up)
        TransportNote.DRIVE_FAILED_WIFI_CARRIES -> words.text(R.string.sync_note_drive_failed_wifi_carries)
        TransportNote.DRIVE_FAILED -> words.text(R.string.sync_note_drive_failed)
        TransportNote.PAUSED -> words.text(R.string.sync_note_paused)
        null -> null
    }?.takeUnless { overview.state == OverallSync.ATTENTION && overview.cause in TRANSPORT_CAUSES }
    val lastSynced = overview.lastSynced.takeIf { it > 0 }?.let { words.text(R.string.sync_last_synced, relativeTime(it, facts.now)) }
    return SyncOverviewText(kind, title, detail, note, lastSynced)
}

/** A device's state by its verified progress, whichever transport carried it. */
internal fun peerAckText(peer: PeerSyncFacts, ack: PeerAck, now: Long, words: SyncWords): PeerAckText = when (ack) {
    PeerAck.UP_TO_DATE -> PeerAckText(StatusKind.SUCCESS, words.text(R.string.sync_peer_up_to_date),
        words.text(R.string.sync_peer_detail_up_to_date))
    PeerAck.WAITING -> PeerAckText(StatusKind.INFO, words.text(R.string.sync_peer_waiting),
        words.plural(R.plurals.sync_peer_detail_waiting, peer.unconfirmed.toCount(), peer.unconfirmed, peer.name))
    PeerAck.SILENT -> PeerAckText(StatusKind.ERROR, words.text(R.string.sync_peer_silent),
        words.text(R.string.sync_peer_detail_silent, ((now - peer.lastSeen) / 86_400_000L).toInt()))
    PeerAck.NOT_CHECKED -> PeerAckText(StatusKind.NEUTRAL, words.text(R.string.sync_peer_not_checked),
        words.text(R.string.sync_peer_detail_not_checked))
}

private val TRANSPORT_CAUSES = setOf(AttentionCause.BOTH_FAILED, AttentionCause.WIFI_ONLY_FAILED, AttentionCause.DRIVE_ONLY_FAILED,
    AttentionCause.DRIVE_SIGN_IN)

private fun Long.toCount(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
