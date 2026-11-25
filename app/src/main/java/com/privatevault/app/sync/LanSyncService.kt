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

/**
 * How a start request runs. Only continuous Auto sync is a foreground service with a notification (Android
 * requires one). A one-time pass (Sync now, or opening Nuvori) while Auto sync is paused runs as a plain
 * service while Nuvori is on screen, so it shows no notification and leaves none behind.
 */
internal enum class SyncRun { STOP, ONE_PASS, AUTO }

internal fun syncRun(action: String?, paused: Boolean, notificationsOn: Boolean, autoRunning: Boolean = false): SyncRun {
    val oneTime = action == LanSyncService.SYNC_NOW || action == LanSyncService.SYNC_ON_OPEN
    return when {
        // M10: a check asked for while Auto sync runs joins it, and never turns it into a one-time pass.
        oneTime && autoRunning -> SyncRun.AUTO
        oneTime && (paused || !notificationsOn) -> SyncRun.ONE_PASS
        paused -> SyncRun.STOP
        else -> SyncRun.AUTO
    }
}
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
    private val preAuth = PreAuthLimiter()
    /** True after [startForeground]: only continuous Auto sync gets there. */
    private var foreground = false
    private lateinit var connectivity: ConnectivityManager
    private lateinit var nsd: NsdManager
    @Volatile private var discoveryNeedsRestart = true
    private var server: ServerSocket? = null
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var manualStop: Job? = null
    @Volatile private var manualAttempt = false
    /** The one-time pass came from opening Nuvori: finding no device is normal and says nothing. */
    @Volatile private var quietAttempt = false
    @Volatile private var manualCompleted = false
    @Volatile private var manualProgressAt = 0L
    /** M10: when the current one-time pass started; devices proved since then were reached. */
    @Volatile private var manualStartedAt = 0L
    /** M8: when the exchange lock was last taken; 0 while it is free. */
    @Volatile private var exchangeSince = 0L
    /** M12: the last session failed because the other device sent something refused, not a dropped link. */
    @Volatile private var lastFailureRefused = false
    private val instanceName = "nuvori-" + UUID.randomUUID().toString()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        private fun networkChanged() {
            endpoints.clear()
            discoveryNeedsRestart = true
            signals.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        mutablePeerStatus.value = emptyMap()
        mutableNearbyCount.value = 0
        setStatus(DeviceSyncPhase.WAITING, "Connect both devices to the same Wi-Fi network.")
        connectivity = getSystemService(ConnectivityManager::class.java)
        nsd = getSystemService(NsdManager::class.java)
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        // A hotspot can remain reachable without a Wi-Fi client Network callback.
        scope.launch { while (isActive) { listen(); delay(5_000) } }
    }

    /**
     * The one notification Auto sync needs. Its text never changes, so Nuvori never posts it again: a swipe on
     * Android 14+ or a blocked channel keeps it hidden, and the Auto sync tile still shows On or Paused.
     */
    private fun autoSyncNotification(): Notification {
        val pause = PendingIntent.getService(this, 1, Intent(this, LanSyncService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.privatevault.app.R.drawable.ic_auto_sync)
            .setContentTitle("Auto sync is on")
            .setContentText("Checks your paired devices on Wi-Fi.")
            .setContentIntent(settingsPendingIntent(this))
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 31)
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED) }
            .addAction(Notification.Action.Builder(null, "Pause", pause).build())
            .build()
    }

    /** Makes this a foreground service. False when Android refuses (for example, a start from the background). */
    private fun promote(): Boolean {
        if (foreground) return true
        foreground = runCatching {
            createChannel(this)
            startForeground(NOTIFICATION_ID, autoSyncNotification())
        }.isSuccess
        return foreground
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService() call must reach startForeground(), even when this start then stops.
        // RESUME comes from a 2.2.10 paused notification that can still be in the shade after the update.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true || intent?.action == RESUME ||
            (intent == null && !isPaused(this))) promote()
        if (intent?.action == PAUSE) {
            pause(this)
            return START_NOT_STICKY
        }
        if (intent?.action == RESUME) {
            allowFutureSync(this)
        }
        val paused = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false)
        if (paused && intent?.action != SYNC_NOW && intent?.action != SYNC_ON_OPEN) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mirror = runCatching { store(this).snapshot() }.getOrNull()
        val activeIds = mirror?.members?.filter { it.status == MemberStatus.ACTIVE.name }
            ?.mapTo(hashSetOf()) { it.deviceId }.orEmpty()
        val activeMembers = activeIds.size
        val localIsActive = mirror?.localDeviceId in activeIds
        mutablePeerStatus.update { states -> states.filterKeys { it in activeIds } }
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
        val opened = intent?.action == SYNC_ON_OPEN
        val run = syncRun(intent?.action, paused, notificationsOn(this))
        if (run == SyncRun.AUTO && !promote()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (run == SyncRun.ONE_PASS) {
            // A Sync now during a quiet pass makes it a normal one, with its messages.
            quietAttempt = opened && (!manualAttempt || quietAttempt)
            if (!manualAttempt) manualStartedAt = android.os.SystemClock.elapsedRealtime()
            manualAttempt = true
            manualCompleted = false
            manualProgressAt = android.os.SystemClock.elapsedRealtime()
            manualStop?.cancel()
            manualStop = scope.launch {
                while (!manualCompleted) {
                    delay(1_000)
                    if (exchange.isLocked || android.os.SystemClock.elapsedRealtime() - manualProgressAt <
                        (if (quietAttempt) QUIET_ATTEMPT_MS else 35_000)) continue
                    val searching = mutableStatus.value.phase in setOf(DeviceSyncPhase.WAITING,
                        DeviceSyncPhase.SEARCHING, DeviceSyncPhase.FOUND, DeviceSyncPhase.CONNECTING)
                    // M10: devices reached in this attempt keep their result; only a pass that reached none says so.
                    val reachedAny = lastAuthenticated.values.any { it >= manualStartedAt }
                    if (searching && reachedAny) settleStatus()
                    else if (searching && quietAttempt) {
                        if (isPaused(this@LanSyncService)) setStatus(DeviceSyncPhase.PAUSED, "Tap Resume to check paired devices again.")
                    } else if (searching) setStatus(DeviceSyncPhase.ATTENTION, NO_DEVICE_ANSWERED)
                    stopSelf()
                    break
                }
            }
        }
        signals.trySend(Unit)
        return if (manualAttempt) START_NOT_STICKY else START_STICKY
    }

    private suspend fun listen() {
        try {
            val mirror = requireNotNull(store(this).snapshot())
            val listener = openSyncListener(syncPort(mirror.vaultId))
            server = listener
            // A device without Wi-Fi has no WifiManager; discovery over Ethernet needs no multicast lock.
            multicastLock = runCatching {
                getSystemService(WifiManager::class.java)?.createMulticastLock("nuvori-sync-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
            setStatus(DeviceSyncPhase.SEARCHING, "Checking nearby Nuvori devices on Wi-Fi.")
            discoveryNeedsRestart = true
            coroutineScope {
                var retryDelayMs = 5_000L
                launch { acceptFrom(listener) }
                // L5: also listen on the port older versions use, while a paired device may.
                launch {
                    var legacy: ServerSocket? = null
                    try {
                        while (isActive) {
                            val current = runCatching { store(this@LanSyncService).snapshot() }.getOrNull() ?: mirror
                            val port = SyncPorts.legacy(mirror.vaultId)
                            val needed = port != listener.localPort &&
                                SyncPorts.legacyNeeded(current.localDeviceId, current.members, current.peerFeatures)
                            if (needed && legacy == null) {
                                legacy = runCatching { ServerSocket().apply {
                                    reuseAddress = true
                                    try { bind(InetSocketAddress(port)) } catch (error: Exception) { close(); throw error }
                                    soTimeout = 1000
                                } }.getOrNull()
                                legacy?.let { opened -> launch { acceptFrom(opened, untilClosed = true) } }
                            } else if (!needed && legacy != null) {
                                runCatching { legacy?.close() }
                                legacy = null
                            }
                            delay(LEGACY_PORT_CHECK_MS)
                        }
                    } finally { runCatching { legacy?.close() } }
                }
                while (isActive) {
                    if (discoveryNeedsRestart) {
                        discoveryNeedsRestart = false
                        stopDiscovery()
                        runCatching { startDiscovery(listener.localPort) }.onFailure {
                            discoveryNeedsRestart = true
                            setStatus(DeviceSyncPhase.SEARCHING, "Discovery is unavailable. Trying saved device addresses.")
                        }
                    }
                    var needsRetry = false
                    peerBusy = false
                    val passStartedAt = android.os.SystemClock.elapsedRealtime()
                    // An unlocked app writes a pending appearance now, if the group allows it.
                    AppearanceSync.requestFlush()
                    val current =store(this@LanSyncService).snapshot() ?: mirror
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
                        lastFailureRefused = false
                        exchanged {
                            var socket: Socket? = null
                            try {
                                setStatus(DeviceSyncPhase.CONNECTING, "Trying the last confirmed Wi-Fi address.")
                                setPeerStatus(deviceId, DeviceSyncPhase.CONNECTING, "Trying its saved Wi-Fi address.")
                                // L5: the port it was last reached on, then this group's ports, then the older one.
                                socket = connectFirst(peerAddress,
                                    SyncPorts.targets(peerPort(this@LanSyncService, deviceId), mirror.vaultId), 3000) {
                                    connections.add(it)
                                    bindSyncSocket(connectivity, it, peerAddress)
                                }
                                if (!transfer(socket, false)) {
                                    needsRetry = !lastFailureRefused
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
                            } finally {
                                connections.removeIf { it.isClosed }
                                socket?.let { connections.remove(it); it.close() }
                            }
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
                            lastFailureRefused = false
                            exchanged {
                                val socket = Socket()
                                connections.add(socket)
                                try {
                                    setStatus(DeviceSyncPhase.CONNECTING, "Verifying the device found on Wi-Fi.")
                                    bindSyncSocket(connectivity, socket, endpoint.address)
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
                            // M12: the device answered and refused; its other addresses would only repeat that.
                            if (lastFailureRefused) break
                        }
                        if (!completed) {
                            needsRetry = needsRetry || !lastFailureRefused
                            if (!peerBusy) endpoints.remove(name, candidates)
                        }
                    }
                    deliverPendingLeave()
                    finishOneTimePassIfDone(activePeers.map { it.deviceId })
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
            stopDiscovery()
            multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null
        }
    }

    /**
     * Accepts connections on [listener] until the pass ends: each from a private address takes a
     * pre-authentication slot and proves membership before anything else ([serveIncoming]). L5: the
     * listener on the older port uses the same path, and with [untilClosed] stops when it closes.
     */
    private fun CoroutineScope.acceptFrom(listener: ServerSocket, untilClosed: Boolean = false) {
        while (isActive) {
            val socket = try { listener.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            catch (error: java.net.SocketException) { if (untilClosed && listener.isClosed) return else throw error }
            if (!isPrivateAddress(socket.inetAddress)) { socket.close(); continue }
            // L-06: the exchange lock is held only by proved devices (or this device's own
            // attempts), so a busy reply before the challenge, which every version
            // understands, can't be caused by a stranger.
            if (exchange.isLocked) {
                closeOverdueExchange()
                socket.use {
                    runCatching { SyncFrames(socket.getInputStream(), socket.getOutputStream())
                        .send(MembershipWire.BUSY.toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME) }
                }
                continue
            }
            // M7: an IPv6 host's whole /64 is one source; a paired device's last address has reserved slots.
            val source = sourceKey(socket.inetAddress)
            val known = runCatching { store(this@LanSyncService).snapshot()?.peerAddresses?.values
                ?.contains(socket.inetAddress.hostAddress) == true }.getOrDefault(false)
            if (!preAuth.tryStart(source, android.os.SystemClock.elapsedRealtime(), known)) { socket.close(); continue }
            launch { serveIncoming(socket, source) }
        }
    }

    /**
     * Security review L-06: an incoming connection proves group membership before it waits for the
     * exchange lock, and within [PRE_AUTH_DEADLINE_MS] however slowly it sends, so a device on the
     * Wi-Fi that isn't in the group can't hold up sync. [source] holds a slot in [preAuth].
     */
    private suspend fun serveIncoming(socket: Socket, source: String) {
        connections.add(socket)
        val watch = SessionWatch({ android.os.SystemClock.elapsedRealtime() })
        val checked = try {
            socket.soTimeout = PRE_AUTH_TIMEOUT_MS
            val frames = SyncFrames(watch.input(socket.getInputStream()), watch.output(socket.getOutputStream()))
            val deadline = closeUnlessProved(socket)
            try { frames to LanSyncExchange.checkClient(frames, store(this)) } finally { deadline.cancel() }
        } catch (failure: Exception) {
            preAuth.finish(source, false, android.os.SystemClock.elapsedRealtime())
            connections.remove(socket); runCatching { socket.close() }
            if (failure is CancellationException) throw failure
            return
        }
        preAuth.finish(source, true, android.os.SystemClock.elapsedRealtime())
        val (frames, first) = checked
        // A proved device waits briefly for another exchange to finish, then gets a busy reply.
        // M8: tryLock never leaves the lock taken by a wait that timed out, as withTimeoutOrNull { lock() } could.
        if (!lockExchangeWithin(LOCK_WAIT_MS)) {
            runCatching { frames.send(MembershipWire.BUSY.toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME) }
            connections.remove(socket); runCatching { socket.close() }
            return
        }
        try {
            setStatus(DeviceSyncPhase.CONNECTING, "A device answered. Checking that it belongs to your vault.")
            transfer(socket, true, frames, first, watch)
        } finally { exchangeSince = 0L; exchange.unlock() }
    }

    /** M8: takes the exchange lock within [waitMs], polling, so a timed-out wait never holds it. */
    private suspend fun lockExchangeWithin(waitMs: Long): Boolean {
        val until = android.os.SystemClock.elapsedRealtime() + waitMs
        while (true) {
            if (exchange.tryLock()) { exchangeSince = android.os.SystemClock.elapsedRealtime(); return true }
            if (android.os.SystemClock.elapsedRealtime() >= until) return false
            delay(LOCK_POLL_MS)
        }
    }

    /** Runs [block] holding the exchange lock, noting when it was taken for [closeOverdueExchange]. */
    private suspend inline fun <T> exchanged(block: () -> T): T = exchange.withLock {
        exchangeSince = android.os.SystemClock.elapsedRealtime()
        try { block() } finally { exchangeSince = 0L }
    }

    /**
     * M8: an exchange can't legitimately hold the lock longer than a session's deadline. If one does,
     * its sockets close, which ends it and frees the lock for the other devices.
     */
    private fun closeOverdueExchange() {
        val since = exchangeSince
        if (since == 0L || android.os.SystemClock.elapsedRealtime() - since <= SESSION_DEADLINE_MS + LOCK_WAIT_MS) return
        android.util.Log.w(TAG, "An exchange held the sync lock past its deadline; closing it")
        connections.forEach { runCatching { it.close() } }
    }

    /** M10: ends a one-time pass once every active paired device was reached since it started. */
    private fun finishOneTimePassIfDone(activePeers: List<String>) {
        if (!manualAttempt || manualCompleted) return
        val reached = lastAuthenticated.filterValues { it >= manualStartedAt }.keys
        if (!oneTimePassDone(activePeers, reached)) return
        manualCompleted = true
        stopSelf()
    }

    /** The status after a finished exchange: what arrived and waits here, or that the check finished. */
    private fun settleStatus(peerId: String? = null) {
        when {
            store(this).missingPhotos().isNotEmpty() -> {
                peerId?.let { setPeerStatus(it, DeviceSyncPhase.RECEIVED, "Photo data is still waiting.") }
                setStatus(DeviceSyncPhase.RECEIVED, "Photo data is still waiting. Keep both devices on Wi-Fi.")
            }
            store(this).queuedCount() > 0 -> {
                peerId?.let { setPeerStatus(it, DeviceSyncPhase.RECEIVED, "Changes received; unlock to apply them.") }
                setStatus(DeviceSyncPhase.RECEIVED, "Encrypted changes arrived and are waiting to apply on this device.")
            }
            else -> {
                peerId?.let { setPeerStatus(it, DeviceSyncPhase.CHECKED, "Encrypted exchange completed.") }
                setStatus(DeviceSyncPhase.CHECKED,
                    "Encrypted exchange finished. Device cards show what was received and applied.")
            }
        }
    }

    /** M11: closes [socket] when its session stops moving or runs past its deadline. */
    private fun watchSession(socket: Socket, watch: SessionWatch): Job = scope.launch {
        while (isActive) {
            delay(WATCH_EVERY_MS)
            if (watch.expired(android.os.SystemClock.elapsedRealtime(), SESSION_TIMEOUT_MS.toLong(), SESSION_DEADLINE_MS)) {
                runCatching { socket.close() }
                break
            }
        }
    }

    /** Closes [socket] unless it is cancelled within [PRE_AUTH_DEADLINE_MS] (L-06). */
    private fun closeUnlessProved(socket: Socket): Job = scope.launch {
        delay(PRE_AUTH_DEADLINE_MS)
        runCatching { socket.close() }
    }

    private fun transfer(socket: Socket, server: Boolean, frames: SyncFrames? = null,
        checked: LanSyncExchange.ClientFirst? = null,
        watch: SessionWatch = SessionWatch({ android.os.SystemClock.elapsedRealtime() })): Boolean {
        connections.add(socket)
        var peerId: String? = null
        lastFailureRefused = false
        val locks = TransferLocks()
        // L-06: an exchange that hasn't proved membership within the deadline is closed.
        val deadline = closeUnlessProved(socket)
        // M11: a session that stops moving (a write to a device that left) is closed too.
        val stall = watchSession(socket, watch)
        try {
            socket.use {
                // Until the peer proves group membership, keep the exchange lock only briefly.
                socket.soTimeout = PRE_AUTH_TIMEOUT_MS
                LanSyncExchange.run(frames ?: SyncFrames(watch.input(socket.getInputStream()), watch.output(socket.getOutputStream())),
                    store(this), server, checked) { authenticatedId, platform ->
                    deadline.cancel()
                    socket.soTimeout = SESSION_TIMEOUT_MS
                    locks.acquire()
                    runCatching { store(this).recordPeerPlatform(authenticatedId, platform) }
                    peerId = authenticatedId
                    lastAuthenticated[authenticatedId] = android.os.SystemClock.elapsedRealtime()
                    if (manualAttempt) manualProgressAt = android.os.SystemClock.elapsedRealtime()
                    store(this).recordPeerContact(authenticatedId, completed = false)
                    if (!socket.inetAddress.isLinkLocalAddress)
                        runCatching { store(this).recordPeerAddress(authenticatedId, socket.inetAddress.hostAddress!!) }
                    // L5: the port this device was reached on. When its usual port was taken it listens on
                    // another one, which only discovery would otherwise find.
                    if (!server) rememberPeerPort(this, authenticatedId, socket.port)
                    setPeerStatus(authenticatedId, DeviceSyncPhase.TRANSFERRING, "Encrypted changes are being exchanged.")
                    setStatus(DeviceSyncPhase.TRANSFERRING, "Connected securely. Sending and receiving encrypted changes.")
                }
                peerId?.let { store(this).recordPeerContact(it, completed = true) }
                settleStatus(requireNotNull(peerId))
                // M10: a one-time pass goes on to the other devices; [finishOneTimePassIfDone] ends it.
                if (manualAttempt) {
                    manualProgressAt = android.os.SystemClock.elapsedRealtime()
                    finishOneTimePassIfDone(activeDeviceIds())
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
        } catch (refused: PeerRefused) {
            // M12: the other device refused what this one sent and said so ([SyncRefusal]). Not a Wi-Fi
            // problem and not retried at once; shown on that device's card.
            lastFailureRefused = true
            android.util.Log.w(TAG, "A paired device refused this device's session (${refused.code})")
            peerId?.let { setPeerStatus(it, DeviceSyncPhase.ATTENTION, PEER_REFUSED_OURS) }
            setStatus(DeviceSyncPhase.ATTENTION, DEVICE_REFUSED_OURS)
        } catch (failure: Exception) {
            // M12: a refusal (signature, forked history, bad frame) is not a Wi-Fi problem, and retrying
            // at once changes nothing; it is shown on that device's card.
            if (SessionFailure.of(failure) == SessionFailure.REFUSED && peerId != null) {
                lastFailureRefused = true
                android.util.Log.w(TAG, "A paired device's session was refused: ${failure.javaClass.simpleName}")
                setPeerStatus(peerId!!, DeviceSyncPhase.ATTENTION, PEER_REFUSED)
                setStatus(DeviceSyncPhase.ATTENTION, DEVICE_REFUSED)
            } else {
                setStatus(DeviceSyncPhase.ATTENTION, CONNECTION_CLOSED)
                peerId?.let { setPeerStatus(it, DeviceSyncPhase.ATTENTION, PEER_CONNECTION_CLOSED) }
            }
        }
        finally { deadline.cancel(); stall.cancel(); connections.remove(socket); locks.release() }
        return false
    }

    private fun activeDeviceIds(): List<String> = runCatching {
        val mirror = requireNotNull(store(this).snapshot())
        mirror.members.filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != mirror.localDeviceId }.map { it.deviceId }
    }.getOrDefault(emptyList())

    private fun recordRemoval(notice: RemovalNotice.Removed) = recordRemoval(this, notice)

    private fun stopAfterRemoval() {
        setStatus(DeviceSyncPhase.ATTENTION,
            "This device was removed from the sync group. Your items stay on this device.")
        clearConnectedPeers("Removed from the sync group.")
        stopSelf()
    }

    /** Retries a signed leave request on discovered devices and saved addresses of the old group. */
    private suspend fun deliverPendingLeave() {
        val notice = runCatching { store(this).pendingLeave() }.getOrNull() ?: return
        if (System.currentTimeMillis() - notice.lastAttemptAt < LEAVE_RETRY_MS) return
        runCatching { store(this).recordLeaveAttempt() }
        // L5: a saved address is tried on the saved port, then the group's ports and the older one.
        val targets = endpoints.values.flatten().map { it.address to listOf(it.port) } + notice.addresses.mapNotNull { hint ->
            runCatching { android.net.InetAddresses.parseNumericAddress(hint) }.getOrNull()
                ?.takeIf { isPrivateAddress(it) && !it.isLinkLocalAddress }
                ?.let { it to SyncPorts.targets(notice.port.takeIf { port -> port in 1024..65535 }, notice.vaultId) }
        }
        for ((address, ports) in targets.distinct()) {
            val reply = exchanged {
                var socket: Socket? = null
                try {
                    socket = connectFirst(address, ports, 3000) {
                        connections.add(it)
                        bindSyncSocket(connectivity, it, address)
                    }
                    socket.soTimeout = PRE_AUTH_TIMEOUT_MS
                    LanSyncExchange.sendLeave(SyncFrames(socket.getInputStream(), socket.getOutputStream()), notice)
                } catch (_: Exception) { null }
                finally {
                    connections.removeIf { it.isClosed }
                    socket?.let { connections.remove(it); runCatching { it.close() } }
                }
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
                getSystemService(WifiManager::class.java)!!
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
    private fun startDiscovery(port: Int) {
        val register = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (registration !== this) runCatching { nsd.unregisterService(this) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                if (registration !== this) return
                discoveryNeedsRestart = true
                setStatus(DeviceSyncPhase.SEARCHING, "Discovery is unavailable. Trying saved device addresses.")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = register
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = instanceName; serviceType = TYPE; setPort(port)
        }, NsdManager.PROTOCOL_DNS_SD, register)
        val discover = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                if (discovery !== this) runCatching { nsd.stopServiceDiscovery(this) }
            }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                if (discovery !== this) return
                discoveryNeedsRestart = true
                setStatus(DeviceSyncPhase.SEARCHING, "Discovery is unavailable. Trying saved device addresses.")
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                if (discovery !== this) return
                endpoints.remove(info.serviceName); discovered.remove(info.serviceName)
                setNearbyCount(discovered.size)
                if (discovered.isEmpty() && !exchange.isLocked)
                    setStatus(DeviceSyncPhase.SEARCHING, "The device left Wi-Fi. Looking again.")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (discovery !== this) return
                if (info.serviceName.startsWith(instanceName) || discovered.size >= 64) return
                discovered[info.serviceName] = info
                setNearbyCount(discovered.size)
                if (!exchange.isLocked) setStatus(DeviceSyncPhase.FOUND, "Found Nuvori on Wi-Fi. Verifying it is paired.")
                signals.trySend(Unit)
            }
        }
        discovery = discover
        if (android.os.Build.VERSION.SDK_INT >= 33)
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, null, mainExecutor, discover)
        else nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discover)
    }

    private fun stopDiscovery() {
        val oldDiscovery = discovery.also { discovery = null }
        oldDiscovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        val oldRegistration = registration.also { registration = null }
        oldRegistration?.let { runCatching { nsd.unregisterService(it) } }
        endpoints.clear()
        discovered.clear()
        setNearbyCount(0)
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
    }

    private fun setPeerStatus(deviceId: String, phase: DeviceSyncPhase, detail: String) {
        if (activeInstance !== this) return
        if (isPaused(this) && !manualAttempt) return
        mutablePeerStatus.update { it + (deviceId to DevicePeerStatus(phase, detail)) }
    }

    private fun setNearbyCount(count: Int) {
        if (activeInstance !== this) return
        mutableNearbyCount.value = count
    }

    private fun clearConnectedPeers(detail: String) {
        if (activeInstance !== this) return
        mutablePeerStatus.update { states -> states.mapValues { (_, state) ->
            if (state.phase != DeviceSyncPhase.ATTENTION)
                DevicePeerStatus(DeviceSyncPhase.WAITING, detail) else state
        } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Wi-Fi could not reach a device. [SyncOverviewRules.isReachFailure] keeps these apart from local problems. */
        const val NO_DEVICE_ANSWERED = "No paired device answered. Check Wi-Fi on both devices and try again."
        const val CONNECTION_CLOSED = "The connection closed before the check finished. Retrying on Wi-Fi."
        const val PEER_CONNECTION_CLOSED = "Connection closed before the check finished."
        /** L5: this group's Wi-Fi port, in 20000-32767 ([SyncPorts]). */
        internal fun syncPort(vaultId: String): Int = SyncPorts.port(vaultId)
        /** L5: how often the phone checks whether a paired device may still use the older port. */
        private const val LEGACY_PORT_CHECK_MS = 10_000L

        /** L5: the port a paired device was last reached on, when it differs from [syncPort]. */
        private fun peerPort(context: Context, deviceId: String): Int? =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getInt("peer_port_$deviceId", 0).takeIf { it in 1024..65535 }

        private fun rememberPeerPort(context: Context, deviceId: String, port: Int) {
            if (port !in 1024..65535 || peerPort(context, deviceId) == port) return
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putInt("peer_port_$deviceId", port).apply()
        }
        private const val PRE_AUTH_TIMEOUT_MS = 15_000
        /** L-06: the whole check before a device proves membership, however slowly it sends. */
        private const val PRE_AUTH_DEADLINE_MS = 20_000L
        /** L-06: how long a proved device waits for another exchange; below the peers' 15 s read timeout. */
        private const val LOCK_WAIT_MS = 8_000L
        private const val SESSION_TIMEOUT_MS = 30_000
        /** M11: the longest a whole Wi-Fi session may take; a batch limit splits longer transfers. */
        private const val SESSION_DEADLINE_MS = 10L * 60 * 1000
        private const val WATCH_EVERY_MS = 5_000L
        private const val LOCK_POLL_MS = 50L
        private const val TAG = "NuvoriSync"
        /** M12: a device sent something this one refuses; updating or re-pairing fixes it, not Wi-Fi. */
        const val DEVICE_REFUSED = "A paired device sent changes Nuvori here can't accept. Update Nuvori on both devices."
        const val PEER_REFUSED = "Sent changes this device can't accept. Update Nuvori on both. If it keeps happening, pair it again."
        /** M12: the other device refused what this one sent, and said so ([SyncRefusal]). */
        const val DEVICE_REFUSED_OURS = "A paired device can't accept changes from this device. Update Nuvori on both devices."
        const val PEER_REFUSED_OURS = "Can't accept changes from this device. Update Nuvori on both. If it keeps happening, pair it again."
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
        internal const val SYNC_NOW = "com.privatevault.app.SYNC_NOW"
        internal const val SYNC_ON_OPEN = "com.privatevault.app.SYNC_ON_OPEN"
        private const val EXTRA_FOREGROUND = "com.privatevault.app.SYNC_FOREGROUND"
        /** How long a quiet pass (on opening, after an edit) looks for devices before it stops (H3). */
        private const val QUIET_ATTEMPT_MS = 120_000L
        private const val PREFERENCES = "sync-service"
        private const val NOTIFICATION_ID = 410
        /** The "Device sync paused" notification of 2.2.10 and older. Nuvori no longer posts it. */
        private const val LEGACY_PAUSED_NOTIFICATION_ID = 411

        private fun settingsPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SYNC_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        internal const val CHANNEL_ID = CHANNEL

        /** Low importance: no sound, no pop-up. Android raises a minimum-importance foreground channel to low. */
        internal fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Auto sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while Auto sync runs. Android needs it to keep sync running in the background."
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                })
        }

        /** Removes the "Device sync paused" notification that older versions left in the shade. */
        fun clearLegacyNotifications(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(LEGACY_PAUSED_NOTIFICATION_ID) }
        }

        private fun notificationsOn(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

        /** Starts the service the way [syncRun] says it runs: a one-time pass never becomes a foreground service. */
        private fun launch(context: Context, action: String? = null) {
            val intent = Intent(context, LanSyncService::class.java).setAction(action)
            val running = activeInstance
            val autoRunning = running != null && running.foreground && !running.manualAttempt
            val run = syncRun(action, isPaused(context), notificationsOn(context), autoRunning)
            if (run == SyncRun.AUTO && autoRunning) { running!!.signals.trySend(Unit); return }
            if (run == SyncRun.ONE_PASS) context.startService(intent)
            else context.startForegroundService(intent.putExtra(EXTRA_FOREGROUND, true))
        }

        val SYNC_INTERVALS = listOf(30_000L, 60_000L, 300_000L, 900_000L)
        fun syncInterval(context: Context): Long = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getLong("interval_ms", SYNC_INTERVALS.first()).takeIf { it in SYNC_INTERVALS } ?: SYNC_INTERVALS.first()
        fun setSyncInterval(context: Context, interval: Long) {
            require(interval in SYNC_INTERVALS)
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putLong("interval_ms", interval).apply()
            if (!context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false)) launch(context)
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
        /**
         * Keeps a verified signed removal of this device (from Wi-Fi or from the cloud drive) for the
         * unlocked app, which then leaves the group locally. A running service stops.
         */
        internal fun recordRemoval(context: Context, notice: RemovalNotice.Removed) {
            val mirror = runCatching { store(context).snapshot() }.getOrNull()
            val name = mirror?.members?.firstOrNull { it.deviceId == notice.issuerDeviceId }?.displayName
                ?.takeIf { it.isNotBlank() && it != "Android device" && it != "This device" }.orEmpty()
            runCatching {
                store(context).recordRemoval(SyncRemovalNotice(mirror?.vaultId.orEmpty(), notice.issuerDeviceId, name,
                    System.currentTimeMillis()))
            }
            mutableRemovalEvents.value = mutableRemovalEvents.value + 1
            activeInstance?.stopAfterRemoval()
        }

        internal suspend fun publishCredentialChanges(context: Context, database: com.privatevault.app.data.VaultDatabase) {
            try {
                store(context).publish(database)
                // Cloud sync does not depend on Wi-Fi sync running or being paused.
                CloudSyncRunner.request(context)
                val running = activeInstance
                if (!isPaused(context)) {
                    if (running != null) running.signals.trySend(Unit)
                    else try { start(context) }
                    // L7: Android refuses a start from the background. The change is saved and Drive was
                    // asked to upload it, so this is not a problem; Wi-Fi sync starts at the next open.
                    catch (refused: IllegalStateException) {
                        if (activeInstance == null) mutableStatus.value = DeviceSyncStatus(DeviceSyncPhase.OFF, BACKGROUND_START_REFUSED)
                    }
                } else { publishedWhilePaused = true; quietPass(context) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableStatus.value = DeviceSyncStatus(DeviceSyncPhase.ATTENTION,
                "Saved changes need another check. Open Nuvori and tap Sync now.") }
        }
        fun start(context: Context, resume: Boolean = false) {
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            if (resume) preferences.edit().putBoolean("paused", false).apply()
            if (!preferences.getBoolean("paused", false)) launch(context)
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
        }

        fun isPaused(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("paused", false)

        /**
         * M9: whether automatic sync is actually running in this process. When it is on but Android
         * ended it (a reboot, the app killed, a start it refused), the tile says "Not running".
         */
        fun isAutoRunning(): Boolean = activeInstance?.let { it.foreground && !it.manualAttempt } == true

        /** This device is active in a group with at least one other active device. */
        internal fun inPairedGroup(context: Context): Boolean {
            val mirror = runCatching { store(context).snapshot() }.getOrNull() ?: return false
            val active = mirror.members.filter { it.status == MemberStatus.ACTIVE.name }.map { it.deviceId }
            return mirror.localDeviceId in active && active.size >= 2
        }

        /**
         * M9: brings automatic sync back after a reboot or an app update, when it was left on. Android
         * allows a foreground start at those moments. Elsewhere it may refuse; that is not an error.
         */
        internal fun restartIfOn(context: Context): Boolean {
            val app = context.applicationContext
            if (isPaused(app) || isAutoRunning() || !notificationsOn(app) || !inPairedGroup(app)) return false
            return runCatching { launch(app) }.isSuccess
        }

        fun allowFutureSync(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("paused", false).apply()
        }

        /**
         * One quiet pass when Nuvori comes to the front ([SyncOnOpen]): Google Drive, and the paired
         * devices on this Wi-Fi even while automatic sync is paused. Runs while locked too.
         */
        fun syncOnOpen(context: Context) {
            val app = context.applicationContext
            if (!SyncOnOpen.enabled(app) || !SyncOnOpen.claim(android.os.SystemClock.elapsedRealtime())) return
            CloudSyncRunner.request(app)
            Thread {
                val mirror = runCatching { store(app).snapshot() }.getOrNull() ?: return@Thread
                val active = mirror.members.filter { it.status == MemberStatus.ACTIVE.name }.map { it.deviceId }
                if (mirror.localDeviceId !in active || active.size < 2) return@Thread
                val running = activeInstance
                if (running != null && !isPaused(app)) running.signals.trySend(Unit)
                else runCatching { launch(app, SYNC_ON_OPEN) }
            }.start()
        }

        /**
         * H3: with Auto sync paused, an edit (or leaving Nuvori after one) runs one quiet pass that
         * looks for the paired devices for about two minutes; a pass already running just looks again.
         */
        fun quietPass(context: Context) {
            val app = context.applicationContext
            Thread {
                val mirror = runCatching { store(app).snapshot() }.getOrNull() ?: return@Thread
                val active = mirror.members.filter { it.status == MemberStatus.ACTIVE.name }.map { it.deviceId }
                if (mirror.localDeviceId !in active || active.size < 2) return@Thread
                val running = activeInstance
                if (running != null) running.signals.trySend(Unit)
                // Android refuses a start from the background; the next open or edit tries again.
                else runCatching { launch(app, SYNC_ON_OPEN) }
            }.start()
        }

        @Volatile private var publishedWhilePaused = false
        internal const val BACKGROUND_START_REFUSED = "Your change is saved. Wi-Fi sync starts again when you open Nuvori."

        /** H3: leaving Nuvori after a change saved while paused looks for the paired devices once more. */
        fun onAppLeft(context: Context) {
            if (!publishedWhilePaused) return
            publishedWhilePaused = false
            if (isPaused(context)) quietPass(context)
        }

        fun syncNow(context: Context) {
            CloudSyncRunner.request(context)
            launch(context, SYNC_NOW)
        }
    }
}
