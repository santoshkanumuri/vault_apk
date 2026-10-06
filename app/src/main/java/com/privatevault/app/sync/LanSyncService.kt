package com.privatevault.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.ext.SdkExtensions
import com.privatevault.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.BindException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DeviceSyncPhase(val title: String) {
    OFF("Device sync is off"),
    PAUSED("Sync paused"),
    WAITING("Waiting for Wi-Fi"),
    SEARCHING("Looking for your devices"),
    FOUND("Device found nearby"),
    CONNECTING("Verifying device"),
    TRANSFERRING("Syncing changes"),
    RECEIVED("Changes still waiting"),
    CHECKED("Last check complete"),
    ATTENTION("Sync needs attention")
}

data class DeviceSyncStatus(val phase: DeviceSyncPhase, val detail: String)
data class DevicePeerStatus(val phase: DeviceSyncPhase, val detail: String)

/** Owns only transport keys and ciphertext. Opening the vault is the UI's responsibility. */
class LanSyncService : Service() {
    private data class Endpoint(val address: InetAddress, val port: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val endpoints = ConcurrentHashMap<String, List<Endpoint>>()
    @Volatile private var peerBusy = false
    private val discovered = ConcurrentHashMap<String, NsdServiceInfo>()
    private val lastAuthenticated = ConcurrentHashMap<String, Long>()
    private val exchange = Mutex()
    private lateinit var notificationManager: NotificationManager
    @Volatile private var pairedCount = 0
    private var lastNotificationText = ""
    private lateinit var connectivity: ConnectivityManager
    private lateinit var nsd: NsdManager
    private var networkJob: Job? = null
    @Volatile private var activeNetwork: Network? = null
    private var activeAddress: InetAddress? = null
    private var server: ServerSocket? = null
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var manualStop: Job? = null
    @Volatile private var manualAttempt = false
    @Volatile private var manualCompleted = false
    @Volatile private var manualProgressAt = 0L
    private val instanceName = "nuvori-" + UUID.randomUUID().toString()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connect(network)
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            connect(network)
        }
        private fun connect(network: Network) {
            scope.launch(Dispatchers.Main.immediate) {
                if (!isLocalWifi(connectivity.getNetworkCapabilities(network))) return@launch
                val address = preferredWifiAddress(connectivity.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }.orEmpty()) ?: return@launch
                if (activeNetwork == null || activeNetwork == network && activeAddress != address) {
                    activeNetwork = network
                    activeAddress = address
                    setNearbyCount(0)
                    setStatus(DeviceSyncPhase.SEARCHING, "Wi-Fi changed. Restarting device discovery.")
                    val previous = networkJob
                    previous?.cancel()
                    server?.close()
                    connections.forEach { runCatching { it.close() } }
                    networkJob = scope.launch {
                        previous?.join()
                        while (isActive) { listen(network); delay(5_000) }
                    }
                }
            }
        }
        override fun onLost(network: Network) {
            scope.launch(Dispatchers.Main.immediate) {
                if (activeNetwork == network) {
                    activeNetwork = null
                    activeAddress = null
                    setNearbyCount(0)
                    setStatus(DeviceSyncPhase.WAITING, "Wi-Fi disconnected. Reconnect both devices to the same network.")
                    clearConnectedPeers("Wi-Fi disconnected.")
                    networkJob?.cancel()
                    server?.close()
                    connections.forEach { runCatching { it.close() } }
                    connectivity.allNetworks.filter { it != network &&
                        isLocalWifi(connectivity.getNetworkCapabilities(it)) }
                        .forEach(::connect)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        mutablePeerStatus.value = emptyMap()
        mutableNearbyCount.value = 0
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "Device sync", NotificationManager.IMPORTANCE_LOW))
        notificationManager.cancel(PAUSED_NOTIFICATION_ID)
        startForeground(NOTIFICATION_ID, syncNotification())
        setStatus(DeviceSyncPhase.WAITING, "Connect both devices to the same Wi-Fi network.")
        connectivity = getSystemService(ConnectivityManager::class.java)
        nsd = getSystemService(NsdManager::class.java)
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    }

    private fun syncNotification(): Notification {
        val connected = mutablePeerStatus.value.values.count { it.phase == DeviceSyncPhase.TRANSFERRING }
        val nearby = mutableNearbyCount.value
        val paused = isPaused(this)
        val action = if (paused) resumePendingIntent(this) else
            PendingIntent.getService(this, 1, Intent(this, LanSyncService::class.java).setAction(PAUSE),
                PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.privatevault.app.R.drawable.ic_auto_sync)
            .setContentTitle("$connected connected · $nearby nearby")
            .setContentText("$pairedCount paired · ${mutableStatus.value.phase.title}")
            .setContentIntent(settingsPendingIntent(this)).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", action).build())
            .build()
    }

    @Synchronized private fun updateNotification() {
        if (activeInstance !== this || !::notificationManager.isInitialized) return
        val connected = mutablePeerStatus.value.values.count { it.phase == DeviceSyncPhase.TRANSFERRING }
        val text = "$connected|${mutableNearbyCount.value}|$pairedCount|${mutableStatus.value.phase}"
        if (text == lastNotificationText) return
        lastNotificationText = text
        notificationManager.notify(NOTIFICATION_ID, syncNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            pause(this)
            return START_NOT_STICKY
        }
        if (intent?.action == RESUME) {
            allowFutureSync(this)
            notificationManager.cancel(PAUSED_NOTIFICATION_ID)
        }
        val paused = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false)
        if (paused && intent?.action != SYNC_NOW) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mirror = runCatching { store(this).snapshot() }.getOrNull()
        val activeIds = mirror?.members?.filter { it.status == MemberStatus.ACTIVE.name }
            ?.mapTo(hashSetOf()) { it.deviceId }.orEmpty()
        val activeMembers = activeIds.size
        val localIsActive = mirror?.localDeviceId in activeIds
        pairedCount = if (localIsActive) (activeMembers - 1).coerceAtLeast(0) else 0
        mutablePeerStatus.update { states -> states.filterKeys { it in activeIds } }
        updateNotification()
        val membershipWork = runCatching { store(this).hasMembershipWork() }.getOrDefault(false)
        if ((!localIsActive || activeMembers < 2) && !membershipWork) {
            setStatus(DeviceSyncPhase.OFF, if (mirror != null && !localIsActive)
                "This device is no longer in the active sync group."
                else "Pair a device to start syncing.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != SYNC_NOW && !paused) {
            manualAttempt = false
            manualStop?.cancel()
        }
        if (intent?.action == SYNC_NOW && (paused || !getSystemService(NotificationManager::class.java).areNotificationsEnabled())) {
            manualAttempt = true
            manualCompleted = false
            manualProgressAt = android.os.SystemClock.elapsedRealtime()
            manualStop?.cancel()
            manualStop = scope.launch {
                while (!manualCompleted) {
                    delay(1_000)
                    if (exchange.isLocked ||
                        android.os.SystemClock.elapsedRealtime() - manualProgressAt < 35_000) continue
                    if (mutableStatus.value.phase in setOf(DeviceSyncPhase.WAITING,
                            DeviceSyncPhase.SEARCHING, DeviceSyncPhase.FOUND, DeviceSyncPhase.CONNECTING))
                        setStatus(DeviceSyncPhase.ATTENTION,
                            "No paired device answered. Check Wi-Fi on both devices and try again.")
                    stopSelf()
                    break
                }
            }
        }
        signals.trySend(Unit)
        return if (manualAttempt) START_NOT_STICKY else START_STICKY
    }

    private suspend fun listen(network: Network) {
        try {
            val address = preferredWifiAddress(connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }.orEmpty()) ?: return
            val mirror = requireNotNull(store(this).snapshot())
            val listener = ServerSocket().apply {
                val preferred = InetSocketAddress(address, syncPort(mirror.vaultId))
                try { bind(preferred) }
                catch (_: BindException) { bind(InetSocketAddress(address, 0)) }
                soTimeout = 1000
            }
            server = listener
            multicastLock = getSystemService(WifiManager::class.java)
                .createMulticastLock("nuvori-sync-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            setStatus(DeviceSyncPhase.SEARCHING, "Checking nearby Nuvori devices on Wi-Fi.")
            startDiscovery(network, listener.localPort)
            coroutineScope {
                var retryDelayMs = 5_000L
                launch {
                    while (isActive) {
                        val socket = try { listener.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                        if (!isPrivateAddress(socket.inetAddress)) { socket.close(); continue }
                        if (!exchange.tryLock()) {
                            socket.use {
                                runCatching { SyncFrames(socket.getInputStream(), socket.getOutputStream())
                                    .send(MembershipWire.BUSY.toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME) }
                            }
                            continue
                        }
                        launch {
                            setStatus(DeviceSyncPhase.CONNECTING, "A device answered. Checking that it belongs to your vault.")
                            try { transfer(socket, true) } finally { exchange.unlock() }
                        }
                    }
                }
                while (isActive) {
                    var needsRetry = false
                    peerBusy = false
                    val passStartedAt = android.os.SystemClock.elapsedRealtime()
                    val current = store(this@LanSyncService).snapshot() ?: mirror
                    require(current.vaultId == mirror.vaultId)
                    val activePeers = current.members.filter {
                        it.status == MemberStatus.ACTIVE.name && it.deviceId != current.localDeviceId
                    }
                    activePeers.forEach { peer ->
                        if (mutablePeerStatus.value[peer.deviceId]?.phase !in setOf(
                                DeviceSyncPhase.ATTENTION, DeviceSyncPhase.TRANSFERRING))
                            setPeerStatus(peer.deviceId, DeviceSyncPhase.SEARCHING,
                                "Looking for this device on Wi-Fi.")
                    }
                    current.peerAddresses.forEach { (deviceId, hint) ->
                        if (current.members.none { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name } ||
                            lastAuthenticated[deviceId]?.let {
                                it >= passStartedAt
                            } == true)
                            return@forEach
                        val peerAddress = runCatching { android.net.InetAddresses.parseNumericAddress(hint) }
                            .getOrNull() ?: return@forEach
                        if (!isPrivateAddress(peerAddress) || peerAddress.isLinkLocalAddress) return@forEach
                        // Prefer a confirmed address; discovery remains a fallback if its address or port changed.
                        exchange.withLock {
                            val socket = Socket()
                            connections.add(socket)
                            try {
                                setStatus(DeviceSyncPhase.CONNECTING, "Trying the last confirmed Wi-Fi address.")
                                setPeerStatus(deviceId, DeviceSyncPhase.CONNECTING, "Trying its saved Wi-Fi address.")
                                network.bindSocket(socket)
                                socket.connect(InetSocketAddress(peerAddress, syncPort(mirror.vaultId)), 3000)
                                if (!transfer(socket, false)) {
                                    needsRetry = true
                                    if (mutablePeerStatus.value[deviceId]?.phase == DeviceSyncPhase.CONNECTING)
                                        setPeerStatus(deviceId, DeviceSyncPhase.SEARCHING,
                                            "Saved address did not complete a check. Looking on Wi-Fi.")
                                }
                            } catch (_: Exception) {
                                needsRetry = true
                                setStatus(DeviceSyncPhase.SEARCHING,
                                    "Saved address did not answer. Looking for this device on Wi-Fi.")
                                setPeerStatus(deviceId, DeviceSyncPhase.SEARCHING,
                                    "Saved address did not answer. Looking on Wi-Fi.")
                            } finally { connections.remove(socket); socket.close() }
                        }
                    }
                    for (info in discovered.values.toList()) {
                        if (!endpoints.containsKey(info.serviceName)) {
                            val resolved = resolve(info)
                            if (resolved == null) {
                                needsRetry = true
                                setStatus(DeviceSyncPhase.SEARCHING,
                                    "Found Nuvori, but its Wi-Fi address is still being resolved.")
                                continue
                            }
                            val addresses = if (android.os.Build.VERSION.SDK_INT >= 34 ||
                                android.os.Build.VERSION.SDK_INT >= 33 &&
                                SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.TIRAMISU) >= 7)
                                resolved.hostAddresses else listOfNotNull(resolved.host)
                            val peerAddresses = peerSyncAddresses(addresses)
                            if (peerAddresses.isNotEmpty() && resolved.port in 1024..65535)
                                endpoints[resolved.serviceName] = peerAddresses.map { Endpoint(it, resolved.port) }
                            else {
                                needsRetry = true
                                setStatus(DeviceSyncPhase.SEARCHING,
                                    "Found Nuvori, but its Wi-Fi address is unavailable. Retrying discovery.")
                            }
                        }
                    }
                    for ((name, candidates) in endpoints.entries.toList()) {
                        ensureActive()
                        if (current.peerAddresses.any { (deviceId, address) ->
                                candidates.any { address == it.address.hostAddress } &&
                                    lastAuthenticated[deviceId]?.let {
                                        it >= passStartedAt
                                    } == true
                            }) continue
                        // Let either side connect when discovery is one-sided. Stagger the
                        // higher name so two phones do not repeatedly reject each other.
                        if (instanceName > name) delay(1_000 + kotlin.random.Random.nextLong(1_000))
                        var completed = false
                        for (endpoint in candidates) {
                            exchange.withLock {
                                val socket = Socket()
                                connections.add(socket)
                                try {
                                    setStatus(DeviceSyncPhase.CONNECTING, "Verifying the device found on Wi-Fi.")
                                    network.bindSocket(socket)
                                    socket.connect(InetSocketAddress(endpoint.address, endpoint.port), 5000)
                                    completed = transfer(socket, false)
                                } catch (failure: Exception) {
                                    needsRetry = true
                                    setStatus(DeviceSyncPhase.SEARCHING,
                                        "The Wi-Fi address changed or the connection closed. Looking again.")
                                } finally { connections.remove(socket); socket.close() }
                            }
                            if (completed) {
                                endpoints[name] = listOf(endpoint) + candidates.filter { it != endpoint }
                                break
                            }
                        }
                        if (!completed) {
                            needsRetry = true
                            if (!peerBusy) endpoints.remove(name, candidates)
                        }
                    }
                    deliverPendingLeave(network)
                    activePeers.forEach { peer ->
                        if ((lastAuthenticated[peer.deviceId] ?: -1L) < passStartedAt &&
                            mutablePeerStatus.value[peer.deviceId]?.phase in setOf(
                                DeviceSyncPhase.SEARCHING, DeviceSyncPhase.CONNECTING))
                            setPeerStatus(peer.deviceId, DeviceSyncPhase.WAITING,
                                "No reply on this check. Check Wi-Fi and try again.")
                    }
                    val interval = syncInterval(this@LanSyncService)
                    val delayMs = if (peerBusy) 5_000L.coerceAtMost(interval)
                        else if (needsRetry) retryDelayMs.coerceAtMost(interval) else interval
                    retryDelayMs = if (needsRetry && !peerBusy) (retryDelayMs * 2).coerceAtMost(interval) else 5_000L
                    if (withTimeoutOrNull(delayMs + kotlin.random.Random.nextLong(5000)) { signals.receive() } != null) {
                        // Coalesce a burst of saves (or saves during a running exchange) into one pass.
                        delay(FOLLOW_UP_DEBOUNCE_MS)
                        while (signals.tryReceive().isSuccess) Unit
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (mutableStatus.value.phase != DeviceSyncPhase.ATTENTION)
                setStatus(DeviceSyncPhase.SEARCHING, "Wi-Fi discovery restarted. Looking again.")
        }
        finally {
            server?.close(); server = null
            val oldDiscovery = discovery.also { discovery = null }
            oldDiscovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            val oldRegistration = registration.also { registration = null }
            oldRegistration?.let { runCatching { nsd.unregisterService(it) } }
            multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null
            endpoints.clear()
            discovered.clear()
            setNearbyCount(0)
        }
    }

    private fun transfer(socket: Socket, server: Boolean): Boolean {
        connections.add(socket)
        var peerId: String? = null
        val locks = TransferLocks()
        try {
            socket.use {
                // Until the peer proves group membership, keep the exchange lock only briefly.
                socket.soTimeout = PRE_AUTH_TIMEOUT_MS
                LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                    store(this), server) { authenticatedId, platform ->
                    socket.soTimeout = SESSION_TIMEOUT_MS
                    locks.acquire()
                    runCatching { store(this).recordPeerPlatform(authenticatedId, platform) }
                    peerId = authenticatedId
                    lastAuthenticated[authenticatedId] = android.os.SystemClock.elapsedRealtime()
                    if (manualAttempt) manualProgressAt = android.os.SystemClock.elapsedRealtime()
                    store(this).recordPeerContact(authenticatedId, completed = false)
                    if (!socket.inetAddress.isLinkLocalAddress)
                        runCatching { store(this).recordPeerAddress(authenticatedId, socket.inetAddress.hostAddress!!) }
                    setPeerStatus(authenticatedId, DeviceSyncPhase.TRANSFERRING, "Encrypted changes are being exchanged.")
                    setStatus(DeviceSyncPhase.TRANSFERRING, "Connected securely. Sending and receiving encrypted changes.")
                }
                peerId?.let { store(this).recordPeerContact(it, completed = true) }
                when {
                    store(this).missingPhotos().isNotEmpty() -> {
                        setPeerStatus(requireNotNull(peerId), DeviceSyncPhase.RECEIVED, "Photo data is still waiting.")
                        setStatus(DeviceSyncPhase.RECEIVED, "Photo data is still waiting. Keep both devices on Wi-Fi.")
                    }
                    store(this).queuedCount() > 0 -> {
                        setPeerStatus(requireNotNull(peerId), DeviceSyncPhase.RECEIVED, "Changes received; unlock to apply them.")
                        setStatus(DeviceSyncPhase.RECEIVED, "Encrypted changes arrived and are waiting to apply on this device.")
                    }
                    else -> {
                        setPeerStatus(requireNotNull(peerId), DeviceSyncPhase.CHECKED, "Encrypted exchange completed.")
                        setStatus(DeviceSyncPhase.CHECKED,
                            "Encrypted exchange finished. Device cards show what was received and applied.")
                    }
                }
                if (manualAttempt) {
                    manualCompleted = true
                    stopSelf()
                }
            }
            return true
        } catch (_: LanSyncExchange.PeerBusy) {
            peerBusy = true
            if (manualAttempt) manualProgressAt = android.os.SystemClock.elapsedRealtime()
            setStatus(DeviceSyncPhase.SEARCHING, "A device is finishing another sync. Retrying shortly.")
        } catch (_: PhotoStorageFullException) {
            setStatus(DeviceSyncPhase.ATTENTION, "Photo sync needs more free space. Free space, then tap Sync now.")
            peerId?.let { setPeerStatus(it, DeviceSyncPhase.ATTENTION, "Photo transfer needs more free space.") }
        } catch (_: SyncQueueFullException) {
            setStatus(DeviceSyncPhase.ATTENTION,
                "Encrypted changes filled the queue. Unlock this vault to apply them, then sync again.")
            peerId?.let { setPeerStatus(it, DeviceSyncPhase.ATTENTION, "Unlock to apply waiting changes.") }
        } catch (_: LanSyncExchange.BatchLimitReached) {
            if (manualAttempt) manualProgressAt = android.os.SystemClock.elapsedRealtime()
            setStatus(DeviceSyncPhase.RECEIVED, "More changes remain. Another check will continue the transfer.")
            peerId?.let { setPeerStatus(it, DeviceSyncPhase.RECEIVED, "More changes remain; another check is queued.") }
            // The connecting side continues; a Windows PC reconnects for its next batch by itself.
            if (!server) signals.trySend(Unit)
        } catch (removed: LanSyncExchange.RemovedFromGroup) {
            recordRemoval(removed.notice)
            return true
        } catch (_: LanSyncExchange.MembershipCaughtUp) {
            setStatus(DeviceSyncPhase.SEARCHING, "The sync group changed. Reconnecting with the new keys.")
            signals.trySend(Unit)
        } catch (_: LanSyncExchange.MembershipMessageServed) {
            return true
        } catch (_: Exception) {
            setStatus(DeviceSyncPhase.ATTENTION, "The connection closed before the check finished. Retrying on Wi-Fi.")
            peerId?.let { setPeerStatus(it, DeviceSyncPhase.ATTENTION, "Connection closed before the check finished.") }
        }
        finally { connections.remove(socket); locks.release() }
        return false
    }

    private fun recordRemoval(notice: RemovalNotice.Removed) {
        val mirror = runCatching { store(this).snapshot() }.getOrNull()
        val name = mirror?.members?.firstOrNull { it.deviceId == notice.issuerDeviceId }?.displayName
            ?.takeIf { it.isNotBlank() && it != "Android device" && it != "This device" }.orEmpty()
        runCatching {
            store(this).recordRemoval(SyncRemovalNotice(mirror?.vaultId.orEmpty(), notice.issuerDeviceId, name,
                System.currentTimeMillis()))
        }
        mutableRemovalEvents.value = mutableRemovalEvents.value + 1
        setStatus(DeviceSyncPhase.ATTENTION,
            "This device was removed from the sync group. Your items stay on this device.")
        clearConnectedPeers("Removed from the sync group.")
        stopSelf()
    }

    /** Retries a signed leave request on discovered devices and saved addresses of the old group. */
    private suspend fun deliverPendingLeave(network: Network) {
        val notice = runCatching { store(this).pendingLeave() }.getOrNull() ?: return
        if (System.currentTimeMillis() - notice.lastAttemptAt < LEAVE_RETRY_MS) return
        runCatching { store(this).recordLeaveAttempt() }
        val targets = endpoints.values.flatten().map { it.address to it.port } + notice.addresses.mapNotNull { hint ->
            runCatching { android.net.InetAddresses.parseNumericAddress(hint) }.getOrNull()
                ?.takeIf { isPrivateAddress(it) && !it.isLinkLocalAddress }?.let { it to notice.port }
        }
        for ((address, port) in targets.distinct()) {
            val reply = exchange.withLock {
                val socket = Socket()
                connections.add(socket)
                try {
                    network.bindSocket(socket)
                    socket.connect(InetSocketAddress(address, port), 3000)
                    socket.soTimeout = PRE_AUTH_TIMEOUT_MS
                    LanSyncExchange.sendLeave(SyncFrames(socket.getInputStream(), socket.getOutputStream()), notice)
                } catch (_: Exception) { null }
                finally { connections.remove(socket); runCatching { socket.close() } }
            }
            if (reply == MembershipWire.LEFT) {
                runCatching { store(this).clearPendingLeave() }
                mutableRemovalEvents.value = mutableRemovalEvents.value + 1
                return
            }
        }
    }

    /** A partial wake lock and a high-performance Wi-Fi lock for one authenticated exchange. */
    private inner class TransferLocks {
        private var wake: android.os.PowerManager.WakeLock? = null
        private var wifi: WifiManager.WifiLock? = null

        @Suppress("DEPRECATION")
        fun acquire() {
            if (wake != null) return
            wake = runCatching {
                getSystemService(android.os.PowerManager::class.java)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "nuvori:sync-transfer")
                    .apply { setReferenceCounted(false); acquire(TRANSFER_LOCK_MS) }
            }.getOrNull()
            wifi = runCatching {
                getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nuvori:sync-transfer")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }

        fun release() {
            runCatching { wake?.let { if (it.isHeld) it.release() } }
            runCatching { wifi?.let { if (it.isHeld) it.release() } }
            wake = null; wifi = null
        }
    }

    @Suppress("DEPRECATION")
    private fun startDiscovery(network: Network, port: Int) {
        val register = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                if (registration !== this || activeNetwork != network) return
                setStatus(DeviceSyncPhase.ATTENTION, "Wi-Fi registration failed. Restarting discovery.")
                server?.close()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = register
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = instanceName; serviceType = TYPE; setPort(port)
            if (android.os.Build.VERSION.SDK_INT >= 33) setNetwork(network)
        }, NsdManager.PROTOCOL_DNS_SD, register)
        val discover = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                if (discovery !== this || activeNetwork != network) return
                setStatus(DeviceSyncPhase.ATTENTION, "Wi-Fi discovery failed. Restarting it.")
                server?.close()
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                if (discovery !== this || activeNetwork != network) return
                endpoints.remove(info.serviceName); discovered.remove(info.serviceName)
                setNearbyCount(discovered.size)
                if (discovered.isEmpty() && !exchange.isLocked)
                    setStatus(DeviceSyncPhase.SEARCHING, "The device left Wi-Fi. Looking again.")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (discovery !== this || activeNetwork != network) return
                if (info.serviceName.startsWith(instanceName) || discovered.size >= 64) return
                discovered[info.serviceName] = info
                setNearbyCount(discovered.size)
                if (!exchange.isLocked) setStatus(DeviceSyncPhase.FOUND, "Found Nuvori on Wi-Fi. Verifying it is paired.")
                signals.trySend(Unit)
            }
        }
        discovery = discover
        if (android.os.Build.VERSION.SDK_INT >= 33)
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, network, mainExecutor, discover)
        else nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discover)
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { continuation ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(info))
                }
            })
        }
    }

    override fun onDestroy() {
        manualStop?.cancel()
        setNearbyCount(0)
        clearConnectedPeers("Connection ended.")
        connectivity.unregisterNetworkCallback(callback)
        scope.cancel()
        server?.close()
        connections.forEach { runCatching { it.close() } }
        if (activeInstance === this) activeInstance = null
        if (isPaused(this)) postPausedNotification(this)
        super.onDestroy()
    }

    private fun abortTransport() {
        manualAttempt = false
        scope.cancel()
        setNearbyCount(0)
        clearConnectedPeers("Connection ended.")
        connections.forEach { runCatching { it.close() } }
        runCatching { server?.close() }
    }

    private fun setStatus(phase: DeviceSyncPhase, detail: String) {
        if (activeInstance !== this) return
        if (phase != DeviceSyncPhase.PAUSED && isPaused(this) && !manualAttempt) return
        mutableStatus.value = DeviceSyncStatus(phase, detail)
        updateNotification()
    }

    private fun setPeerStatus(deviceId: String, phase: DeviceSyncPhase, detail: String) {
        if (activeInstance !== this) return
        if (isPaused(this) && !manualAttempt) return
        mutablePeerStatus.update { it + (deviceId to DevicePeerStatus(phase, detail)) }
        updateNotification()
    }

    private fun setNearbyCount(count: Int) {
        if (activeInstance !== this) return
        mutableNearbyCount.value = count
        updateNotification()
    }

    private fun clearConnectedPeers(detail: String) {
        if (activeInstance !== this) return
        mutablePeerStatus.update { states -> states.mapValues { (_, state) ->
            if (state.phase != DeviceSyncPhase.ATTENTION)
                DevicePeerStatus(DeviceSyncPhase.WAITING, detail) else state
        } }
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal fun syncPort(vaultId: String): Int = 20_000 + Math.floorMod(vaultId.hashCode(), 30_000)
        private const val PRE_AUTH_TIMEOUT_MS = 15_000
        private const val SESSION_TIMEOUT_MS = 30_000
        private const val FOLLOW_UP_DEBOUNCE_MS = 750L
        private const val LEAVE_RETRY_MS = 60_000L
        private const val TRANSFER_LOCK_MS = 15L * 60 * 1000
        private val mutableRemovalEvents = MutableStateFlow(0)
        /** Ticks when this device learns it was removed or its leave request is confirmed. */
        val membershipEvents = mutableRemovalEvents.asStateFlow()
        private const val CHANNEL = "device-sync"
        private const val TYPE = "_nuvori-sync._tcp."
        private const val PAUSE = "com.privatevault.app.PAUSE_SYNC"
        private const val RESUME = "com.privatevault.app.RESUME_SYNC"
        private const val SYNC_NOW = "com.privatevault.app.SYNC_NOW"
        private const val PREFERENCES = "sync-service"
        private const val NOTIFICATION_ID = 410
        private const val PAUSED_NOTIFICATION_ID = 411

        private fun settingsPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SYNC_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun resumePendingIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context, 2, Intent(context, LanSyncService::class.java).setAction(RESUME),
            PendingIntent.FLAG_IMMUTABLE)

        private fun postPausedNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val mirror = runCatching { store(context).snapshot() }.getOrNull()
            val active = mirror?.members?.filter { it.status == MemberStatus.ACTIVE.name }.orEmpty()
            if (active.size < 2 || active.none { it.deviceId == mirror?.localDeviceId }) {
                manager.cancel(PAUSED_NOTIFICATION_ID)
                return
            }
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Device sync", NotificationManager.IMPORTANCE_LOW))
            manager.notify(PAUSED_NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(com.privatevault.app.R.drawable.ic_auto_sync)
                .setContentTitle("Device sync paused")
                .setContentText("Automatic checks are off")
                .setContentIntent(settingsPendingIntent(context))
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(Notification.Action.Builder(null, "Resume", resumePendingIntent(context)).build())
                .build())
        }

        val SYNC_INTERVALS = listOf(30_000L, 60_000L, 300_000L, 900_000L)
        fun syncInterval(context: Context): Long = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getLong("interval_ms", SYNC_INTERVALS.first()).takeIf { it in SYNC_INTERVALS } ?: SYNC_INTERVALS.first()
        fun setSyncInterval(context: Context, interval: Long) {
            require(interval in SYNC_INTERVALS)
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putLong("interval_ms", interval).apply()
            if (!context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false))
                context.startForegroundService(Intent(context, LanSyncService::class.java))
        }
        private val mutableStatus = MutableStateFlow(DeviceSyncStatus(DeviceSyncPhase.OFF,
            "Open Nuvori and resume automatic sync to check paired devices."))
        val status = mutableStatus.asStateFlow()
        private val mutablePeerStatus = MutableStateFlow<Map<String, DevicePeerStatus>>(emptyMap())
        val peerStatus = mutablePeerStatus.asStateFlow()
        private val mutableNearbyCount = MutableStateFlow(0)
        val nearbyCount = mutableNearbyCount.asStateFlow()
        @Volatile private var sharedStore: LockedSyncStore? = null
        @Volatile private var activeInstance: LanSyncService? = null
        internal fun store(context: Context): LockedSyncStore = sharedStore ?: synchronized(this) {
            sharedStore ?: LockedSyncStore(context.applicationContext).also { sharedStore = it }
        }
        internal suspend fun publishCredentialChanges(context: Context, database: com.privatevault.app.data.VaultDatabase) {
            try {
                store(context).publish(database)
                if (!isPaused(context)) {
                    val running = activeInstance
                    if (running != null) running.signals.trySend(Unit)
                    else start(context)
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableStatus.value = DeviceSyncStatus(DeviceSyncPhase.ATTENTION,
                "Saved changes need another check. Open Nuvori and tap Sync now.") }
        }
        fun start(context: Context, resume: Boolean = false) {
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            if (resume) {
                preferences.edit().putBoolean("paused", false).apply()
                context.getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIFICATION_ID)
            }
            if (!preferences.getBoolean("paused", false))
                context.startForegroundService(Intent(context, LanSyncService::class.java))
        }

        fun pause(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", true).apply()
            mutableStatus.value = DeviceSyncStatus(DeviceSyncPhase.PAUSED,
                "Tap Resume to check paired devices again.")
            activeInstance?.let {
                it.abortTransport()
                it.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
            context.stopService(Intent(context, LanSyncService::class.java))
            postPausedNotification(context)
        }

        fun isPaused(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false)

        fun allowFutureSync(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", false).apply()
            context.getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIFICATION_ID)
        }

        fun refreshPausedNotification(context: Context) {
            if (isPaused(context)) postPausedNotification(context)
        }

        fun syncNow(context: Context) {
            context.startForegroundService(Intent(context, LanSyncService::class.java).setAction(SYNC_NOW))
        }
    }
}
