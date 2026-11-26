package com.privatevault.app.sync

import android.net.ConnectivityManager
import android.net.Network
import com.privatevault.app.data.SyncMembershipEntity
import java.net.ConnectException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.ServerSocket

internal data class LocalSyncRoute(val address: InetAddress, val prefixLength: Int, val network: Network?) {
    fun contains(peer: InetAddress): Boolean {
        val local = address.address
        val remote = peer.address
        if (local.size != remote.size || prefixLength !in 1..local.size * 8) return false
        return local.indices.all { index ->
            val bits = (prefixLength - index * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            (local[index].toInt() and mask) == (remote[index].toInt() and mask)
        }
    }
}

/** Hotspot interfaces need the system's local route, not the upstream Wi-Fi Network. */
internal fun localSyncRoutes(manager: ConnectivityManager): List<LocalSyncRoute> {
    val networks = (listOfNotNull(manager.activeNetwork) + manager.allNetworks).distinct()
    val properties = networks.associateWith { manager.getLinkProperties(it) }
    val wifi = networks.filter { isLocalWifi(manager.getNetworkCapabilities(it)) }
        .flatMap { network -> properties[network]?.linkAddresses.orEmpty().mapNotNull {
            if (isPrivateAddress(it.address) && !it.address.isLinkLocalAddress)
                LocalSyncRoute(it.address, it.prefixLength, network) else null
        } }
    val managedInterfaces = properties.values.mapNotNull { it?.interfaceName }.toSet()
    val local = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && it.name !in managedInterfaces }
            .flatMap { it.interfaceAddresses }.mapNotNull {
                if (it.broadcast != null && it.address.address.size == 4 &&
                    isPrivateAddress(it.address) && !it.address.isLinkLocalAddress)
                    LocalSyncRoute(it.address, it.networkPrefixLength.toInt(), null) else null
            }
    }.getOrDefault(emptyList())
    // Prefer the hosted LAN for a new QR. Existing peers select their matching subnet below.
    return (local + wifi).sortedBy { if (it.address.address.size == 4) 0 else 1 }
}

internal fun routeToPeer(routes: List<LocalSyncRoute>, peer: InetAddress): LocalSyncRoute? =
    routes.filter { it.contains(peer) }.maxByOrNull { it.prefixLength }
        ?: routes.firstOrNull { it.network != null && it.address.address.size == peer.address.size }

internal fun bindSyncSocket(manager: ConnectivityManager, socket: Socket, peer: InetAddress) {
    val route = routeToPeer(localSyncRoutes(manager), peer)
        ?: throw ConnectException("No local network route to the paired device")
    if (route.network != null) route.network.bindSocket(socket)
    else socket.bind(InetSocketAddress(route.address, 0))
}

/**
 * L5: the Wi-Fi ports of a sync group, the same as on Windows (`lan_sync.rs`); the rule and its
 * vectors are in `scripts/sync-vectors/sync_ports.py` (`sync-ports-v2.json`). A group listens on
 * a port in 20000-32767, below the ports Android (32768 and up) and Windows (49152 and up) hand out
 * for outgoing connections, so another app is far less likely to hold it.
 */
internal object SyncPorts {
    /** Announced by builds that listen in 20000-32767 and try a device's other ports when one refuses. */
    const val FEATURE = "sync-ports-v2"
    private const val FIRST = 20_000
    private const val COUNT = 12_768
    /** How many ports older versions spread groups over (20000 to 49999). */
    private const val LEGACY_COUNT = 30_000

    /** The port this group listens on and connects to first. */
    fun port(vaultId: String): Int = FIRST + Math.floorMod(vaultId.hashCode(), COUNT)

    /**
     * The port the listener takes when another app holds [port]: half the range away, the same on
     * every start. Devices find it through discovery and remember the port they reached.
     */
    fun second(port: Int): Int = FIRST + Math.floorMod(port - FIRST + COUNT / 2, COUNT)

    /** The port versions before [FEATURE] listen on and connect to. */
    fun legacy(vaultId: String): Int = FIRST + Math.floorMod(vaultId.hashCode(), LEGACY_COUNT)

    /**
     * The ports to try at a saved address, in order: the one this device last reached it on, then
     * this group's port, its second port and the port older versions use. The next is tried only
     * when the device refused the connection ([connectFirst]).
     */
    fun targets(remembered: Int?, vaultId: String): List<Int> =
        (listOfNotNull(remembered) + listOf(port(vaultId), second(port(vaultId)), legacy(vaultId))).distinct()

    /**
     * Whether to also listen on [legacy]: some other active member hasn't announced [FEATURE] (or
     * announced nothing yet, like a device that just joined). Once every device announced it, the
     * older port closes and this phone listens on one port.
     */
    fun legacyNeeded(localDeviceId: String, members: List<SyncMembershipEntity>,
        peerFeatures: Map<String, List<String>>): Boolean =
        members.any { it.status == MemberStatus.ACTIVE.name && it.deviceId != localDeviceId &&
            FEATURE !in peerFeatures[it.deviceId].orEmpty() }
}

/** L5: [SyncPorts.second], kept for the listener. */
internal fun fallbackSyncPort(port: Int): Int = SyncPorts.second(port)

/**
 * L5: the first of [ports] at [address] that accepts, trying the next only when the last refused
 * (a device that doesn't answer costs one timeout, as before). [open] makes each new socket ready,
 * for example bound to the network that reaches the device. Every port is on the same address, and
 * the session still proves membership before anything is exchanged.
 */
internal fun connectFirst(address: InetAddress, ports: List<Int>, timeoutMs: Int, open: (Socket) -> Unit): Socket {
    var refused: Exception = ConnectException("No port to try")
    for (port in ports) {
        val socket = Socket()
        try {
            open(socket)
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            return socket
        } catch (error: ConnectException) {
            runCatching { socket.close() }
            refused = error
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }
    throw refused
}

internal fun openSyncListener(port: Int): ServerSocket {
    for (candidate in listOf(port, fallbackSyncPort(port))) {
        val listener = ServerSocket().apply { reuseAddress = true }
        try {
            listener.bind(InetSocketAddress(candidate))
            return listener.apply { soTimeout = 1000 }
        } catch (_: BindException) { listener.close() }
    }
    return ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(0))
        soTimeout = 1000
    }
}
