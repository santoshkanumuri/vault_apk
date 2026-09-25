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
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.BindException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns only transport keys and ciphertext. Opening the vault is the UI's responsibility. */
class LanSyncService : Service() {
    private data class Endpoint(val address: InetAddress, val port: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val discovered = ConcurrentHashMap<String, NsdServiceInfo>()
    private val exchange = Mutex()
    private lateinit var connectivity: ConnectivityManager
    private lateinit var nsd: NsdManager
    private var networkJob: Job? = null
    private var activeNetwork: Network? = null
    private var activeAddress: InetAddress? = null
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var manualStop: Job? = null
    private var manualAttempt = false
    private var manualCompleted = false
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Device sync", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, LanSyncService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        startForeground(410, Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.privatevault.app.R.drawable.ic_vault_codes)
            .setContentTitle("Nuvori device sync")
            .setContentText("Available to paired devices on Wi-Fi. Unlock to apply changes.")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build()).build())
        mutableStatus.value = "Waiting for Wi-Fi"
        connectivity = getSystemService(ConnectivityManager::class.java)
        nsd = getSystemService(NsdManager::class.java)
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", true).apply()
            mutableStatus.value = "Automatic sync paused"
            stopSelf()
            return START_NOT_STICKY
        }
        val paused = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false)
        if (paused && intent?.action != SYNC_NOW) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runCatching { store(this).snapshot()?.members?.count {
                it.status == MemberStatus.ACTIVE.name }?.let { it < 2 } ?: true }.getOrDefault(true)) {
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
            manualStop?.cancel()
            manualStop = scope.launch {
                delay(35_000)
                if (!manualCompleted && mutableStatus.value in setOf("Waiting for Wi-Fi",
                        "Looking for paired phones on Wi-Fi", "Found a Nuvori service on Wi-Fi"))
                    mutableStatus.value = "No paired phone found. Check Wi-Fi and try again."
                stopSelf()
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
            startDiscovery(network, listener.localPort)
            mutableStatus.value = "Looking for paired phones on Wi-Fi"
            coroutineScope {
                launch {
                    while (isActive) {
                        val socket = try { listener.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                        if (!isPrivateAddress(socket.inetAddress) || !exchange.tryLock()) { socket.close(); continue }
                        launch {
                            try { transfer(socket, true) } finally { exchange.unlock() }
                        }
                    }
                }
                while (isActive) {
                    val current = store(this@LanSyncService).snapshot() ?: mirror
                    require(current.vaultId == mirror.vaultId)
                    current.peerAddresses.forEach { (deviceId, hint) ->
                            if (current.members.none { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
                                return@forEach
                            // Both phones remember the address after pairing. Let one connect first so
                            // simultaneous outgoing sockets do not reject each other's incoming socket.
                            if (current.localDeviceId > deviceId) delay(5_000)
                            val peerAddress = runCatching { android.net.InetAddresses.parseNumericAddress(hint) }
                                .getOrNull() ?: return@forEach
                            if (!isPrivateAddress(peerAddress) || peerAddress.isLinkLocalAddress) return@forEach
                            exchange.withLock {
                                val socket = Socket()
                                connections.add(socket)
                                try {
                                    mutableStatus.value = "Connecting to a paired phone on Wi-Fi"
                                    network.bindSocket(socket)
                                    socket.connect(InetSocketAddress(peerAddress, syncPort(mirror.vaultId)), 3000)
                                    transfer(socket, false)
                                } catch (_: Exception) {
                                    mutableStatus.value = "Saved phone address is unreachable. Looking on Wi-Fi."
                                } finally { connections.remove(socket); socket.close() }
                            }
                    }
                    for (info in discovered.values.toList()) {
                        if (!endpoints.containsKey(info.serviceName)) {
                            val resolved = resolve(info)
                            if (resolved == null) {
                                mutableStatus.value = "Found Nuvori, but Wi-Fi discovery could not resolve it."
                                continue
                            }
                            val addresses = if (android.os.Build.VERSION.SDK_INT >= 34 ||
                                android.os.Build.VERSION.SDK_INT >= 33 &&
                                SdkExtensions.getExtensionVersion(33) >= 7)
                                resolved.hostAddresses else listOfNotNull(resolved.host)
                            val peerAddress = preferredWifiAddress(addresses)
                            if (peerAddress != null && resolved.port in 1024..65535)
                                endpoints[resolved.serviceName] = Endpoint(peerAddress, resolved.port)
                            else mutableStatus.value = "Found Nuvori, but its Wi-Fi address is unavailable."
                        }
                    }
                    for ((name, endpoint) in endpoints.entries.toList()) {
                        ensureActive()
                        // Both phones discover each other. Only one initiates a connection.
                        if (instanceName >= name) continue
                        exchange.withLock {
                            val socket = Socket()
                            connections.add(socket)
                            try {
                                mutableStatus.value = "Connecting to a Nuvori device on Wi-Fi"
                                network.bindSocket(socket)
                                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), 5000)
                                transfer(socket, false)
                            } catch (failure: Exception) {
                                mutableStatus.value = if (failure is java.net.SocketTimeoutException ||
                                    failure is java.net.ConnectException)
                                    "Found Nuvori, but the Wi-Fi connection failed. Retrying."
                                else "Sync connection failed. Retrying."
                            } finally { connections.remove(socket); socket.close() }
                        }
                    }
                    withTimeoutOrNull(syncInterval(this@LanSyncService) + kotlin.random.Random.nextLong(5000)) { signals.receive() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (!mutableStatus.value.endsWith("failed. Retrying."))
                mutableStatus.value = "Wi-Fi discovery interrupted. Retrying."
        }
        finally {
            server?.close(); server = null
            discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
            registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
            multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null
            endpoints.clear()
            discovered.clear()
        }
    }

    private fun transfer(socket: Socket, server: Boolean) {
        connections.add(socket)
        try {
            socket.use {
                socket.soTimeout = 15_000
                LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                    store(this), server)
                mutableStatus.value = if (store(this).missingPhotos().isNotEmpty())
                    "Photo data is waiting for a paired phone on Wi-Fi. Keep both devices connected."
                else if (store(this).queuedCount() > 0)
                    "Encrypted changes received. Unlock to apply them."
                else "Paired phone checked. No incoming changes waiting."
                if (manualAttempt) {
                    manualCompleted = true
                    stopSelf()
                }
            }
        } catch (_: PhotoStorageFullException) {
            mutableStatus.value = "Photo sync needs more free storage. Free space, then tap Sync now."
        } catch (_: SyncQueueFullException) {
            mutableStatus.value = "Encrypted sync queue is full. Unlock this device to apply changes, then sync again."
        } catch (_: Exception) { mutableStatus.value = "Sync interrupted. Retrying on Wi-Fi." }
        finally { connections.remove(socket) }
    }

    @Suppress("DEPRECATION")
    private fun startDiscovery(network: Network, port: Int) {
        val register = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                mutableStatus.value = "Wi-Fi service registration failed. Retrying."
                if (registration === this) server?.close()
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
                mutableStatus.value = "Wi-Fi discovery failed. Retrying."
                if (discovery === this) server?.close()
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                endpoints.remove(info.serviceName); discovered.remove(info.serviceName)
                if (discovered.isEmpty()) mutableStatus.value = "Looking for paired phones on Wi-Fi"
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.startsWith(instanceName) || discovered.size >= 64) return
                discovered[info.serviceName] = info
                mutableStatus.value = "Found a Nuvori service on Wi-Fi"
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
        connectivity.unregisterNetworkCallback(callback)
        scope.cancel()
        server?.close()
        connections.forEach { runCatching { it.close() } }
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private fun abortTransport() {
        scope.cancel()
        connections.forEach { runCatching { it.close() } }
        runCatching { server?.close() }
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
        private val mutableStatus = MutableStateFlow("Automatic sync is not running")
        val status = mutableStatus.asStateFlow()
        @Volatile private var sharedStore: LockedSyncStore? = null
        @Volatile private var activeInstance: LanSyncService? = null
        internal fun store(context: Context): LockedSyncStore = sharedStore ?: synchronized(this) {
            sharedStore ?: LockedSyncStore(context.applicationContext).also { sharedStore = it }
        }
        internal suspend fun publishCredentialChanges(context: Context, database: com.privatevault.app.data.VaultDatabase) {
            try { store(context).publish(database) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableStatus.value = "Saved changes need another sync attempt. Open Nuvori to retry." }
        }
        fun start(context: Context, resume: Boolean = false) {
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            if (resume) preferences.edit().putBoolean("paused", false).apply()
            if (!preferences.getBoolean("paused", false))
                context.startForegroundService(Intent(context, LanSyncService::class.java))
        }

        fun pause(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", true).apply()
            mutableStatus.value = "Automatic sync paused"
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
