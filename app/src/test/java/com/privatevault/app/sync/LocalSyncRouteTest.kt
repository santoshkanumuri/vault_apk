package com.privatevault.app.sync

import android.net.Network
import android.net.ConnectivityManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class LocalSyncRouteTest {
    private val wifi = mock(Network::class.java)
    private fun route(ip: String, prefix: Int, network: Network? = null) =
        LocalSyncRoute(InetAddress.getByName(ip), prefix, network)

    @Test fun hotspotPeerDoesNotUseUpstreamWifi() {
        val upstream = route("192.168.1.20", 24, wifi)
        val hotspot = route("192.168.43.1", 24)
        assertSame(hotspot, routeToPeer(listOf(upstream, hotspot), InetAddress.getByName("192.168.43.10")))
        assertSame(upstream, routeToPeer(listOf(hotspot, upstream), InetAddress.getByName("192.168.1.30")))
    }

    @Test fun hotspotWorksWithoutAWifiClientNetwork() {
        val hotspot = route("192.168.43.1", 24)
        assertSame(hotspot, routeToPeer(listOf(hotspot), InetAddress.getByName("192.168.43.10")))
        assertNull(routeToPeer(listOf(hotspot), InetAddress.getByName("192.168.2.10")))
    }

    @Test fun discoversHotspotWithoutAConnectivityManagerNetworkAndIgnoresNonLanAddresses() {
        val manager = mock(ConnectivityManager::class.java)
        `when`(manager.allNetworks).thenReturn(emptyArray())
        fun networkInterface(name: String, ip: String, broadcast: String?): NetworkInterface {
            val address = mock(InterfaceAddress::class.java)
            `when`(address.address).thenReturn(InetAddress.getByName(ip))
            `when`(address.networkPrefixLength).thenReturn(24.toShort())
            `when`(address.broadcast).thenReturn(broadcast?.let(InetAddress::getByName))
            return mock(NetworkInterface::class.java).apply {
                `when`(this.name).thenReturn(name)
                `when`(isUp).thenReturn(true)
                `when`(interfaceAddresses).thenReturn(listOf(address))
            }
        }
        val hotspot = networkInterface("ap0", "192.168.43.1", "192.168.43.255")
        val cellular = networkInterface("rmnet0", "10.1.2.3", null)
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<java.util.Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                .thenReturn(Collections.enumeration(listOf(cellular, hotspot)))
            assertEquals(listOf(route("192.168.43.1", 24)), localSyncRoutes(manager))
        }
    }

    @Test fun routedLanUsesWifiWhenNoDirectSubnetMatches() {
        val upstream = route("10.0.0.2", 24, wifi)
        assertSame(upstream, routeToPeer(listOf(upstream), InetAddress.getByName("10.1.0.2")))
    }

    @Test fun subnetMatchingHandlesIpv6AndNonBytePrefixes() {
        assertTrue(route("172.30.80.1", 20).contains(InetAddress.getByName("172.30.95.255")))
        assertFalse(route("172.30.80.1", 20).contains(InetAddress.getByName("172.30.96.1")))
        assertTrue(route("fd00:1::1", 64).contains(InetAddress.getByName("fd00:1::2")))
        assertFalse(route("fd00:1::1", 64).contains(InetAddress.getByName("fd00:2::2")))
        assertFalse(route("fd00:1::1", 64).contains(InetAddress.getByName("192.168.43.10")))
    }

    @Test fun syncListenerAcceptsAnyLocalInterfaceAndReusesItsPortAfterAnExchange() {
        val port = openSyncListener(0).use { listener ->
            assertTrue(listener.inetAddress.isAnyLocalAddress)
            Socket("127.0.0.1", listener.localPort).use { client ->
                listener.accept().use { server ->
                    server.getOutputStream().write(42)
                    assertEquals(42, client.getInputStream().read())
                }
            }
            listener.localPort
        }
        openSyncListener(port).use { assertEquals(port, it.localPort) }
    }
}
