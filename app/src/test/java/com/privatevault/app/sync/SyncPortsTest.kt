package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket

/** L5, rule `sync-ports-v2`. The vector file is byte-identical in the Windows repository. */
class SyncPortsTest {
    private val vectors = JSONObject(requireNotNull(javaClass.classLoader?.getResource("sync-vectors/sync-ports-v2.json"))
        .readText(Charsets.UTF_8))

    @Test fun portsMatchTheSharedVectors() {
        assertEquals("sync-ports-v2", vectors.getString("rule"))
        val cases = vectors.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val group = case.getString("group")
            assertEquals(group, case.getInt("hash"), group.hashCode())
            assertEquals(group, case.getInt("port"), SyncPorts.port(group))
            assertEquals(group, case.getInt("second"), SyncPorts.second(SyncPorts.port(group)))
            assertEquals(group, case.getInt("legacy"), SyncPorts.legacy(group))
            assertTrue(SyncPorts.port(group) in 20_000..32_767 && SyncPorts.second(SyncPorts.port(group)) in 20_000..32_767)
        }
        assertEquals(SyncPorts.port("android-vault"), LanSyncService.syncPort("android-vault"))
    }

    @Test fun aSavedAddressTriesItsOwnPortThenThisGroupsPortsThenTheOlderOne() {
        assertEquals(listOf(41_000, 25_364, 31_748, 23_492), SyncPorts.targets(41_000, "android-vault"))
        assertEquals(listOf(25_364, 31_748, 23_492), SyncPorts.targets(null, "android-vault"))
        assertEquals(listOf(25_364, 31_748, 23_492), SyncPorts.targets(25_364, "android-vault"))
        // An empty group ID has the same new and old port: it is tried once.
        assertEquals(listOf(20_000, 26_384), SyncPorts.targets(null, ""))
    }

    @Test fun aRefusedPortMovesOnToTheNext() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val closed = ServerSocket(0, 1, loopback).use { it.localPort }
        ServerSocket(0, 1, loopback).use { open ->
            var prepared = 0
            connectFirst(loopback, listOf(closed, open.localPort), 2000) { prepared++ }.use { socket ->
                assertEquals(open.localPort, socket.port)
            }
            assertEquals(2, prepared)
        }
        assertTrue(runCatching { connectFirst(loopback, listOf(closed), 2000) {} }.exceptionOrNull() is ConnectException)
    }

    @Test fun theOlderPortClosesOnceEveryDeviceAnnouncedTheNewPorts() {
        fun member(id: String, status: MemberStatus = MemberStatus.ACTIVE) =
            SyncMembershipEntity(vaultId = "v", deviceId = id, displayName = id, identityPublicKey = "",
                status = status.name, addedByDeviceId = "", membershipSequence = 1, keyEpoch = 0)
        val members = listOf(member("me"), member("pc"), member("old", MemberStatus.REVOKED))
        assertFalse("a lone device needs no second port", SyncPorts.legacyNeeded("me", listOf(member("me")), emptyMap()))
        assertTrue("nothing announced yet", SyncPorts.legacyNeeded("me", members, emptyMap()))
        assertTrue("an older PC", SyncPorts.legacyNeeded("me", members, mapOf("pc" to listOf(RemovalCutoff.FEATURE))))
        assertFalse("every active device is new", SyncPorts.legacyNeeded("me", members, mapOf("pc" to SyncFeatures.ANNOUNCED)))
        assertTrue(SyncFeatures.SYNC_PORTS in SyncFeatures.ANNOUNCED)
    }
}
