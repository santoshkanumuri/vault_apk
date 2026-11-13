package com.privatevault.app.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What the Google Drive card shows. [lastError] is a fixed sentence without account or file data. */
data class CloudSyncStatus(val connected: Boolean = false, val account: String = "", val syncing: Boolean = false,
    val lastSyncAt: Long = 0L, val needsSignIn: Boolean = false, val lastError: String? = null)

/**
 * Runs cloud passes one at a time, outside the vault lock: a pass reads and writes only the
 * transport store, so it works while the vault is locked and while Wi-Fi sync is paused. Requests
 * that arrive during a pass coalesce into one follow-up pass.
 */
internal object CloudSyncRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val queued = AtomicBoolean(false)
    private val queuedFull = AtomicBoolean(false)
    /** The first pass after app start lists the whole folder. */
    @Volatile private var listedSinceStart = false
    private val mutableStatus = MutableStateFlow(CloudSyncStatus())
    val status: StateFlow<CloudSyncStatus> = mutableStatus.asStateFlow()

    const val QUEUE_FULL = "Unlock Nuvori to apply waiting changes."
    private const val PHOTO_SPACE = "Photo sync needs more free space. Free space, then sync again."
    /** One request may run a few passes when the work bound of a pass stopped it early. */
    private const val PASSES_PER_REQUEST = 4

    /** Reads the saved connection into [status]. */
    fun refresh(context: Context) = scope.launch {
        val store = LanSyncService.store(context.applicationContext)
        val state = runCatching { store.cloudState() }.getOrDefault(CloudSyncState())
        val vaultId = runCatching { store.snapshot()?.vaultId }.getOrNull()
        mutableStatus.update { status ->
            if (!state.enabled) CloudSyncStatus()
            else status.copy(connected = true, account = state.account,
                lastSyncAt = if (state.vaultId == vaultId) state.lastSyncAt else 0L)
        }
    }

    /**
     * Asks for a pass; it does nothing when Google Drive is not connected. [full] lists the whole
     * folder (manual sync). Safe on the main thread: the store is read on the pass's thread.
     */
    fun request(context: Context, full: Boolean = false) {
        val app = context.applicationContext
        if (full) queuedFull.set(true)
        if (!queued.compareAndSet(false, true)) return
        scope.launch {
            mutex.withLock {
                queued.set(false)
                pass(app, queuedFull.getAndSet(false) || !listedSinceStart)
            }
        }
    }

    /**
     * Saves a new sign-in after [GoogleDriveSignIn] returned [accessToken]: reads the account address,
     * then forgets the cached file list so the next pass lists the whole folder. Returns the address.
     */
    suspend fun connect(context: Context, accessToken: String): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val account = GoogleDrive(object : DriveAccess {
            override fun token() = accessToken
            override fun refresh(stale: String) = GoogleDriveSignIn.clearToken(app, stale)
        }).account()
        mutex.withLock {
            val store = LanSyncService.store(app)
            store.forgetCloud()
            store.updateCloudState { CloudSyncState(enabled = true, account = account) }
            listedSinceStart = false
        }
        mutableStatus.value = CloudSyncStatus(connected = true, account = account)
        account
    }

    /**
     * Signs out on this device: clears Google's cached token and forgets the connection, the file
     * list and the pass state. The encrypted files stay in the Drive for the other devices.
     */
    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        mutex.withLock {
            LanSyncService.store(app).forgetCloud()
            listedSinceStart = false
        }
        mutableStatus.value = CloudSyncStatus()
        // Best effort, without any screen: only a token Google hands out silently can be cleared.
        (GoogleDriveSignIn.authorize(app) as? GoogleDriveSignIn.Outcome.Token)?.let {
            GoogleDriveSignIn.clearToken(app, it.accessToken)
        }
    }

    private suspend fun pass(app: Context, full: Boolean) {
        val store = LanSyncService.store(app)
        val state = runCatching { store.cloudState() }.getOrNull()
        if (state == null || !state.enabled) { mutableStatus.value = CloudSyncStatus(); return }
        val mirror = runCatching { store.snapshot() }.getOrNull() ?: return
        val active = mirror.members.filter { it.status == MemberStatus.ACTIVE.name }
        if (active.none { it.deviceId == mirror.localDeviceId } || active.size < 2) return
        mutableStatus.update { it.copy(connected = true, account = state.account, syncing = true) }
        var error: String? = null
        var needsSignIn = false
        try {
            val drive = GoogleDrive(GoogleDriveAccess(app))
            var round = 0
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val report = CloudSyncPass(store, drive).run(full && round == 0)
                if (full && round == 0) listedSinceStart = true
                round++
                report.removed?.let { LanSyncService.recordRemoval(app, it); return }
                if (report.queueFull) { error = QUEUE_FULL; break }
                if (report.complete || round >= PASSES_PER_REQUEST) break
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CloudException) {
            needsSignIn = failure.error == CloudError.AUTH
            error = failure.error.message
        }
        catch (_: NotCloudMemberException) { }
        catch (_: PhotoStorageFullException) { error = PHOTO_SPACE }
        catch (_: Exception) { error = CloudError.OTHER.message }
        finally {
            val saved = runCatching { store.cloudState() }.getOrDefault(CloudSyncState())
            mutableStatus.update { status ->
                if (!saved.enabled) CloudSyncStatus()
                else status.copy(connected = true, account = saved.account, syncing = false,
                    lastSyncAt = if (saved.vaultId == mirror.vaultId) saved.lastSyncAt else 0L,
                    needsSignIn = needsSignIn, lastError = error)
            }
        }
    }
}
