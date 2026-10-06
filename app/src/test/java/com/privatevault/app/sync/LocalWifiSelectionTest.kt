package com.privatevault.app.sync

import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.net.InetAddress

class LocalWifiSelectionTest {
    @Test fun allPrivateDiscoveredAddressesAreRetainedWithIpv4First() {
        val virtual = InetAddress.getByName("172.24.16.1")
        val lan = InetAddress.getByName("192.168.1.20")
        val ipv6 = InetAddress.getByName("fd00::2")
        assertEquals(listOf(virtual, lan, ipv6), peerSyncAddresses(listOf(ipv6, virtual, lan, lan,
            InetAddress.getByName("127.0.0.1"), InetAddress.getByName("169.254.1.2"),
            InetAddress.getByName("8.8.8.8"))))
    }

    @Test fun physicalWifiCanSupplyThePairingAddressAndSyncRoute() {
        val wifi = mock(NetworkCapabilities::class.java)
        `when`(wifi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        assertTrue(isLocalWifi(wifi))
    }

    @Test fun vpnOverWifiCannotSupplyThePairingAddressOrSyncRoute() {
        val vpn = mock(NetworkCapabilities::class.java)
        `when`(vpn.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        `when`(vpn.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        assertFalse(isLocalWifi(vpn))
    }

    @Test fun cellularAndUnavailableNetworksAreNotLocalWifi() {
        val cellular = mock(NetworkCapabilities::class.java)
        `when`(cellular.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true)
        assertFalse(isLocalWifi(cellular))
        assertFalse(isLocalWifi(null))
    }
}
