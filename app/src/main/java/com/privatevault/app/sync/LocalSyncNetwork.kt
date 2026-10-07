package com.privatevault.app.sync

import android.net.ConnectivityManager
import android.net.Network
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

internal fun openSyncListener(port: Int): ServerSocket = ServerSocket().apply {
    reuseAddress = true
    try { bind(InetSocketAddress(port)) }
    catch (_: BindException) { bind(InetSocketAddress(0)) }
    soTimeout = 1000
}
