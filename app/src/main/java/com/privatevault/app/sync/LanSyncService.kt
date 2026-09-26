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
    OFF("Automatic sync is off"),
    PAUSED("Automatic sync paused"),
    WAITING("Waiting for Wi-Fi"),
    SEARCHING("Looking for paired devices"),
    FOUND("Nuvori found on Wi-Fi"),
    CONNECTING("Connecting securely"),
    TRANSFERRING("Exchanging changes"),
    RECEIVED("Changes received"),
    CHECKED("Device checked"),
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
    private val endpoints = ConcurrentHashMap<String, Endpoint>()
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
                val address = preferredWifiAddress(connectivity.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }.orEmpty()) ?: return@launch
                if (activeNetwork == null || activeNetwork == network && activeAddress != address) {
                    activeNetwork = network
                    activeAddress = address
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
                    setStatus(DeviceSyncPhase.WAITING, "Wi-Fi disconnected. Reconnect both devices to the same network.")
                    clearConnectedPeers("Wi-Fi disconnected.")
                    networkJob?.cancel()
                    server?.close()
                    connections.forEach { runCatching { it.close() } }
                    connectivity.allNetworks.filter { it != network &&
                        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                        .forEach(::connect)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        mutablePeerStatus.value = emptyMap()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "Device sync", NotificationManager.IMPORTANCE_LOW))
        startForeground(410, syncNotification())
        setStatus(DeviceSyncPhase.WAITING, "Connect both devices to the same Wi-Fi network.")
        connectivity = getSystemService(ConnectivityManager::class.java)
        nsd = getSystemService(NsdManager::class.java)
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    }

    private fun syncNotification(): Notification {
        val connected = mutablePeerStatus.value.values.count { it.phase == DeviceSyncPhase.TRANSFERRING }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, LanSyncService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.privatevault.app.R.drawable.ic_vault_codes)
            .setContentTitle("Nuvori sync · $connected connected")
            .setContentText("$pairedCount paired · ${mutableStatus.value.phase.title}")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build()).build()
    }

    @Synchronized private fun updateNotification() {
        if (activeInstance !== this || !::notificationManager.isInitialized) return
        val connected = mutablePeerStatus.value.values.count { it.phase == DeviceSyncPhase.TRANSFERRING }
        val text = "$connected|$pairedCount|${mutableStatus.value.phase}"
        if (text == lastNotificationText) return
        lastNotificationText = text
        notificationManager.notify(410, syncNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", true).apply()
            setStatus(DeviceSyncPhase.PAUSED, "Tap Resume to check paired devices again.")
            stopSelf()
            return START_NOT_STICKY
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
        if (!localIsActive || activeMembers < 2) {
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
                launch {
                    while (isActive) {
                        val socket = try { listener.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                        if (!isPrivateAddress(socket.inetAddress) || !exchange.tryLock()) { socket.close(); continue }
                        launch {
                            setStatus(DeviceSyncPhase.CONNECTING, "A device connected. Verifying membership.")
                            try { transfer(socket, true) } finally { exchange.unlock() }
                        }
                    }
                }
                while (isActive) {
                    val passStartedAt = android.os.SystemClock.elapsedRealtime()
                    val current = store(this@LanSyncService).snapshot() ?: mirror
                    require(current.vaultId == mirror.vaultId)
                    for (info in discovered.values.toList()) {
                        if (!endpoints.containsKey(info.serviceName)) {
                            val resolved = resolve(info)
                            if (resolved == null) {
                                setStatus(DeviceSyncPhase.SEARCHING,
                                    "Found Nuvori, but its Wi-Fi address is still being resolved.")
                                continue
                            }
                            val addresses = if (android.os.Build.VERSION.SDK_INT >= 34 ||
                                android.os.Build.VERSION.SDK_INT >= 33 &&
                                SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.TIRAMISU) >= 7)
                                resolved.hostAddresses else listOfNotNull(resolved.host)
                            val peerAddress = preferredWifiAddress(addresses)
                            if (peerAddress != null && resolved.port in 1024..65535)
                                endpoints[resolved.serviceName] = Endpoint(peerAddress, resolved.port)
                            else setStatus(DeviceSyncPhase.SEARCHING,
                                "Found Nuvori, but its Wi-Fi address is unavailable. Retrying discovery.")
                        }
                    }
                    for ((name, endpoint) in endpoints.entries.toList()) {
                        ensureActive()
                        if (current.peerAddresses.any { (deviceId, address) ->
                                address == endpoint.address.hostAddress &&
                                    lastAuthenticated[deviceId]?.let {
                                        it >= passStartedAt
                                    } == true
                            }) continue
                        // Let either side connect when discovery is one-sided. Stagger the
                        // higher name so two phones do not repeatedly reject each other.
                        if (instanceName > name) delay(1_000 + kotlin.random.Random.nextLong(1_000))
                        exchange.withLock {
                            val socket = Socket()
                            connections.add(socket)
                            try {
                                setStatus(DeviceSyncPhase.CONNECTING, "Verifying the device found on Wi-Fi.")
                                network.bindSocket(socket)
                                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), 5000)
                                if (!transfer(socket, false)) endpoints.remove(name, endpoint)
                            } catch (failure: Exception) {
                                endpoints.remove(name, endpoint)
                                setStatus(DeviceSyncPhase.SEARCHING,
                                    "The Wi-Fi address changed or the connection closed. Looking again.")
                            } finally { connections.remove(socket); socket.close() }
                        }
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
                        // A saved address is a fallback. Discovery may advertise a different port.
                        exchange.withLock {
                            val socket = Socket()
                            connections.add(socket)
                            try {
                                setStatus(DeviceSyncPhase.CONNECTING, "Trying the last confirmed Wi-Fi address.")
                                setPeerStatus(deviceId, DeviceSyncPhase.CONNECTING, "Trying its saved Wi-Fi address.")
                                network.bindSocket(socket)
                                socket.connect(InetSocketAddress(peerAddress, syncPort(mirror.vaultId)), 3000)
                                if (!transfer(socket, false) &&
                                    mutablePeerStatus.value[deviceId]?.phase == DeviceSyncPhase.CONNECTING)
                                    setPeerStatus(deviceId, DeviceSyncPhase.SEARCHING,
                                        "Saved address did not complete a check. Looking on Wi-Fi.")
                            } catch (_: Exception) {
                                setStatus(DeviceSyncPhase.SEARCHING,
                                    "Saved address did not answer. Looking for this device on Wi-Fi.")
                                setPeerStatus(deviceId, DeviceSyncPhase.SEARCHING,
                                    "Saved address did not answer. Looking on Wi-Fi.")
                            } finally { connections.remove(socket); socket.close() }
                        }
                    }
                    withTimeoutOrNull(syncInterval(this@LanSyncService) + kotlin.random.Random.nextLong(5000)) { signals.receive() }
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
        }
    }

    private fun transfer(socket: Socket, server: Boolean): Boolean {
        connections.add(socket)
        var peerId: String? = null
        try {
            socket.use {
                socket.soTimeout = 30_000
                LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                    store(this), server) { authenticatedId ->
                    peerId = authenticatedId
                    lastAuthenticated[authenticatedId] = android.os.SystemClock.elapsedRealtime()
                    if (manualAttempt) manualProgressAt = android.os.SystemClock.elapsedRealtime()
                    store(this).recordPeerContact(authenticatedId, completed = false)
                    if (!socket.inetAddress.isLinkLocalAddress)
                        runCatching { store(this).recordPeerAddress(authenticatedId, socket.inetAddress.hostAddress!!) }
                    setPeerStatus(authenticatedId, DeviceSyncPhase.TRANSFERRING, "Encrypted changes are being exchanged.")
                    setStatus(DeviceSyncPhase.TRANSFERRING, "Sending and receiving encrypted changes.")
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
                            "The device answered. Check its card below for received and applied progress.")
                    }
                }
                if (manualAttempt) {
                    manualCompleted = true
                    stopSelf()
                }
            }
            return true
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
            signals.trySend(Unit)
        } catch (_: Exception) {
            setStatus(DeviceSyncPhase.ATTENTION, "The connection closed before the check finished. Retrying on Wi-Fi.")
            peerId?.let { setPeerStatus(it, DeviceSyncPhase.ATTENTION, "Connection closed before the check finished.") }
        }
        finally { connections.remove(socket) }
        return false
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
                if (discovered.isEmpty() && !exchange.isLocked)
                    setStatus(DeviceSyncPhase.SEARCHING, "The device left Wi-Fi. Looking again.")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (discovery !== this || activeNetwork != network) return
                if (info.serviceName.startsWith(instanceName) || discovered.size >= 64) return
                discovered[info.serviceName] = info
                if (!exchange.isLocked) setStatus(DeviceSyncPhase.FOUND, "Checking its address before connecting.")
                signals.trySend(Unit)
            }
        }
        discovery = discover
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discover)
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
        clearConnectedPeers("Connection ended.")
        connectivity.unregisterNetworkCallback(callback)
        scope.cancel()
        server?.close()
        connections.forEach { runCatching { it.close() } }
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private fun abortTransport() {
        manualAttempt = false
        scope.cancel()
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

    private fun clearConnectedPeers(detail: String) {
        if (activeInstance !== this) return
        mutablePeerStatus.update { states -> states.mapValues { (_, state) ->
            if (state.phase == DeviceSyncPhase.TRANSFERRING || state.phase == DeviceSyncPhase.CONNECTING)
                DevicePeerStatus(DeviceSyncPhase.WAITING, detail) else state
        } }
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal fun syncPort(vaultId: String): Int = 20_000 + Math.floorMod(vaultId.hashCode(), 30_000)
        private const val CHANNEL = "device-sync"
        private const val TYPE = "_nuvori-sync._tcp."
        private const val PAUSE = "com.privatevault.app.PAUSE_SYNC"
        private const val SYNC_NOW = "com.privatevault.app.SYNC_NOW"
        private const val PREFERENCES = "sync-service"
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
            if (resume) preferences.edit().putBoolean("paused", false).apply()
            if (!preferences.getBoolean("paused", false))
                context.startForegroundService(Intent(context, LanSyncService::class.java))
        }

        fun pause(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", true).apply()
            mutableStatus.value = DeviceSyncStatus(DeviceSyncPhase.PAUSED,
                "Tap Resume to check paired devices again.")
            activeInstance?.abortTransport()
            context.stopService(Intent(context, LanSyncService::class.java))
        }

        fun isPaused(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false)

        fun allowFutureSync(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", false).apply()
        }

        fun syncNow(context: Context) {
            context.startForegroundService(Intent(context, LanSyncService::class.java).setAction(SYNC_NOW))
        }
    }
}
