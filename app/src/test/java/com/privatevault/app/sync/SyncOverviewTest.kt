package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one sync status over Wi-Fi and Google Drive ([SyncOverviewRules]); Windows `syncOverview.test.mjs` checks the same cases. */
class SyncOverviewTest {
    private val now = 1_790_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private fun peer(name: String, unconfirmed: Long = 0, seenAgo: Long? = 5 * minute, reported: Boolean = true,
        wifiFailed: Boolean = false) =
        PeerSyncFacts("id-$name", name, unconfirmed, reported, seenAgo?.let { now - it } ?: 0L, wifiFailed)
    private fun overview(vararg peers: PeerSyncFacts, wifi: TransportHealth = TransportHealth.OK,
        drive: TransportHealth = TransportHealth.OFF, block: SyncFacts.() -> SyncFacts = { this }) =
        SyncOverviewRules.overview(SyncFacts(now, peers.toList(), wifi = wifi, drive = drive,
            lastDriveSync = if (drive == TransportHealth.OFF) 0L else now - minute).block())

    @Test fun everyDeviceConfirmedAndRecentlyHeardIsUpToDate() {
        val result = overview(peer("Pixel 8"), peer("Tablet", seenAgo = 2 * day))
        assertEquals(OverallSync.UP_TO_DATE, result.state)
        assertEquals(0L, result.waitingChanges)
        assertEquals(now - minute, overview(peer("Pixel 8"), drive = TransportHealth.OK).lastSynced)
        assertEquals(PeerAck.UP_TO_DATE, result.peers["id-Tablet"])
    }

    /** The owner's case: Wi-Fi failed, Google Drive delivered everything. It is synced. */
    @Test fun aWifiFailureIsAQuietNoteWhileDriveKeepsDevicesInStep() {
        val result = overview(peer("Pixel 8", wifiFailed = true), wifi = TransportHealth.FAILED, drive = TransportHealth.OK)
        assertEquals(OverallSync.UP_TO_DATE, result.state)
        assertFalse(result.needsAttention)
        assertEquals(TransportNote.WIFI_FAILED_DRIVE_KEPT_UP, result.note)
        assertEquals(listOf("Pixel 8"), result.noteDevices)
        // Changes still on the way: Drive carries them, so it is waiting, not an error.
        val waiting = overview(peer("Pixel 8", unconfirmed = 2), wifi = TransportHealth.FAILED, drive = TransportHealth.OK)
        assertEquals(OverallSync.WAITING, waiting.state)
        assertEquals(TransportNote.WIFI_FAILED_DRIVE_CARRIES, waiting.note)
        assertEquals(2L, waiting.waitingChanges)
    }

    @Test fun aDriveFailureIsAQuietNoteWhileWifiKeepsDevicesInStep() {
        val result = overview(peer("Pixel 8"), drive = TransportHealth.FAILED)
        assertEquals(OverallSync.UP_TO_DATE, result.state)
        assertEquals(TransportNote.DRIVE_FAILED_WIFI_KEPT_UP, result.note)
        assertEquals(TransportNote.DRIVE_FAILED_WIFI_CARRIES, overview(peer("Pixel 8", unconfirmed = 1),
            drive = TransportHealth.FAILED).note)
    }

    @Test fun bothTransportsFailingIsAnError() {
        val result = overview(peer("Pixel 8"), wifi = TransportHealth.FAILED, drive = TransportHealth.FAILED)
        assertEquals(OverallSync.ATTENTION, result.state)
        assertEquals(AttentionCause.BOTH_FAILED, result.cause)
        assertNull(result.note)
    }

    @Test fun theOnlyTransportFailingIsAnErrorOnlyWhileChangesWait() {
        // Wi-Fi only, every device confirmed: synced, with a quiet note.
        val synced = overview(peer("Pixel 8", wifiFailed = true), wifi = TransportHealth.FAILED)
        assertEquals(OverallSync.UP_TO_DATE, synced.state)
        assertEquals(TransportNote.WIFI_FAILED, synced.note)
        val stuck = overview(peer("Pixel 8", unconfirmed = 3), wifi = TransportHealth.FAILED)
        assertEquals(OverallSync.ATTENTION, stuck.state)
        assertEquals(AttentionCause.WIFI_ONLY_FAILED, stuck.cause)
        // Drive only (Wi-Fi paused): a Drive failure with waiting changes is an error too.
        val driveOnly = overview(peer("Pixel 8", unconfirmed = 1), wifi = TransportHealth.OFF, drive = TransportHealth.FAILED)
        assertEquals(AttentionCause.DRIVE_ONLY_FAILED, driveOnly.cause)
    }

    @Test fun waitingCountsChangesNotEveryActiveDeviceApplied() {
        val result = overview(peer("Pixel 8", unconfirmed = 2), peer("Tablet", unconfirmed = 5), peer("PC"))
        assertEquals(OverallSync.WAITING, result.state)
        assertEquals(5L, result.waitingChanges)
        assertEquals(listOf("Tablet", "Pixel 8").sorted(), result.waitingFor.map { it.name }.sorted())
        assertEquals(PeerAck.WAITING, result.peers["id-Pixel 8"])
        assertEquals(PeerAck.UP_TO_DATE, result.peers["id-PC"])
    }

    @Test fun aDeviceSilentForLongWithWaitingChangesIsAnError() {
        val result = overview(peer("Tablet", unconfirmed = 1, seenAgo = 4 * day), peer("Pixel 8"), drive = TransportHealth.OK)
        assertEquals(OverallSync.ATTENTION, result.state)
        assertEquals(AttentionCause.DEVICE_SILENT, result.cause)
        assertEquals(4L, result.days)
        assertEquals(PeerAck.SILENT, result.peers["id-Tablet"])
        // Silent but holding everything: no error for that device.
        assertEquals(OverallSync.UP_TO_DATE, overview(peer("Tablet", seenAgo = 4 * day), peer("Pixel 8")).state)
    }

    @Test fun noDeviceHeardForDaysIsStaleEvenWhenThisDeviceDrivePassesWork() {
        val result = overview(peer("Pixel 8", seenAgo = 5 * day), drive = TransportHealth.OK)
        assertEquals(OverallSync.STALE, result.state)
        assertEquals(5L, result.days)
    }

    @Test fun realProblemsStayVisible() {
        assertEquals(AttentionCause.LOCAL_PROBLEM, overview(peer("Pixel 8")) { copy(localProblem = "Photo sync needs more free space.") }.cause)
        assertEquals(AttentionCause.REJECTED, overview(peer("Pixel 8")) { copy(rejected = 2) }.cause)
        assertEquals(OverallSync.KEYS, overview(peer("Pixel 8")) { copy(waitingForKeys = true) }.state)
        assertEquals(OverallSync.CONFLICTS, overview(peer("Pixel 8")) { copy(conflicts = 1) }.state)
        assertEquals(OverallSync.WAITING_HERE, overview(peer("Pixel 8")) { copy(waitingHere = true) }.state)
        assertEquals(OverallSync.SYNCING, overview(peer("Pixel 8"), wifi = TransportHealth.FAILED,
            drive = TransportHealth.FAILED) { copy(busy = true) }.state)
    }

    @Test fun aNewDeviceIsNotCheckedUntilHeardFrom() {
        val result = overview(peer("Pixel 8", seenAgo = null, reported = false))
        assertEquals(OverallSync.NOT_YET, result.state)
        assertEquals(PeerAck.NOT_CHECKED, result.peers["id-Pixel 8"])
        // Before its first report the count is every change this device wrote, not what it misses.
        assertEquals(PeerAck.NOT_CHECKED, SyncOverviewRules.peerAck(peer("Pixel 8", unconfirmed = 1, seenAgo = null,
            reported = false), now))
    }

    /** A PC whose pairing never finished: the phone signed its ADD, but it never synced. */
    @Test fun aDeviceThatNeverFinishedPairingNeverMakesChangesWaitForever() {
        val halfAdded = peer("Windows device", unconfirmed = 1303, seenAgo = null, reported = false)
        val alone = overview(halfAdded)
        assertEquals(OverallSync.NOT_YET, alone.state)
        assertEquals(0L, alone.waitingChanges)
        assertTrue(alone.waitingFor.isEmpty())
        // Beside a device that is in step, the status follows that device; the half-added one stays "not checked".
        val withPhone = overview(peer("Pixel 8"), halfAdded)
        assertEquals(OverallSync.UP_TO_DATE, withPhone.state)
        assertEquals(PeerAck.NOT_CHECKED, withPhone.peers["id-Windows device"])
        // Once it reports, its real backlog counts again.
        val reported = overview(peer("Windows device", unconfirmed = 3, seenAgo = minute, reported = true))
        assertEquals(OverallSync.WAITING, reported.state)
        assertEquals(3L, reported.waitingChanges)
    }

    @Test fun pausedSyncIsAQuietNote() {
        val result = overview(peer("Pixel 8"), wifi = TransportHealth.OFF) { copy(paused = true) }
        assertEquals(OverallSync.UP_TO_DATE, result.state)
        assertEquals(TransportNote.PAUSED, result.note)
    }

    private val head = { sequence: Long -> SyncChainHead(sequence, if (sequence == 0L) GENESIS_HASH else "a".repeat(64)) }

    @Test fun unconfirmedReadsTheAppliedHeadOfThisDevice() {
        val local = SyncProgress(mapOf("me" to head(7)), mapOf("me" to head(7)))
        assertEquals(7L, SyncOverviewRules.unconfirmed("me", local, null))
        assertEquals(3L, SyncOverviewRules.unconfirmed("me", local, SyncProgress(mapOf("me" to head(7)), mapOf("me" to head(4)))))
        // Received but not applied there is not confirmed.
        assertEquals(7L, SyncOverviewRules.unconfirmed("me", local, SyncProgress(mapOf("me" to head(7)), emptyMap())))
        assertEquals(0L, SyncOverviewRules.unconfirmed("me", local, SyncProgress(mapOf("me" to head(7)), mapOf("me" to head(7)))))
    }

    @Test fun factsSplitWifiReachFailuresFromLocalProblemsAndReadDrive() {
        val local = SyncProgress(mapOf("me" to head(2)), mapOf("me" to head(2)))
        val confirmed = SyncProgress(mapOf("me" to head(2)), mapOf("me" to head(2)))
        fun facts(lan: DeviceSyncStatus, cloud: CloudSyncStatus = CloudSyncStatus(),
            peers: Map<String, DevicePeerStatus> = emptyMap()) = SyncOverviewRules.facts(now,
            listOf("me" to "This phone", "pc" to "Office PC"), "me", local, mapOf("pc" to confirmed),
            mapOf("pc" to now - day), mapOf("pc" to now - 10 * minute), lan, peers, cloud)
        val unreachable = facts(DeviceSyncStatus(DeviceSyncPhase.ATTENTION, LanSyncService.NO_DEVICE_ANSWERED),
            CloudSyncStatus(connected = true, lastSyncAt = now - minute),
            mapOf("pc" to DevicePeerStatus(DeviceSyncPhase.ATTENTION, LanSyncService.PEER_CONNECTION_CLOSED)))
        assertEquals(TransportHealth.FAILED, unreachable.wifi)
        assertEquals(TransportHealth.OK, unreachable.drive)
        assertNull(unreachable.localProblem)
        assertEquals(listOf("pc"), unreachable.peers.map { it.deviceId })
        val pc = unreachable.peers.single()
        assertEquals(0L, pc.unconfirmed)
        assertTrue(pc.wifiFailed)
        assertEquals(now - 10 * minute, pc.lastSeen)
        assertEquals(OverallSync.UP_TO_DATE, SyncOverviewRules.overview(unreachable).state)

        val full = facts(DeviceSyncStatus(DeviceSyncPhase.ATTENTION, "Encrypted changes filled the queue."))
        assertEquals(TransportHealth.OK, full.wifi)
        assertEquals("Encrypted changes filled the queue.", full.localProblem)
        assertEquals(TransportHealth.FAILED, facts(DeviceSyncStatus(DeviceSyncPhase.CHECKED, ""),
            CloudSyncStatus(connected = true, needsSignIn = true)).drive)
        assertEquals(TransportHealth.OFF, facts(DeviceSyncStatus(DeviceSyncPhase.PAUSED, "")).wifi)
        assertTrue(facts(DeviceSyncStatus(DeviceSyncPhase.TRANSFERRING, "")).busy)
        assertTrue(facts(DeviceSyncStatus(DeviceSyncPhase.RECEIVED, "")).waitingHere)
    }

    private fun progress(received: Map<String, Long> = emptyMap(), applied: Map<String, Long> = emptyMap()) =
        SyncProgress(received.mapValues { SyncChainHead(it.value, "r${it.value}") },
            applied.mapValues { SyncChainHead(it.value, "a${it.value}") })

    /** H1: another device applied changes that never arrived here, so this device is not up to date. */
    @Test fun changesAnotherDeviceAppliedButThisOneLacksAreWaitingHere() {
        val local = progress(received = mapOf("id-pc" to 10, "id-me" to 4), applied = mapOf("id-pc" to 10, "id-me" to 4))
        val tablet = progress(applied = mapOf("id-pc" to 30, "id-me" to 4))
        val pc = progress(applied = mapOf("id-pc" to 30, "id-me" to 4))
        assertEquals(20L, SyncOverviewRules.missingHere("id-pc", local, listOf(tablet, pc)))
        assertEquals(0L, SyncOverviewRules.missingHere("id-me", local, listOf(tablet, pc)))
        val facts = SyncOverviewRules.facts(now, listOf("id-me" to "Phone", "id-pc" to "PC", "id-tab" to "Tablet"), "id-me",
            local, mapOf("id-pc" to pc, "id-tab" to tablet), mapOf("id-pc" to now - minute, "id-tab" to now - minute),
            emptyMap(), DeviceSyncStatus(DeviceSyncPhase.CHECKED, ""), emptyMap(), CloudSyncStatus())
        val result = SyncOverviewRules.overview(facts)
        assertEquals("every device confirmed this phone's own changes", 0L, result.waitingChanges)
        assertEquals(OverallSync.WAITING_HERE, result.state)
        assertEquals(listOf("PC"), result.missingFrom.map { it.name })
        assertEquals(20L, result.missingFrom.single().missingHere)
        // Once they arrive, it is up to date again.
        val caughtUp = SyncOverviewRules.facts(now, listOf("id-me" to "Phone", "id-pc" to "PC"), "id-me",
            progress(received = mapOf("id-pc" to 30), applied = mapOf("id-pc" to 30)), mapOf("id-pc" to pc),
            mapOf("id-pc" to now - minute), emptyMap(), DeviceSyncStatus(DeviceSyncPhase.CHECKED, ""), emptyMap(),
            CloudSyncStatus())
        assertEquals(OverallSync.UP_TO_DATE, SyncOverviewRules.overview(caughtUp).state)
    }

    /** M4: a change of a kind this version doesn't know waits for an update, and says so. */
    @Test fun changesWaitingForAnAppUpdateNeedAttention() {
        val result = overview(peer("PC").copy(needsUpdate = 3))
        assertEquals(OverallSync.ATTENTION, result.state)
        assertEquals(AttentionCause.APP_UPDATE, result.cause)
        assertEquals(listOf("PC"), result.updateFrom.map { it.name })
        // A change that was set aside still comes first: it needs a decision, not an update.
        assertEquals(AttentionCause.REJECTED, overview(peer("PC").copy(needsUpdate = 3)) { copy(rejected = 1) }.cause)
    }
}
