package com.privatevault.app.sync

import androidx.annotation.Keep

/**
 * History compaction on Google Drive (feature [FEATURE]). Shared vectors: `compaction-v1.json`
 * `drive` (scripts/sync-vectors/compaction.py); Windows `compaction.rs`.
 *
 * - A floor marker `c.<author tag>` says the author's changes up to `floor` may be gone from the
 *   drive. A reader counts it only at or below its own floor(A), so a wrong or hostile marker can
 *   only make a device upload again, never skip what a peer needs.
 * - Coverage starts after the counted floor, so no device uploads a deleted range again.
 * - `o.` files at or below floor(A) go once every active member applied them: an offline member
 *   pins floor(A) and holds the cleanup back; a removed member is not active and pins nothing.
 * - Only when every active member announced [FEATURE]: an older build counts coverage from change 1
 *   and would upload everything again.
 */
internal object DriveCompaction {
    const val FEATURE = "history-compaction-v1"
    const val MAX_DELETES = 64
    const val INTERVAL_MS = 24L * 60 * 60 * 1000
    const val MARKER_BYTES = 4_096L

    @Keep
    data class Marker(val author: String, val floor: Long, val hash: String, val writer: String, val at: Long)

    /** The end of the gap-free run of files that starts right after [floor]. */
    fun coverage(ranges: List<Pair<Long, Long>>, floor: Long): Long {
        var covered = floor
        for ((first, last) in ranges.sortedWith(compareBy({ it.first }, { it.second }))) {
            if (first > covered + 1) break
            covered = maxOf(covered, last)
        }
        return covered
    }

    /** A marker counts only at or below this reader's own floor(A). */
    fun countedFloor(marker: Long?, readerFloor: Long): Long = if (marker != null && marker <= readerFloor) marker else 0L

    /** The files to delete for one author and the new marker floor. */
    fun cleanup(ranges: List<Pair<Long, Long>>, marker: Long, floor: Long): Pair<Long, List<Pair<Long, Long>>> {
        val reach = minOf(floor, coverage(ranges, marker))
        val gone = ranges.filter { it.second <= reach }.sortedWith(compareBy({ it.first }, { it.second }))
        return (listOf(marker) + gone.map { it.second }).max() to gone
    }

    /** Every other active member announced [FEATURE] (over Wi-Fi or in its drive progress file). */
    fun gateOpen(mirror: TransportMirror): Boolean = mirror.members
        .filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != mirror.localDeviceId }
        .all { FEATURE in mirror.peerFeatures[it.deviceId].orEmpty() }

    /** floor(A) per author from what this device and its active peers applied (no drive term). */
    fun floors(mirror: TransportMirror, localApplied: Map<String, Long>): Map<String, Long> =
        HistoryCompaction.floors(mirror.localDeviceId, mirror.members, localApplied,
            mirror.peerProgress.mapValues { (_, progress) -> progress.applied.mapValues { it.value.sequence } }, null)
}
