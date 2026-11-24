package com.privatevault.app.ui

import com.privatevault.app.R
import com.privatevault.app.StatusKind
import com.privatevault.app.SyncWords
import com.privatevault.app.peerAckText
import com.privatevault.app.sync.PeerAck
import com.privatevault.app.sync.PeerSyncFacts
import com.privatevault.app.sync.SyncFacts
import com.privatevault.app.sync.SyncOverviewRules
import com.privatevault.app.sync.TransportHealth
import com.privatevault.app.syncOverviewText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** The words the status card shows, read from the real strings.xml (the same words as Windows `syncStatusText`). */
class SyncOverviewTextTest {
    private val now = 1_790_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private val words = object : SyncWords {
        private val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.exists() })
        private fun clean(text: String) = text.replace("\\'", "'")
        private val strings = xml.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to clean(nodes.item(it).textContent) }
        }
        private val plurals = xml.getElementsByTagName("plurals").let { nodes ->
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                val items = node.childNodes.let { children -> (0 until children.length).map { children.item(it) }
                    .filter { it.nodeName == "item" }.associate { it.attributes.getNamedItem("quantity").nodeValue to clean(it.textContent) } }
                node.attributes.getNamedItem("name").nodeValue to items
            }
        }
        private fun name(type: Class<*>, id: Int) = type.fields.first { it.getInt(null) == id }.name
        override fun text(id: Int, vararg args: Any) =
            String.format(Locale.US, strings.getValue(name(R.string::class.java, id)), *args)
        override fun plural(id: Int, count: Int, vararg args: Any) = String.format(Locale.US,
            plurals.getValue(name(R.plurals::class.java, id)).getValue(if (count == 1) "one" else "other"), *args)
    }

    private fun peer(name: String, unconfirmed: Long = 0, seenAgo: Long = 5 * minute, wifiFailed: Boolean = false) =
        PeerSyncFacts("id-$name", name, unconfirmed, true, now - seenAgo, wifiFailed)
    private fun text(facts: SyncFacts) = syncOverviewText(facts, SyncOverviewRules.overview(facts), words)

    @Test fun wifiFailedDriveOkReadsAsSynced() {
        val result = text(SyncFacts(now, listOf(peer("Pixel 8", wifiFailed = true)), wifi = TransportHealth.FAILED,
            drive = TransportHealth.OK, lastDriveSync = now - minute))
        assertEquals(StatusKind.SUCCESS, result.kind)
        assertEquals("Up to date", result.title)
        assertEquals("Every device has your latest changes.", result.detail)
        assertEquals("Wi-Fi couldn't reach Pixel 8. Google Drive kept it in sync.", result.note)
        assertEquals("Last synced 1 minute ago", result.lastSynced)
        val two = text(SyncFacts(now, listOf(peer("Pixel 8"), peer("Tablet")), wifi = TransportHealth.FAILED,
            drive = TransportHealth.OK))
        assertEquals("Wi-Fi couldn't reach your devices. Google Drive kept them in sync.", two.note)
    }

    @Test fun waitingNamesTheDeviceAndTheChanges() {
        val one = text(SyncFacts(now, listOf(peer("Pixel 8", unconfirmed = 3), peer("Tablet"))))
        assertEquals(StatusKind.INFO, one.kind)
        assertEquals("Waiting for Pixel 8", one.title)
        assertEquals("Pixel 8 hasn't confirmed 3 changes yet.", one.detail)
        val many = text(SyncFacts(now, listOf(peer("Pixel 8", unconfirmed = 1), peer("Tablet", unconfirmed = 1))))
        assertEquals("Waiting for 2 devices", many.title)
        assertEquals("1 change isn't on every device yet.", many.detail)
    }

    @Test fun errorsKeepTheirHeadline() {
        val both = text(SyncFacts(now, listOf(peer("Pixel 8")), wifi = TransportHealth.FAILED, drive = TransportHealth.FAILED))
        assertEquals(StatusKind.ERROR, both.kind)
        assertEquals("Sync needs attention", both.title)
        assertEquals("Couldn't reach your devices over Wi-Fi or Google Drive.", both.detail)
        assertNull(both.note)
        val silent = text(SyncFacts(now, listOf(peer("Tablet", unconfirmed = 2, seenAgo = 4 * day))))
        assertEquals("Tablet hasn't synced in 4 days and doesn't have your latest changes.", silent.detail)
        val only = text(SyncFacts(now, listOf(peer("Pixel 8", unconfirmed = 1)), wifi = TransportHealth.FAILED))
        assertEquals("Wi-Fi couldn't reach Pixel 8. Your changes wait until it connects.", only.detail)
        assertNull("the failure is the headline, not a second line", only.note)
        assertEquals("2 incoming changes couldn't apply. Update both devices, then retry.",
            text(SyncFacts(now, listOf(peer("Pixel 8")), rejected = 2)).detail)
    }

    @Test fun staleAndQuietNotes() {
        val stale = text(SyncFacts(now, listOf(peer("Pixel 8", seenAgo = 5 * day)), drive = TransportHealth.OK,
            lastDriveSync = now - minute))
        assertEquals(StatusKind.WARNING, stale.kind)
        assertEquals("Devices haven't synced in 5 days", stale.title)
        assertEquals("Open Nuvori on your other device so it syncs with Google Drive.", stale.detail)
        assertEquals("Google Drive didn't finish. Wi-Fi kept your devices in sync.",
            text(SyncFacts(now, listOf(peer("Pixel 8")), drive = TransportHealth.FAILED)).note)
        assertEquals("Automatic sync is paused. Sync now still checks once.",
            text(SyncFacts(now, listOf(peer("Pixel 8")), wifi = TransportHealth.OFF, paused = true)).note)
    }

    @Test fun deviceCardWords() {
        assertEquals("Up to date", peerAckText(peer("Pixel 8"), PeerAck.UP_TO_DATE, now, words).label)
        val waiting = peerAckText(peer("Pixel 8", unconfirmed = 1), PeerAck.WAITING, now, words)
        assertEquals("Waiting", waiting.label)
        assertEquals("Waiting for Pixel 8 to confirm 1 change.", waiting.detail)
        val silent = peerAckText(peer("Tablet", unconfirmed = 2, seenAgo = 6 * day), PeerAck.SILENT, now, words)
        assertEquals(StatusKind.ERROR, silent.kind)
        assertEquals("Hasn't synced in 6 days. Open Nuvori on it to catch up.", silent.detail)
    }
}
