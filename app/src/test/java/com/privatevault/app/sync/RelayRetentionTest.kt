package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRetentionTest {
    private fun held(author: String, sequence: Long) = RelayRetention.Held(author, sequence, hash(author, sequence))
    private fun hash(author: String, sequence: Long) = (author.first().toString() + sequence.toString().padStart(4, '0')).padEnd(64, 'a')
        .map { if (it in '0'..'9' || it in 'a'..'f') it else 'b' }.joinToString("")
    private fun context(local: String = "phone", peers: Set<String> = setOf("pc", "tablet"),
        applied: Map<String, Map<String, Long>> = emptyMap(), cutoffs: Map<String, SyncChainHead> = emptyMap(),
        active: Set<String> = peers + local, cloud: Map<String, Long>? = null) =
        RelayRetention.Context(local, peers, applied, cutoffs, active, cloud)

    @Test fun ownChangesStayWhileThisDeviceIsAlone() {
        assertTrue(RelayRetention.keep(held("phone", 1), context(peers = emptySet())))
        assertFalse(RelayRetention.keep(held("pc", 1), context(peers = emptySet(), active = setOf("phone", "pc"))))
    }

    @Test fun aChangeStaysUntilEveryOtherActivePeerAppliedIt() {
        val applied = mapOf("pc" to mapOf("phone" to 5L), "tablet" to mapOf("phone" to 3L))
        assertFalse(RelayRetention.keep(held("phone", 3), context(applied = applied)))
        assertTrue(RelayRetention.keep(held("phone", 4), context(applied = applied)))
        assertTrue(RelayRetention.keep(held("phone", 6), context(applied = applied)))
    }

    @Test fun foreignChangesNoLongerStayForever() {
        // The author itself never needs its own change; the tablet applied it.
        val applied = mapOf("tablet" to mapOf("pc" to 9L))
        assertFalse(RelayRetention.keep(held("pc", 9), context(applied = applied)))
        assertTrue(RelayRetention.keep(held("pc", 10), context(applied = applied)))
    }

    @Test fun anOfflinePeerWithoutProgressPinsEverything() {
        val applied = mapOf("pc" to mapOf("phone" to 100L, "tablet" to 100L))
        assertTrue(RelayRetention.keep(held("phone", 1), context(applied = applied)))
        assertTrue(RelayRetention.keep(held("pc", 1), context(applied = applied)))
    }

    @Test fun driveSyncKeepsWhatTheDriveDoesNotHoldYet() {
        val applied = mapOf("pc" to mapOf("phone" to 10L, "tablet" to 10L), "tablet" to mapOf("phone" to 10L))
        assertTrue(RelayRetention.keep(held("phone", 8), context(applied = applied, cloud = mapOf("phone" to 7L))))
        assertFalse(RelayRetention.keep(held("phone", 7), context(applied = applied, cloud = mapOf("phone" to 7L))))
        // Foreign authors too: the drive needs a gap-free history of every author.
        assertTrue(RelayRetention.keep(held("tablet", 4), context(applied = applied, cloud = emptyMap())))
        assertFalse(RelayRetention.keep(held("tablet", 4), context(applied = applied)))
    }

    @Test fun aRemovedAuthorsChangesStayOnlyUpToItsCutoff() {
        val cutoff = SyncChainHead(5, hash("old", 5))
        val ctx = context(cutoffs = mapOf("old" to cutoff), active = setOf("phone", "pc", "tablet"))
        assertTrue(RelayRetention.keep(held("old", 5), ctx))
        assertFalse(RelayRetention.keep(held("old", 6), ctx))
        assertFalse(RelayRetention.keep(RelayRetention.Held("old", 5, "f".repeat(64)), ctx))
        // Without a cutoff a removed author's changes are never relayed.
        assertFalse(RelayRetention.keep(held("gone", 1), context(active = setOf("phone", "pc", "tablet"))))
    }

    @Test fun keepIsMonotoneSoTheFolderHoldsAGapFreeTail() {
        val applied = mapOf("pc" to mapOf("tablet" to 40L), "tablet" to mapOf("pc" to 12L))
        val ctx = context(applied = applied, cloud = mapOf("pc" to 20L, "tablet" to 30L))
        for (author in listOf("pc", "tablet")) {
            val kept = (1L..60L).map { RelayRetention.keep(held(author, it), ctx) }
            assertEquals("$author kept set is a tail", kept.dropWhile { !it }.all { it }, true)
        }
    }

    @Test fun gapFreeSelectionStopsEachAuthorAtTheFirstMissingChange() {
        val links = listOf(1L, 2L, 3L, 5L, 6L).map { ChainLink("pc", it, hash("pc", it)) } +
            listOf(7L, 8L).map { ChainLink("tablet", it, hash("tablet", it)) }
        val selected = RelayRetention.gapFree(links, mapOf("pc" to 1L, "tablet" to 6L), { true }, 100)
        assertEquals(listOf("pc" to 2L, "pc" to 3L, "tablet" to 7L, "tablet" to 8L), selected.map { it.deviceId to it.sequence })
        // A peer below this device's oldest held change gets nothing of that author from here.
        assertEquals(emptyList<ChainLink>(), RelayRetention.gapFree(links.drop(5), mapOf("tablet" to 2L), { true }, 100))
        // A refused change ends that author's run as a gap does; other authors go on.
        assertEquals(listOf("tablet" to 7L, "tablet" to 8L),
            RelayRetention.gapFree(links, mapOf("tablet" to 6L), { it.deviceId != "pc" || it.sequence > 1 }, 100)
                .map { it.deviceId to it.sequence })
        assertEquals(2, RelayRetention.gapFree(links, emptyMap(), { true }, 2).size)
    }

    @Test fun relayTopIsTheHighestHeldFileOrTheRaisedFloor() {
        val top = RelayRetention.relayTop(listOf(held("pc", 4), held("pc", 9), held("phone", 2)), mapOf("pc" to 12L, "tablet" to 3L))
        assertEquals(mapOf("pc" to 12L, "phone" to 2L, "tablet" to 3L), top)
        assertEquals(mapOf("pc" to 9L, "tablet" to 3L),
            RelayRetention.raiseFloor(mapOf("pc" to 2L, "tablet" to 3L), listOf(held("pc", 9), held("pc", 7), held("tablet", 1))))
    }

    @Test fun oldResendsAreAcknowledgedAndForksAtTheHeadRefused() {
        val received = mapOf("pc" to SyncChainHead(40, "d".repeat(64)))
        assertEquals(IncomingChain.Admission.OLD_RESEND,
            IncomingChain.admit(ChainLink("pc", 12, "e".repeat(64), "c".repeat(64)), received, emptySet()))
        assertEquals(IncomingChain.Admission.ALREADY_STORED,
            IncomingChain.admit(ChainLink("pc", 40, "d".repeat(64), "c".repeat(64)), received, emptySet()))
        assertThrows(IllegalArgumentException::class.java) {
            IncomingChain.admit(ChainLink("pc", 40, "e".repeat(64), "c".repeat(64)), received, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingChain.admit(ChainLink("pc", 42, "e".repeat(64), "c".repeat(64)), received, emptySet())
        }
    }
}
