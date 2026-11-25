package com.privatevault.app.sync

/**
 * Keeping the drive's copy of every author's changes readable (audit C1 and M6). Shared with
 * Windows `drive_refill.rs`; both apps make the same choices from the same facts.
 *
 * - Refill marker (C1): the drive holds an author's changes up to `reach`, and the oldest change this
 *   device still holds is `oldest`, with a gap between them (history was trimmed here while Drive was
 *   off, or the drive lost files). When every active member already applied everything below `oldest`
 *   (this device's own floor(A), the same floor drive cleanup uses), a floor marker at `oldest - 1`
 *   lets every device go on from `oldest`. It is the marker that cleanup writes, so readers need
 *   nothing new, and an existing marker is never lowered. Otherwise the gap stays until a device
 *   that still holds the missing changes uploads them.
 * - Re-upload (M6): an active device keeps reporting less of an author than the drive holds, well
 *   after the drive got the file it needs: that file may be unreadable for it. A device that holds
 *   the range uploads it again as a new file, at most once a day per author.
 */
internal object DriveRefill {
    /** A device counts as stuck on a file it reported this long after the file reached the drive. */
    const val STUCK_AFTER_MS = 6L * 60 * 60 * 1000
    /** At most one re-upload per author in this long. */
    const val REUPLOAD_INTERVAL_MS = 24L * 60 * 60 * 1000

    enum class Step { NONE, MARK, STUCK }

    /** What to do about the start of an author's range on the drive. */
    fun plan(reach: Long, oldest: Long?, ownFloor: Long, marker: Long): Step = when {
        oldest == null || oldest <= reach + 1 -> Step.NONE
        oldest - 1 <= ownFloor && oldest - 1 > marker -> Step.MARK
        else -> Step.STUCK
    }

    data class Segment(val first: Long, val last: Long, val modifiedAt: Long)
    data class PeerReport(val received: Long, val reportedAt: Long)

    /**
     * The range to upload again for one author, as (after, through): from right after the lowest
     * sequence an active device stays stuck at, through the end of the files it can't get past.
     * Null when no device is stuck, or the last re-upload was less than a day ago.
     */
    fun reupload(covered: Long, peers: List<PeerReport>, segments: List<Segment>, lastReupload: Long, now: Long): Pair<Long, Long>? {
        if (now - lastReupload in 0 until REUPLOAD_INTERVAL_MS) return null
        return peers.mapNotNull { peer ->
            if (peer.received >= covered) return@mapNotNull null
            val next = segments.filter { it.first <= peer.received + 1 && it.last > peer.received }
            // No file holds its next change: a gap that a device holding the changes fills, not a stuck read.
            if (next.isEmpty()) return@mapNotNull null
            if (next.all { it.modifiedAt > 0 && peer.reportedAt - it.modifiedAt >= STUCK_AFTER_MS })
                peer.received to minOf(covered, next.maxOf { it.last }) else null
        }.minByOrNull { it.first }
    }
}
