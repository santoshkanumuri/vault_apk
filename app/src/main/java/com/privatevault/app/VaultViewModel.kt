package com.privatevault.app

import com.privatevault.app.sync.removeOnlyPairedDevice
import com.privatevault.app.sync.leaveSyncGroup
import com.privatevault.app.sync.offerAuthorityTransfer
import com.privatevault.app.sync.acceptAuthorityTransfer
import com.privatevault.app.sync.completeAuthorityTransfer
import com.privatevault.app.sync.cancelAuthorityTransfer

import androidx.room.withTransaction
import com.privatevault.app.sync.captureEntryUpserts

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privatevault.app.backup.VaultBackupManager
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.LoginImportAction
import com.privatevault.app.data.LoginImportMatch
import com.privatevault.app.data.LoginImportRequest
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.security.EncryptedPhotoStore
import com.privatevault.app.security.BiometricGate
import com.privatevault.app.security.VaultKeyManager
import com.privatevault.app.security.PasswordColumnMapping
import com.privatevault.app.security.PasswordColumnMappingRequired
import com.privatevault.app.security.PasswordColumnPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import java.util.UUID

enum class LockReason { STARTUP, BACKGROUND, INACTIVITY, SCREEN_OFF, MANUAL }

data class AuthorityTransferState(val targetDeviceId: String, val accepted: Boolean)

internal data class DraftPhoto(val id: String, val encryptedFileName: String, val encryptedThumbnailFileName: String)

sealed interface VaultStatus {
    data object NeedsSetup : VaultStatus
    data class Locked(val reason: LockReason, val canUseBiometric: Boolean) : VaultStatus
    data object Unlocked : VaultStatus
}

enum class PasswordImportStatus { NEW, ALREADY_SAVED, PASSWORD_DIFFERS, AMBIGUOUS }

enum class PasswordImportFilter(val label: String) {
    ALL("All"),
    NEW("New"),
    ALREADY_SAVED("Already saved"),
    NEEDS_DECISION("Needs decision")
}

internal fun PasswordImportFilter.matches(status: PasswordImportStatus): Boolean = when (this) {
    PasswordImportFilter.ALL -> true
    PasswordImportFilter.NEW -> status == PasswordImportStatus.NEW
    PasswordImportFilter.ALREADY_SAVED -> status == PasswordImportStatus.ALREADY_SAVED
    PasswordImportFilter.NEEDS_DECISION -> status == PasswordImportStatus.PASSWORD_DIFFERS || status == PasswordImportStatus.AMBIGUOUS
}

enum class PasswordImportDecision { KEEP_SAVED, USE_IMPORTED }

data class PasswordImportItem(
    val rowId: String,
    val matchedSavedEntryId: String?,
    val label: String,
    val status: PasswordImportStatus,
    val decision: PasswordImportDecision? = null,
    val selected: Boolean = status == PasswordImportStatus.NEW
)

data class PasswordImportPreview(
    val items: List<PasswordImportItem>,
    val incomingRows: Int,
    val duplicateRows: Int,
    val newCount: Int,
    val alreadySavedCount: Int,
    val conflictCount: Int,
    val ambiguousCount: Int
)

internal data class PasswordDuplicateReview(
    val groups: List<com.privatevault.app.security.ExactPasswordDuplicateGroup>,
    val selectedIds: Set<String> = emptySet()
) {
    val duplicateCount: Int get() = groups.sumOf { it.duplicates.size }
}

enum class PasskeyTransferStatus { NEW, ALREADY_SAVED, CONFLICT }

data class PasskeyTransferItem(
    val id: String,
    val rpId: String,
    val username: String,
    val status: PasskeyTransferStatus
)

data class PasskeyTransferPreview(
    val exporter: String,
    val sourcePackage: String,
    val items: List<PasskeyTransferItem>,
    val unsupported: Int
)

internal data class PasswordImportMappingRequest(
    val columns: List<PasswordColumnPreview>,
    val suggested: PasswordColumnMapping
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val keyManager = VaultKeyManager(application)
    private val biometricGate = BiometricGate(application)
    private val photoStore = EncryptedPhotoStore(application)
    private val deviceIdentityStore = com.privatevault.app.sync.AndroidDeviceIdentityStore(application)
    private val devicePairing = com.privatevault.app.sync.AndroidPairing(application)
    private val lockedSyncStore = com.privatevault.app.sync.LanSyncService.store(application)
    private var incomingSyncJob: kotlinx.coroutines.Job? = null
    val devicePairingState = devicePairing.state
    val deviceSyncStatus: kotlinx.coroutines.flow.StateFlow<com.privatevault.app.sync.DeviceSyncStatus> =
        com.privatevault.app.sync.LanSyncService.status
    val devicePeerStatus = com.privatevault.app.sync.LanSyncService.peerStatus
    val nearbySyncServices = com.privatevault.app.sync.LanSyncService.nearbyCount
    private val _syncConflicts = kotlinx.coroutines.flow.MutableStateFlow<List<com.privatevault.app.sync.SyncConflictReview>>(emptyList())
    val syncConflicts = _syncConflicts.asStateFlow()
    private val _rejectedSyncChanges = MutableStateFlow(0)
    val rejectedSyncChanges = _rejectedSyncChanges.asStateFlow()
    private val _pairedDevices = MutableStateFlow<List<com.privatevault.app.data.SyncMembershipEntity>>(emptyList())
    val pairedDevices = _pairedDevices.asStateFlow()
    private val _localSyncDevice = MutableStateFlow<com.privatevault.app.data.SyncMembershipEntity?>(null)
    val localSyncDevice = _localSyncDevice.asStateFlow()
    private val _canRemoveOnlyPeer = MutableStateFlow(false)
    val canRemoveOnlyPeer = _canRemoveOnlyPeer.asStateFlow()
    private val _syncManagerDeviceId = MutableStateFlow<String?>(null)
    val syncManagerDeviceId = _syncManagerDeviceId.asStateFlow()
    private val _authorityTransfer = MutableStateFlow<AuthorityTransferState?>(null)
    val authorityTransfer = _authorityTransfer.asStateFlow()
    private val _canHostDevicePairing = MutableStateFlow(false)
    val canHostDevicePairing = _canHostDevicePairing.asStateFlow()
    fun deviceSyncProgress(deviceId: String): String {
        val mirror = lockedSyncStore.snapshot() ?: return "No sync status yet"
        val local = mirror.heads.firstOrNull { it.deviceId == mirror.localDeviceId }?.sequence ?: 0L
        if (local == 0L) return "No changes made here since pairing"
        val progress = mirror.peerProgress[deviceId] ?: return "Waiting to check this device"
        val received = progress.received[mirror.localDeviceId]?.sequence ?: 0L
        val applied = progress.applied[mirror.localDeviceId]?.sequence ?: 0L
        return when {
            applied >= local -> "All your changes applied on this device"
            received >= local -> "All your changes received by this device; waiting to apply"
            else -> "Changes from this device: $received of $local received, $applied applied"
        }
    }

    internal fun deviceSyncCounts(deviceId: String): com.privatevault.app.sync.PeerSyncCounts {
        val mirror = lockedSyncStore.snapshot() ?: return com.privatevault.app.sync.PeerSyncCounts(0, 0, 0, 0)
        return com.privatevault.app.sync.peerSyncCounts(mirror.localDeviceId, deviceId,
            lockedSyncStore.progress(), mirror.peerProgress[deviceId])
    }

    fun deviceSyncAddress(deviceId: String): String =
        lockedSyncStore.snapshot()?.peerAddresses?.get(deviceId).orEmpty()
    fun deviceSyncContact(deviceId: String): Pair<Long, Long> {
        val mirror = lockedSyncStore.snapshot()
        return (mirror?.lastContactAt?.get(deviceId) ?: 0L) to
            (mirror?.lastExchangeAt?.get(deviceId) ?: 0L)
    }
    fun deviceSyncQueueStatus(): String? {
        val photos = lockedSyncStore.missingPhotos().size
        val waiting = lockedSyncStore.queuedCount()
        return when {
            photos > 0 -> "Photo data waiting for transfer"
            waiting > 0 -> "$waiting encrypted changes waiting to apply"
            else -> null
        }
    }
    private val watchSyncPublisher = com.privatevault.app.watch.WatchSyncPublisher(application)
    private var lastWatchAccounts: List<com.privatevault.app.watch.WatchAccount>? = null
    private var watchPublishJob: Job? = null
    private var preparedRestore: com.privatevault.app.backup.PreparedRestore? = null
    private val _restoreSummary = MutableStateFlow<String?>(null)
    val restoreSummary = _restoreSummary.asStateFlow()
    private var pendingLoginRequests = emptyList<LoginImportRequest>()
    private var pendingPasswordImportUri: Uri? = null
    private val _importPreview = MutableStateFlow<PasswordImportPreview?>(null)
    val importPreview = _importPreview.asStateFlow()
    private val _importMapping = MutableStateFlow<PasswordImportMappingRequest?>(null)
    internal val importMapping = _importMapping.asStateFlow()
    private val _passwordDuplicateReview = MutableStateFlow<PasswordDuplicateReview?>(null)
    internal val passwordDuplicateReview = _passwordDuplicateReview.asStateFlow()
    private var pendingTransferredPasskeys = emptyList<com.privatevault.app.data.VaultPasskey>()
    private val _passkeyTransferPreview = MutableStateFlow<PasskeyTransferPreview?>(null)
    val passkeyTransferPreview = _passkeyTransferPreview.asStateFlow()

    fun cancelPasswordImport() {
        pendingLoginRequests = emptyList()
        pendingPasswordImportUri = null
        _importPreview.value = null
        _importMapping.value = null
    }

    internal fun checkPasswordDuplicates() = securedLaunch("Could not check for duplicates.") {
        val groups = com.privatevault.app.security.exactPasswordDuplicateGroups(dao().allEntries())
        if (groups.isEmpty()) {
            _passwordDuplicateReview.value = null
            notify("No exact password duplicates found.", StatusKind.INFO)
        } else {
            _passwordDuplicateReview.value = PasswordDuplicateReview(groups)
        }
    }

    internal fun setPasswordDuplicateSelected(id: String, selected: Boolean) {
        val review = _passwordDuplicateReview.value ?: return
        if (review.groups.none { group -> group.duplicates.any { it.entry.id == id } }) return
        _passwordDuplicateReview.value = review.copy(selectedIds = if (selected) review.selectedIds + id else review.selectedIds - id)
    }

    internal fun setAllPasswordDuplicatesSelected(selected: Boolean) {
        val review = _passwordDuplicateReview.value ?: return
        val ids = review.groups.flatMap { it.duplicates }.mapTo(linkedSetOf()) { it.entry.id }
        _passwordDuplicateReview.value = review.copy(selectedIds = if (selected) ids else emptySet())
    }

    internal fun cancelPasswordDuplicateReview() {
        _passwordDuplicateReview.value = null
    }

    internal fun deleteSelectedPasswordDuplicates() = securedLaunch("Could not delete the duplicates.") {
        val selectedIds = _passwordDuplicateReview.value?.selectedIds.orEmpty()
        require(selectedIds.isNotEmpty()) { "Select at least one duplicate." }
        val db = requireNotNull(database)
        val deleted = db.withTransaction {
            val entries = selectedIds.map { id -> requireNotNull(db.dao().entry(id)) { "Passwords changed after review" }.entry }
            val count = db.dao().deleteExactPasswordDuplicates(selectedIds)
            val writer = com.privatevault.app.sync.LocalEntryChangeWriter(db, deviceIdentityStore)
            entries.forEach { writer.recordDeletedEntry(it, requireNotNull(sessionKey)) }
            count
        }
        cancelPasswordDuplicateReview()
        refresh()
        notify("Deleted $deleted exact password ${if (deleted == 1) "duplicate" else "duplicates"}.", StatusKind.SUCCESS)
    }

    fun cancelCredentialTransfer() {
        pendingTransferredPasskeys = emptyList()
        _passkeyTransferPreview.value = null
    }

    fun previewCredentialTransfer(json: String, sourcePackage: String) = securedLaunch("Could not read the credential transfer.") {
        cancelCredentialTransfer()
        val activeKey = requireNotNull(sessionKey)
        val transfer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.privatevault.app.passkeys.readCxfPasskeys(json)
        }
        if (sessionKey !== activeKey || _status.value !is VaultStatus.Unlocked) return@securedLaunch
        val existing = dao().allPasskeys().associateBy { it.id }
        val items = transfer.passkeys.map { incoming ->
            val saved = existing[incoming.id]
            val status = when {
                saved == null -> PasskeyTransferStatus.NEW
                saved.copy(createdAt = incoming.createdAt) == incoming -> PasskeyTransferStatus.ALREADY_SAVED
                else -> PasskeyTransferStatus.CONFLICT
            }
            PasskeyTransferItem(incoming.id, incoming.rpId, incoming.username, status)
        }
        pendingTransferredPasskeys = transfer.passkeys
        _passkeyTransferPreview.value = PasskeyTransferPreview(
            transfer.exporterDisplayName, sourcePackage.take(200), items, transfer.unsupportedPasskeys
        )
    }

    fun confirmCredentialTransfer() = securedLaunch("Could not import the passkeys.") {
        val preview = requireNotNull(_passkeyTransferPreview.value)
        require(preview.items.none { it.status == PasskeyTransferStatus.CONFLICT }) {
            "Resolve passkey credential-ID conflicts before importing."
        }
        val db = requireNotNull(database)
        val result = db.withTransaction {
            val before = db.dao().allPasskeys().map { it.id }.toSet()
            val imported = db.dao().importPasskeys(pendingTransferredPasskeys)
            val writer = com.privatevault.app.sync.LocalPasskeyChangeWriter(db, deviceIdentityStore)
            db.dao().allPasskeys().filter { it.id !in before }.forEach {
                writer.recordSaved(it, requireNotNull(sessionKey))
            }
            imported
        }
        cancelCredentialTransfer()
        refreshPasskeys()
        notify("Imported ${quantity(result.added, "passkey")}. ${result.alreadySaved} already saved.",
            if (result.added > 0) StatusKind.SUCCESS else StatusKind.INFO)
    }

    fun credentialTransferFailed(cancelled: Boolean) {
        externalFlowActive = false
        touch()
        if (!cancelled) notify("No compatible passkey transfer was available. The source manager must support Android credential transfer.", StatusKind.WARNING)
    }

    internal fun previewPasswordImport(uri: Uri, mapping: PasswordColumnMapping? = null) = securedLaunch("Could not read that file.") {
        cancelPasswordImport()
        val activeKey = requireNotNull(sessionKey)
        val parsed = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).buffered().use {
                    com.privatevault.app.security.readBrowserPasswords(it, mapping)
                }
            }
        } catch (required: PasswordColumnMappingRequired) {
            if (sessionKey === activeKey && _status.value is VaultStatus.Unlocked) {
                pendingPasswordImportUri = uri
                _importMapping.value = PasswordImportMappingRequest(required.columns, required.suggested)
            }
            return@securedLaunch
        }
        val unique = com.privatevault.app.security.deduplicateImportedLogins(parsed)
        if (sessionKey !== activeKey || _status.value !is VaultStatus.Unlocked) return@securedLaunch
        val existing = dao().loginAndCodeEntries().filter { it.type == com.privatevault.app.data.EntryType.PASSWORD }
        if (sessionKey === activeKey && _status.value is VaultStatus.Unlocked) {
            val requests = unique.map { entry ->
                val matches = existing.filter { com.privatevault.app.security.sameImportedAccount(it, entry) }
                val action = when {
                    matches.isEmpty() -> LoginImportAction.ADD
                    matches.size > 1 -> LoginImportAction.SKIP_AMBIGUOUS
                    com.privatevault.app.security.sameImportedLogin(matches.single(), entry) -> LoginImportAction.SKIP_EXACT
                    else -> LoginImportAction.KEEP_SAVED
                }
                LoginImportRequest(entry, action, matches.map(LoginImportMatch::from), matches.singleOrNull()?.id)
            }
            val items = requests.map { request ->
                val status = when (request.action) {
                    LoginImportAction.ADD -> PasswordImportStatus.NEW
                    LoginImportAction.SKIP_EXACT -> PasswordImportStatus.ALREADY_SAVED
                    LoginImportAction.KEEP_SAVED, LoginImportAction.USE_IMPORTED -> PasswordImportStatus.PASSWORD_DIFFERS
                    LoginImportAction.SKIP_AMBIGUOUS -> PasswordImportStatus.AMBIGUOUS
                }
                PasswordImportItem(request.incoming.id, request.matchedSavedEntryId,
                    "${request.incoming.title} · ${request.incoming.primaryValue}", status,
                    if (status == PasswordImportStatus.PASSWORD_DIFFERS) PasswordImportDecision.KEEP_SAVED else null)
            }
            pendingLoginRequests = requests
            _importPreview.value = PasswordImportPreview(items, parsed.size, parsed.size - unique.size,
                items.count { it.status == PasswordImportStatus.NEW },
                items.count { it.status == PasswordImportStatus.ALREADY_SAVED },
                items.count { it.status == PasswordImportStatus.PASSWORD_DIFFERS },
                items.count { it.status == PasswordImportStatus.AMBIGUOUS })
        }
    }

    internal fun retryPasswordImport(mapping: PasswordColumnMapping) {
        val uri = pendingPasswordImportUri ?: return
        previewPasswordImport(uri, mapping)
    }

    fun setPasswordImportDecision(id: String, decision: PasswordImportDecision) {
        val preview = _importPreview.value ?: return
        if (preview.items.none { it.rowId == id && it.status == PasswordImportStatus.PASSWORD_DIFFERS }) return
        _importPreview.value = preview.copy(items = preview.items.map { if (it.rowId == id) it.copy(decision = decision) else it })
        pendingLoginRequests = pendingLoginRequests.map { request ->
            if (request.incoming.id != id) request else request.copy(action = when (decision) {
                PasswordImportDecision.KEEP_SAVED -> LoginImportAction.KEEP_SAVED
                PasswordImportDecision.USE_IMPORTED -> LoginImportAction.USE_IMPORTED
            })
        }
    }

    fun setAllPasswordImportDecisions(decision: PasswordImportDecision) {
        _importPreview.value?.items?.filter { it.status == PasswordImportStatus.PASSWORD_DIFFERS }
            ?.forEach { setPasswordImportDecision(it.rowId, decision) }
    }

    fun setPasswordImportSelected(id: String, selected: Boolean) {
        val preview = _importPreview.value ?: return
        if (preview.items.none { it.rowId == id && it.status == PasswordImportStatus.NEW }) return
        _importPreview.value = preview.copy(items = preview.items.map {
            if (it.rowId == id) it.copy(selected = selected) else it
        })
    }

    fun confirmPasswordImport() = securedLaunch("Could not import the logins.") {
        val preview = _importPreview.value
        if (preview != null) {
            require(preview?.items?.filter { it.status == PasswordImportStatus.PASSWORD_DIFFERS }
                ?.all { it.decision != null } == true) { "Choose how to handle every changed password." }
            val selectedNewRows = preview.items.filter { it.status == PasswordImportStatus.NEW && it.selected }
                .mapTo(hashSetOf()) { it.rowId }
            val requests = pendingLoginRequests.filter { it.action != LoginImportAction.ADD || it.incoming.id in selectedNewRows }
            val result = requireNotNull(database).let { db ->
                db.captureEntryUpserts(deviceIdentityStore, requireNotNull(sessionKey)) {
                    importLogins(requests)
                }
            }
            cancelPasswordImport()
            refresh()
            val duplicateRows = preview?.duplicateRows ?: 0
            notify("Added ${quantity(result.added, "login")} and updated ${result.updated}. " +
                "Skipped ${quantity(result.skippedExact + duplicateRows, "duplicate")} and ${quantity(result.skippedConflicts, "incoming password change")}. " +
                "Delete the readable export file after checking your logins.",
                if (result.added + result.updated > 0) StatusKind.SUCCESS else StatusKind.INFO)
        }
    }
    fun cancelRestore() {
        preparedRestore?.close()
        preparedRestore = null
        _restoreSummary.value = null
    }
    private var pendingCameraFile: java.io.File? = null
    private var pendingCameraUri: Uri? = null

    fun prepareCamera(): Uri? {
        if (_status.value !is VaultStatus.Unlocked) return null
        clearCamera()
        val context = getApplication<Application>()
        val directory = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        return runCatching {
            val file = java.io.File.createTempFile("capture-", ".jpg", directory)
            pendingCameraFile = file
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
                .also { pendingCameraUri = it; externalFlowActive = true }
        }.getOrElse { clearCamera(); notify("Could not open the camera.", StatusKind.ERROR); null }
    }

    fun clearCamera() {
        pendingCameraUri?.let {
            getApplication<Application>().revokeUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        pendingCameraUri = null
        pendingCameraFile?.delete()
        pendingCameraFile = null
    }

    fun finishCamera(entryId: String, success: Boolean) = viewModelScope.launch {
        externalFlowActive = false
        val uri = pendingCameraUri
        try {
            if (success && uri != null && _status.value is VaultStatus.Unlocked) addPhoto(entryId, uri).join()
        } finally { clearCamera() }
    }
    private var database: VaultDatabase? = null
    private var sessionKey: ByteArray? = null
    private var inactivityJob: Job? = null
    private var backgroundJob: Job? = null
    private var backgroundDeadline: Long? = null
    private var inactivityDeadline = 0L
    private var lastActivityElapsed = 0L
    private val _securitySettings = MutableStateFlow(com.privatevault.app.data.VaultSettings())
    val securitySettings = _securitySettings.asStateFlow()
    @Volatile private var backgrounded = false
    private var externalReturnPending = false

    private val _status = MutableStateFlow<VaultStatus>(
        if (keyManager.isInitialized) VaultStatus.Locked(LockReason.STARTUP, biometricGate.hasValidDailySession) else VaultStatus.NeedsSetup
    )
    val status = _status.asStateFlow()
    private val _entries = MutableStateFlow<List<EntryWithDetails>>(emptyList())
    val entries = _entries.asStateFlow()
    private val _passkeys = MutableStateFlow<List<com.privatevault.app.data.PasskeySummary>>(emptyList())
    val passkeys = _passkeys.asStateFlow()
    private val _groups = MutableStateFlow<List<VaultGroup>>(emptyList())
    val groups = _groups.asStateFlow()
    private val _notice = MutableStateFlow<UserNotice?>(null)
    val notice = _notice.asStateFlow()

    /** Shows a short confirmation or problem report. Never include passwords, codes, keys or card numbers. */
    internal fun notify(text: String, kind: StatusKind = StatusKind.INFO) { _notice.value = UserNotice(text, kind) }
    private val _requestDailyBiometric = MutableStateFlow(false)
    val requestDailyBiometric = _requestDailyBiometric.asStateFlow()

    var externalFlowActive = false
    private val preferences = application.getSharedPreferences("vault_preferences", Application.MODE_PRIVATE)
    private val _lightMode = MutableStateFlow(preferences.getBoolean("light_mode", false))
    val lightMode = _lightMode.asStateFlow()

    fun setLightMode(enabled: Boolean) {
        if (preferences.edit().putBoolean("light_mode", enabled).commit()) _lightMode.value = enabled
        else notify("Could not save appearance preference.", StatusKind.ERROR)
        persistSettings()
    }

    private val _nfcEnabled = MutableStateFlow(preferences.getBoolean("nfc_enabled", false))
    val nfcEnabled = _nfcEnabled.asStateFlow()
    val nfcSupported = application.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
    private val _nfcScanning = MutableStateFlow(false)
    val nfcScanning = _nfcScanning.asStateFlow()
    private val _nfcResult = MutableStateFlow<com.privatevault.app.nfc.CardImport?>(null)
    val nfcResult = _nfcResult.asStateFlow()

    fun setNfcEnabled(enabled: Boolean) {
        val value = enabled && nfcSupported
        if (!value) cancelNfcScan()
        if (preferences.edit().putBoolean("nfc_enabled", value).commit()) _nfcEnabled.value = value
        else { _nfcEnabled.value = false; notify("Could not save NFC preference. NFC is off for this session.", StatusKind.ERROR) }
        persistSettings()
    }

    private fun persistSettings() {
        val db = database ?: return
        viewModelScope.launch {
            runCatching {
                val current = db.dao().settings() ?: com.privatevault.app.data.VaultSettings(
                    vaultId = UUID.randomUUID().toString(),
                )
                db.dao().saveSettings(
                    current.copy(lightMode = _lightMode.value, nfcEnabled = _nfcEnabled.value),
                )
            }
        }
    }

    fun setBackgroundTimeout(value: Long) = updateSecuritySettings(value, null, null)
    fun setInactivityTimeout(value: Long) = updateSecuritySettings(null, value, null)
    fun setMasterPasswordInterval(value: Long) = updateSecuritySettings(null, null, value)

    fun connectWatch() = securedLaunch("Could not connect the watch.") {
        watchPublishJob?.cancel()
        val db = requireNotNull(database)
        val key = requireNotNull(sessionKey).copyOf()
        try {
            val current = requireNotNull(db.dao().settings())
            val accounts = db.dao().authenticatorEntries()
            val delivered = watchSyncPublisher.pair(current.vaultId, key, accounts)
            if (database !== db || _status.value !is VaultStatus.Unlocked) return@securedLaunch
            val updated = current.copy(watchSyncEnabled = true)
            db.dao().saveSettings(updated)
            _securitySettings.value = updated
            lastWatchAccounts = accounts.map(::watchAccount)
            refresh()
            if (delivered) notify("Codes saved on the watch.", StatusKind.SUCCESS)
            else notify("Watch paired. Codes are queued; open Nuvori on the watch to receive them.", StatusKind.INFO)
        } finally { key.fill(0) }
    }

    fun removeWatchCodes() = securedLaunch("Could not remove the watch codes.") {
        watchPublishJob?.cancel()
        val db = requireNotNull(database)
        val key = requireNotNull(sessionKey).copyOf()
        try {
            val current = requireNotNull(db.dao().settings())
            _securitySettings.value = current.copy(watchSyncEnabled = false)
            try { watchSyncPublisher.publish(current.vaultId, key, emptyList()) }
            catch (failure: Exception) { _securitySettings.value = current; throw failure }
            if (database !== db || _status.value !is VaultStatus.Unlocked) return@securedLaunch
            val updated = current.copy(watchSyncEnabled = false)
            db.dao().saveSettings(updated)
            _securitySettings.value = updated
            lastWatchAccounts = null
            notify("Removal queued. A disconnected watch will erase its codes when it reconnects.", StatusKind.INFO)
        } finally { key.fill(0) }
    }

    private fun watchAccount(entry: VaultEntry) = com.privatevault.app.watch.WatchAccount(
        entry.id, entry.title, entry.primaryValue, entry.secondaryValue,
        entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod,
    )

    private fun updateSecuritySettings(background: Long?, inactivity: Long?, master: Long?) {
        if (background != null) require(background in com.privatevault.app.security.backgroundTimeouts)
        if (inactivity != null) require(inactivity in com.privatevault.app.security.inactivityTimeouts)
        if (master != null) require(master in com.privatevault.app.security.masterPasswordIntervals)
        val db = database ?: return
        if (master != null && master != _securitySettings.value.masterPasswordIntervalMs) {
            biometricGate.clearDailySession()
            _requestDailyBiometric.value = false
        }
        viewModelScope.launch {
            runCatching {
                val current = db.dao().settings() ?: return@runCatching
                val updated = current.copy(
                    backgroundTimeoutMs = background ?: current.backgroundTimeoutMs,
                    inactivityTimeoutMs = inactivity ?: current.inactivityTimeoutMs,
                    masterPasswordIntervalMs = master ?: current.masterPasswordIntervalMs
                )
                db.dao().saveSettings(updated)
                if (database !== db || _status.value !is VaultStatus.Unlocked) return@runCatching
                _securitySettings.value = updated
                if (inactivity != null) scheduleInactivity()
                notify("Security settings saved", StatusKind.SUCCESS)
            }.onFailure { notify("Could not save security settings.", StatusKind.ERROR) }
        }
    }

    fun requestNfcScan() {
        if (_nfcEnabled.value && nfcSupported && _status.value is VaultStatus.Unlocked && !backgrounded) {
            touch()
            _nfcResult.value = null
            _nfcScanning.value = true
        }
    }

    fun cancelNfcScan() { _nfcScanning.value = false; _nfcResult.value = null }
    fun canReadNfc() = _nfcEnabled.value && _nfcScanning.value && _status.value is VaultStatus.Unlocked && !backgrounded
    fun finishNfcScan(result: com.privatevault.app.nfc.CardImport) {
        if (!canReadNfc()) return
        _nfcScanning.value = false
        _nfcResult.value = result
        touch()
    }
    fun failNfcScan(message: String) { cancelNfcScan(); notify(message, StatusKind.ERROR) }
    fun consumeNfcResult() { _nfcResult.value = null }

    fun setup(password: CharArray, enableNfc: Boolean = false) = viewModelScope.launch {
        runCatching { keyManager.create(password) }
            .onSuccess {
                open(it)
                setNfcEnabled(enableNfc)
                _requestDailyBiometric.value = _securitySettings.value.masterPasswordIntervalMs > 0
            }
            .onFailure { notify(it.userMessage("Could not create the vault"), StatusKind.ERROR) }
        password.fill('\u0000')
    }

    fun unlock(password: CharArray) = viewModelScope.launch {
        runCatching { keyManager.unlock(password) }
            .onSuccess {
                open(it)
                _requestDailyBiometric.value = _securitySettings.value.masterPasswordIntervalMs > 0
            }
            .onFailure { notify("The master password is incorrect.", StatusKind.ERROR) }
        password.fill('\u0000')
    }

    fun requireMasterPasswordForBiometric() {
        val current = _status.value
        if (current is VaultStatus.Locked) _status.value = current.copy(canUseBiometric = false)
        notify("Fingerprint session is unavailable or expired. Enter the master password.", StatusKind.WARNING)
    }

    fun unlockWithBiometric(key: ByteArray) {
        _requestDailyBiometric.value = false
        viewModelScope.launch {
            runCatching { open(key) }
                .onFailure { key.fill(0); biometricGate.clearDailySession(); notify("Biometric session expired. Use the master password.", StatusKind.WARNING) }
        }
    }

    private suspend fun open(key: ByteArray) {
        database?.close()
        sessionKey = key
        database = VaultDatabase.open(getApplication(), key)
        dao().convertCardFoldersToGroups()
        val storedOptions = dao().settings()
        val options = when {
            storedOptions == null -> com.privatevault.app.data.VaultSettings(vaultId = UUID.randomUUID().toString())
            storedOptions.vaultId.isBlank() -> storedOptions.copy(vaultId = UUID.randomUUID().toString())
            else -> storedOptions
        }
        if (storedOptions != null) {
            _lightMode.value = options.lightMode
            _nfcEnabled.value = options.nfcEnabled && nfcSupported
            preferences.edit().putBoolean("light_mode", _lightMode.value).putBoolean("nfc_enabled", _nfcEnabled.value).commit()
        }
        if (storedOptions != options) dao().saveSettings(options.copy(lightMode = _lightMode.value, nfcEnabled = _nfcEnabled.value))
        val identity = deviceIdentityStore.getOrCreate()
        val db = requireNotNull(database)
        db.withTransaction {
            if (db.syncDao().membership(options.vaultId, identity.deviceId) == null) {
                db.syncDao().upsertMembership(com.privatevault.app.data.SyncMembershipEntity.from(
                    com.privatevault.app.sync.DeviceMembership(options.vaultId, identity.deviceId, "This device",
                        identity.publicKeyBase64Url, com.privatevault.app.sync.MemberStatus.ACTIVE,
                        identity.deviceId, 1, 1)))
            }
        }
        _securitySettings.value = options
        _status.value = VaultStatus.Unlocked
        refresh()
        val retained = _entries.value.flatMap { it.photos }.flatMap { listOf(it.encryptedFileName, it.encryptedThumbnailFileName) }.toSet()
        photoStore.cleanupAbandonedRestore(retained)
        touch()
        incomingSyncJob?.cancel()
        incomingSyncJob = viewModelScope.launch {
            while (database === db && _status.value is VaultStatus.Unlocked) {
                kotlinx.coroutines.delay(5_000)
                val membershipWaiting = lockedSyncStore.hasPendingMembership(db)
                if (lockedSyncStore.queuedCount() == 0 && !membershipWaiting) continue
                val syncKey = sessionKey?.copyOf() ?: break
                try {
                    val photoWaiting = lockedSyncStore.missingPhotos().isNotEmpty()
                    val applied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        lockedSyncStore.applyQueued(db, syncKey)
                    }
                    if (!photoWaiting && lockedSyncStore.missingPhotos().isNotEmpty() && !backgrounded)
                        runCatching { com.privatevault.app.sync.LanSyncService.syncNow(getApplication()) }
                    if (database === db && _status.value is VaultStatus.Unlocked &&
                        (applied > 0 || membershipWaiting ||
                            lockedSyncStore.rejectedCount() != _rejectedSyncChanges.value)) refresh()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (database === db && _status.value is VaultStatus.Unlocked)
                        notify("Some synced changes are waiting. Retry after unlocking.", StatusKind.WARNING)
                } finally { syncKey.fill(0) }
            }
        }
    }

    fun dailyBiometricEncryptionCipher(): Cipher? = runCatching { biometricGate.dailyEncryptionCipher() }
        .onFailure { notify("Fingerprint is unavailable. Use the master password next time.", StatusKind.WARNING) }
        .getOrNull()

    fun enableDailyBiometric(cipher: Cipher) {
        val key = sessionKey ?: return
        val interval = _securitySettings.value.masterPasswordIntervalMs
        if (interval == 0L) { _requestDailyBiometric.value = false; return }
        runCatching { biometricGate.enableDailySession(cipher, key, interval) }
            .onSuccess { _requestDailyBiometric.value = false; notify("Fingerprint enabled for the selected interval.", StatusKind.SUCCESS) }
            .onFailure { biometricGate.clearDailySession(); _requestDailyBiometric.value = false; notify("Could not enable fingerprint. Use the master password next time.", StatusKind.ERROR) }
    }

    fun skipDailyBiometric() { _requestDailyBiometric.value = false }

    fun lock(reason: LockReason) {
        incomingSyncJob?.cancel()
        devicePairing.cancel()
        cancelPasswordImport()
        cancelPasswordDuplicateReview()
        cancelCredentialTransfer()
        cancelRestore()
        clearCamera()
        cancelNfcScan()
        externalFlowActive = false
        externalReturnPending = false
        backgroundJob?.cancel()
        backgroundDeadline = null
        if (_status.value !is VaultStatus.Unlocked) return
        inactivityJob?.cancel()
        database?.close()
        database = null
        _entries.value = emptyList()
        lastWatchAccounts = null
        _passkeys.value = emptyList()
        _groups.value = emptyList()
        _pairedDevices.value = emptyList()
        _localSyncDevice.value = null
        _canRemoveOnlyPeer.value = false
        _syncManagerDeviceId.value = null
        _authorityTransfer.value = null
        _canHostDevicePairing.value = false
        _syncConflicts.value = emptyList()
        _rejectedSyncChanges.value = 0
        sessionKey?.fill(0)
        sessionKey = null
        _requestDailyBiometric.value = false
        _status.value = VaultStatus.Locked(reason, biometricGate.hasValidDailySession)
    }

    fun touch() {
        if (_status.value !is VaultStatus.Unlocked || backgrounded || externalReturnPending || externalFlowActive) return
        lastActivityElapsed = android.os.SystemClock.elapsedRealtime()
        scheduleInactivity()
    }

    fun touchFromUser() {
        externalReturnPending = false
        touch()
    }

    private fun scheduleInactivity() {
        if (_status.value !is VaultStatus.Unlocked) return
        inactivityJob?.cancel()
        inactivityDeadline = lastActivityElapsed + _securitySettings.value.inactivityTimeoutMs
        val remaining = inactivityDeadline - android.os.SystemClock.elapsedRealtime()
        if (remaining <= 0) { lock(LockReason.INACTIVITY); return }
        inactivityJob = viewModelScope.launch {
            delay(remaining)
            lock(LockReason.INACTIVITY)
        }
    }

    fun onAppBackgrounded() {
        backgrounded = true
        if (_status.value !is VaultStatus.Unlocked) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (externalFlowActive) externalReturnPending = true
        val deadline = com.privatevault.app.security.backgroundLockDeadline(now, inactivityDeadline, externalFlowActive, _securitySettings.value.backgroundTimeoutMs)
        externalFlowActive = false
        backgroundDeadline = deadline
        if (deadline <= now) { lock(LockReason.BACKGROUND); return }
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            delay((deadline - now).coerceAtLeast(0L))
            lock(LockReason.BACKGROUND)
        }
    }

    fun onAppForegrounded() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (_status.value is VaultStatus.Unlocked && now >= inactivityDeadline) lock(LockReason.INACTIVITY)
        if (backgroundDeadline?.let { now >= it } == true) lock(LockReason.BACKGROUND)
        backgroundJob?.cancel()
        backgroundDeadline = null
        backgrounded = false
        if (_status.value is VaultStatus.Unlocked) scheduleInactivity()
        val activeDatabase = database
        if (activeDatabase != null && _status.value is VaultStatus.Unlocked) viewModelScope.launch {
            // Autofill's separate authenticated activity can save while this screen is away.
            runCatching { activeDatabase.dao().allEntries() }.onSuccess { current ->
                if (database === activeDatabase && _status.value is VaultStatus.Unlocked) _entries.value = current
            }
        }
    }

    fun saveEntry(entry: VaultEntry, groupIds: Set<String>) = securedLaunch("Could not save that entry.") {
        if (entry.type == com.privatevault.app.data.EntryType.AUTHENTICATOR) {
            com.privatevault.app.security.Totp.validate(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod)
        }
        val existed = dao().entry(entry.id) != null
        saveLocalEntry(entry, groupIds)
        refresh()
        notify("${entry.type.noticeName()} ${if (existed) "updated" else "saved"}", StatusKind.SUCCESS)
    }

    internal fun saveEntryWithPhotos(entry: VaultEntry, groupIds: Set<String>, drafts: List<DraftPhoto>) = securedLaunch("Could not save that entry.") {
        val remaining = drafts.toMutableList()
        val key = requireNotNull(sessionKey).copyOf()
        var existed = false
        var photosFailed = false
        try {
            if (entry.type == com.privatevault.app.data.EntryType.AUTHENTICATOR) {
                com.privatevault.app.security.Totp.validate(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod)
            }
            existed = dao().entry(entry.id) != null
            saveLocalEntry(entry, groupIds)
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val writer = com.privatevault.app.sync.LocalPhotoChangeWriter(requireNotNull(database), deviceIdentityStore,
                        lockedSyncStore.photoBlobs, photoStore)
                    for (draft in drafts) {
                        writer.save(VaultPhoto(draft.id, entry.id, draft.encryptedFileName, draft.encryptedThumbnailFileName), key)
                        remaining.remove(draft)
                    }
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                photosFailed = true
                notify("Entry saved, but some photos could not be added. Open the entry to add them again.", StatusKind.WARNING)
            }
        } finally {
            remaining.forEach(::discardDraftPhoto)
            key.fill(0)
            refresh()
        }
        if (!photosFailed) notify("${entry.type.noticeName()} ${if (existed) "updated" else "saved"}", StatusKind.SUCCESS)
    }

    fun refreshAutofillCopy() = securedLaunch { refresh() }

    fun importAuthenticatorAccounts(accounts: List<com.privatevault.app.security.TotpSetup>) = securedLaunch("Could not import the accounts.") {
        require(accounts.isNotEmpty() && accounts.size <= 100)
        val result = requireNotNull(database).let { db ->
            db.captureEntryUpserts(deviceIdentityStore, requireNotNull(sessionKey)) {
                importAuthenticatorAccounts(accounts)
            }
        }
        refresh()
        notify("Imported ${quantity(result.added, "authenticator account")}. ${result.alreadySaved} already saved. ${quantity(result.conflicts, "conflict")} skipped.",
            when {
                result.conflicts > 0 -> StatusKind.WARNING
                result.added == 0 -> StatusKind.INFO
                else -> StatusKind.SUCCESS
            })
    }

    fun refreshPasskeys() = securedLaunch { _passkeys.value = dao().passkeySummaries() }
    fun deletePasskey(id: String) = securedLaunch("Could not delete that passkey.") {
        val passkey = requireNotNull(dao().allPasskeys().firstOrNull { it.id == id }) { "Passkey is missing" }
        com.privatevault.app.sync.LocalPasskeyChangeWriter(requireNotNull(database), deviceIdentityStore)
            .delete(passkey, requireNotNull(sessionKey))
        refreshPasskeys()
        notify("Passkey deleted", StatusKind.SUCCESS)
    }

    fun markOpened(id: String) = securedLaunch {
        dao().markOpened(id, System.currentTimeMillis())
        refresh()
    }

    fun toggleFavorite(entry: VaultEntry) = securedLaunch("Could not update favorites.") {
        val current = requireNotNull(dao().entry(entry.id)) { "Entry is missing" }
        val favorite = !current.entry.favorite
        saveLocalEntry(current.entry.copy(favorite = favorite), current.groups.map { it.id }.toSet())
        refresh()
        notify(if (favorite) "Added to favorites" else "Removed from favorites", StatusKind.SUCCESS)
    }

    fun duplicateEntry(item: EntryWithDetails) = securedLaunch("Could not create a copy.") {
        val source = item.entry
        require(source.type != com.privatevault.app.data.EntryType.AUTHENTICATOR) { "Add each authenticator using its own setup key." }
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            title = "${source.title} copy",
            primaryValue = if (source.type == com.privatevault.app.data.EntryType.CARD) "" else source.primaryValue,
            secondaryValue = "",
            fourthValue = "",
            linkedAuthenticatorId = "",
            favorite = false,
            lastOpenedAt = 0,
            sortOrder = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        saveLocalEntry(duplicate, item.groups.map { it.id }.toSet())
        refresh()
        notify("Copy created", StatusKind.SUCCESS)
    }

    fun deleteEntry(item: EntryWithDetails) = securedLaunch("Could not delete that entry.") {
        com.privatevault.app.sync.LocalEntryChangeWriter(requireNotNull(database), deviceIdentityStore)
            .delete(item.entry, requireNotNull(sessionKey))
        item.photos.forEach {
            photoStore.delete(it.encryptedFileName)
            if (it.encryptedThumbnailFileName.isNotBlank()) photoStore.delete(it.encryptedThumbnailFileName)
        }
        refresh()
        notify("${item.entry.type.noticeName()} deleted", StatusKind.SUCCESS)
    }

    fun addGroup(name: String, notes: String = "") = securedLaunch("Could not save that group.") {
        require(name.isNotBlank()) { "Enter a group name." }
        saveLocalGroup(VaultGroup(name = name.trim(), notes = notes.trim()))
        refresh()
        notify("Group created", StatusKind.SUCCESS)
    }

    fun addFolder(name: String, type: com.privatevault.app.data.EntryType) = securedLaunch("Could not save that folder.") {
        require(name.isNotBlank()) { "Enter a folder name." }
        require(type != com.privatevault.app.data.EntryType.CARD) { "Cards do not use folders." }
        saveLocalGroup(VaultGroup(name = name.trim(), folderType = type))
        refresh()
        notify("Folder created", StatusKind.SUCCESS)
    }

    fun renameFolder(folder: VaultGroup, name: String) = securedLaunch("Could not save that folder.") {
        require(folder.folderType != null && name.isNotBlank()) { "Enter a folder name." }
        saveLocalGroup(folder.copy(name = name.trim()))
        refresh()
        notify("Folder renamed", StatusKind.SUCCESS)
    }

    fun deleteGroup(group: VaultGroup) = securedLaunch(if (group.folderType != null) "Could not delete that folder." else "Could not delete that group.") {
        com.privatevault.app.sync.LocalGroupChangeWriter(requireNotNull(database), deviceIdentityStore)
            .delete(group, requireNotNull(sessionKey))
        refresh()
        notify(if (group.folderType != null) "Folder deleted" else "Group deleted", StatusKind.SUCCESS)
    }

    fun editGroup(group: VaultGroup, name: String, notes: String) = securedLaunch("Could not save that group.") {
        require(name.isNotBlank()) { "Enter a group name." }
        saveLocalGroup(group.copy(name = name.trim(), notes = notes.trim()))
        refresh()
        notify("Group updated", StatusKind.SUCCESS)
    }

    fun setGroupEntries(group: VaultGroup, ids: Set<String>) = securedLaunch("Could not save that group.") {
        val db = requireNotNull(database)
        val key = requireNotNull(sessionKey)
        db.withTransaction {
            val entries = db.dao().allEntries()
            require(ids.all { id -> entries.any { it.entry.id == id } }) { "An entry is missing" }
            val writer = com.privatevault.app.sync.LocalEntryChangeWriter(db, deviceIdentityStore)
            entries.forEach { item ->
                val current = item.groups.map { it.id }.toSet()
                val next = if (item.entry.id in ids) current + group.id else current - group.id
                if (next != current) writer.save(item.entry, next, key)
            }
        }
        refresh()
        notify("Group updated", StatusKind.SUCCESS)
    }

    fun addPhoto(entryId: String, uri: Uri) = securedLaunch("Could not add that photo.") {
        val key = requireNotNull(sessionKey).copyOf()
        val id = UUID.randomUUID().toString()
        val name = "$id.vaultphoto"
        val thumbnailName = "$id.vaultthumb"
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open that image." }
                        photoStore.encrypt(input, name, key)
                    }
                    photoStore.createThumbnail(name, thumbnailName, key)
                    com.privatevault.app.sync.LocalPhotoChangeWriter(requireNotNull(database), deviceIdentityStore,
                        lockedSyncStore.photoBlobs, photoStore).save(VaultPhoto(id, entryId, name, thumbnailName), key)
                } catch (failure: Exception) {
                    photoStore.delete(name); photoStore.delete(thumbnailName)
                    throw failure
                }
            }
        } finally { key.fill(0) }
        refresh()
        notify("Photo added", StatusKind.SUCCESS)
    }

    internal suspend fun stagePhoto(uri: Uri): DraftPhoto {
        val key = requireNotNull(sessionKey).copyOf()
        val id = UUID.randomUUID().toString()
        val draft = DraftPhoto(id, "draft-$id.vaultphoto", "draft-$id.vaultthumb")
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open that image." }
                    photoStore.encrypt(input, draft.encryptedFileName, key)
                }
                photoStore.createThumbnail(draft.encryptedFileName, draft.encryptedThumbnailFileName, key)
            }
            return draft
        } catch (failure: Throwable) {
            discardDraftPhoto(draft)
            throw failure
        } finally { key.fill(0) }
    }

    internal suspend fun finishDraftCamera(success: Boolean): DraftPhoto? {
        externalFlowActive = false
        return try {
            if (success && _status.value is VaultStatus.Unlocked) pendingCameraUri?.let { stagePhoto(it) } else null
        } finally { clearCamera() }
    }

    internal fun discardDraftPhoto(draft: DraftPhoto) {
        photoStore.delete(draft.encryptedFileName)
        photoStore.delete(draft.encryptedThumbnailFileName)
    }

    internal fun loadDraftThumbnail(draft: DraftPhoto): ByteArray? = runCatching {
        photoStore.decryptedBytes(draft.encryptedThumbnailFileName, requireNotNull(sessionKey))
    }.getOrNull()

    fun deletePhoto(photo: VaultPhoto) = securedLaunch("Could not delete that photo.") {
        val key = requireNotNull(sessionKey).copyOf()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.privatevault.app.sync.LocalPhotoChangeWriter(requireNotNull(database), deviceIdentityStore,
                    lockedSyncStore.photoBlobs, photoStore).delete(photo, key)
                photoStore.delete(photo.encryptedFileName)
                if (photo.encryptedThumbnailFileName.isNotBlank()) photoStore.delete(photo.encryptedThumbnailFileName)
            }
        } finally { key.fill(0) }
        refresh()
        notify("Photo deleted", StatusKind.SUCCESS)
    }

    fun loadPhoto(photo: VaultPhoto): ByteArray? = runCatching {
        photoStore.decryptedBytes(photo.encryptedFileName, requireNotNull(sessionKey))
    }.getOrNull()

    fun loadThumbnail(photo: VaultPhoto): ByteArray? = runCatching {
        val file = photo.encryptedThumbnailFileName.ifBlank { photo.encryptedFileName }
        photoStore.decryptedBytes(file, requireNotNull(sessionKey))
    }.getOrNull()

    fun setCoverPhoto(photo: VaultPhoto) = securedLaunch("Could not set the cover photo.") {
        val key = requireNotNull(sessionKey).copyOf()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.privatevault.app.sync.LocalPhotoChangeWriter(requireNotNull(database), deviceIdentityStore,
                    lockedSyncStore.photoBlobs, photoStore).setCover(photo, key)
            }
        } finally { key.fill(0) }
        refresh()
        notify("Cover photo set", StatusKind.SUCCESS)
    }

    fun transformPhoto(photo: VaultPhoto, rotateDegrees: Float = 0f, crop: com.privatevault.app.security.PhotoCrop? = null) = securedLaunch("Could not update that photo.") {
        val version = UUID.randomUUID().toString()
        val imageName = "$version.vaultphoto"
        val thumbnailName = "$version.vaultthumb"
        val key = requireNotNull(sessionKey).copyOf()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    photoStore.transform(photo.encryptedFileName, imageName, thumbnailName,
                        key, rotateDegrees, crop)
                    com.privatevault.app.sync.LocalPhotoChangeWriter(requireNotNull(database), deviceIdentityStore,
                        lockedSyncStore.photoBlobs, photoStore).save(photo.copy(encryptedFileName = imageName,
                            encryptedThumbnailFileName = thumbnailName), key)
                } catch (failure: Exception) {
                    photoStore.delete(imageName); photoStore.delete(thumbnailName)
                    throw failure
                }
            }
        } finally { key.fill(0) }
        refresh()
        notify(if (crop != null) "Photo cropped" else "Photo rotated", StatusKind.SUCCESS)
    }

    fun changePassword(current: CharArray, replacement: CharArray) = securedLaunch("Could not change the master password. Check your current password.") {
        try {
            val db = requireNotNull(database)
            val vaultId = requireNotNull(db.dao().settings()).vaultId
            require(db.syncDao().activeMembershipCount(vaultId) <= 1) {
                "Master password changes are unavailable while devices share a vault. Remove paired devices first."
            }
            keyManager.changePassword(current, replacement)
            biometricGate.clearDailySession()
            _requestDailyBiometric.value = true
            notify("Master password changed. Existing backups still use their original password.", StatusKind.SUCCESS)
        } finally {
            current.fill('\u0000'); replacement.fill('\u0000')
        }
    }

    fun exportBackup(uri: Uri, password: CharArray) = securedLaunch("Could not create the backup. Check your master password and the chosen location.") {
        try {
            // Authenticate before opening/truncating the chosen destination.
            keyManager.unlock(password).fill(0)
            val output = requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "w"))
            output.use { VaultBackupManager(getApplication(), dao(), photoStore).export(it, password, requireNotNull(sessionKey)) }
            notify("Encrypted backup created.", StatusKind.SUCCESS)
        } finally {
            password.fill('\u0000')
        }
    }

    fun restoreBackup(uri: Uri, password: CharArray) = securedLaunch("Could not open that backup. Check the file and its password.") {
        try {
            cancelRestore()
            val activeKey = requireNotNull(sessionKey)
            val input = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri))
            val prepared = input.use { VaultBackupManager(getApplication(), dao(), photoStore).prepareRestore(it, password, activeKey) }
            if (sessionKey !== activeKey || _status.value !is VaultStatus.Unlocked) { prepared.close(); return@securedLaunch }
            preparedRestore = prepared
            val data = prepared.data
            _restoreSummary.value = "Validated ${data.entries.size} entries, ${data.groups.size} groups, ${data.photos.size} photos, ${data.passkeys.size} passkeys, and ${data.entries.count { it.type == com.privatevault.app.data.EntryType.AUTHENTICATOR }} authenticators. Replace this vault? Restoring creates a separate vault identity. Paired devices will need to be connected again."
        } finally {
            password.fill('\u0000')
        }
    }

    fun confirmRestore() = securedLaunch("Could not restore the backup.") {
        val prepared = preparedRestore ?: return@securedLaunch
        VaultBackupManager(getApplication(), dao(), photoStore).commitRestore(prepared)
        getApplication<Application>().stopService(android.content.Intent(getApplication(), com.privatevault.app.sync.LanSyncService::class.java))
        lockedSyncStore.clear()
        _lightMode.value = prepared.data.lightMode
        _nfcEnabled.value = prepared.data.nfcEnabled && nfcSupported
        _securitySettings.value = (dao().settings() ?: com.privatevault.app.data.VaultSettings())
        preferences.edit().putBoolean("light_mode", _lightMode.value).putBoolean("nfc_enabled", _nfcEnabled.value).commit()
        biometricGate.clearDailySession()
        cancelRestore()
        notify("Backup restored as a separate vault. Use this vault's master password to unlock, then set up fingerprint and reconnect paired devices.", StatusKind.SUCCESS)
        lock(LockReason.BACKGROUND)
    }

    fun clearNotice(shown: UserNotice) { _notice.compareAndSet(shown, null) }

    private fun securedLaunch(failure: String = "That action failed", block: suspend () -> Unit) = viewModelScope.launch {
        touch()
        runCatching { block() }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            notify(it.userMessage(failure), StatusKind.ERROR)
        }
    }

    private fun dao() = requireNotNull(database) { "Vault is locked" }.dao()

    private fun EntryType.noticeName() = when (this) {
        EntryType.CARD -> "Card"
        EntryType.PASSWORD -> "Login"
        EntryType.QUESTION -> "Security question"
        EntryType.NOTE -> "Note"
        EntryType.AUTHENTICATOR -> "Authenticator"
        EntryType.AUTOFILL -> "Autofill details"
    }

    private fun quantity(amount: Int, noun: String) = "$amount ${if (amount == 1) noun else noun + "s"}"

    private suspend fun saveLocalEntry(entry: VaultEntry, groupIds: Set<String>) {
        val activeDatabase = requireNotNull(database) { "Vault is locked" }
        com.privatevault.app.sync.LocalEntryChangeWriter(activeDatabase, deviceIdentityStore)
            .save(entry, groupIds, requireNotNull(sessionKey))
    }

    private suspend fun saveLocalGroup(group: VaultGroup) {
        com.privatevault.app.sync.LocalGroupChangeWriter(requireNotNull(database), deviceIdentityStore)
            .save(group, requireNotNull(sessionKey))
    }

    private suspend fun refresh() {
        val syncDatabase = database ?: return
        val syncDao = syncDatabase.dao()
        val passkeys = syncDao.passkeySummaries()
        val entries = syncDao.allEntries()
        val groups = syncDao.allGroupsWithEntries().map { it.group }
        val vaultId = syncDao.settings()?.vaultId.orEmpty()
        val self = deviceIdentityStore.getOrCreate().deviceId
        val members = syncDatabase.syncDao().memberships(vaultId)
        val paired = members.filter { it.deviceId != self }
        val membershipEvents = syncDatabase.syncDao().membershipEvents(vaultId)
        val membership = membershipEvents.takeIf { it.isNotEmpty() }?.let {
            com.privatevault.app.sync.SyncMembershipManager.verify(it.map { event -> event.toEvent() })
        }
        val canHostPairing = if (membership != null)
            membership.managerDeviceId == self && membership.pendingTransferDeviceId == null
            else paired.isEmpty() && syncDatabase.syncDao().vaultState() == null
        val canRemoveOnlyPeer = paired.count { it.status == com.privatevault.app.sync.MemberStatus.ACTIVE.name } == 1 &&
            membership != null && membership.pendingTransferDeviceId == null && membership.members.any {
                it.deviceId == self && it.status == com.privatevault.app.sync.MemberStatus.ACTIVE.name
            }
        val conflicts = com.privatevault.app.sync.SyncConflictResolver(syncDatabase, deviceIdentityStore,
            lockedSyncStore.photoBlobs, getApplication())
            .reviews(requireNotNull(sessionKey))
        if (database !== syncDatabase || _status.value !is VaultStatus.Unlocked) return
        _passkeys.value = passkeys
        _entries.value = entries
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.privatevault.app.autofill.UnlockedProfileStore(getApplication()).publish(vaultId, entries.map { it.entry })
        } }
            .onFailure { notify("Autofill details could not be updated on this device.", StatusKind.WARNING) }
        _groups.value = groups
        _pairedDevices.value = paired
        _localSyncDevice.value = members.firstOrNull { it.deviceId == self }
        _canRemoveOnlyPeer.value = canRemoveOnlyPeer
        _syncManagerDeviceId.value = membership?.managerDeviceId
        _authorityTransfer.value = membership?.pendingTransferDeviceId?.let {
            AuthorityTransferState(it, membership.transferAccepted)
        }
        _canHostDevicePairing.value = canHostPairing
        _syncConflicts.value = conflicts
        _rejectedSyncChanges.value = lockedSyncStore.rejectedCount()
        if (syncDatabase.syncDao().activeMembershipCount(vaultId) > 1 &&
            syncDatabase.syncDao().vaultState()?.transportSecret?.isNotBlank() == true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lockedSyncStore.publish(syncDatabase) }
            if (database !== syncDatabase || _status.value !is VaultStatus.Unlocked) return
            if (!backgrounded && (android.os.Build.VERSION.SDK_INT < 33 ||
                    androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                runCatching { com.privatevault.app.sync.LanSyncService.start(getApplication()) }
                    .onFailure { notify("Open Android devices settings to resume automatic sync.", StatusKind.WARNING) }
            }
        }
        val current = _entries.value.map { it.entry }.filter { it.type == com.privatevault.app.data.EntryType.AUTHENTICATOR }
        val watchAccounts = current.map(::watchAccount)
        if (_securitySettings.value.watchSyncEnabled && watchAccounts != lastWatchAccounts) {
            lastWatchAccounts = watchAccounts
            watchPublishJob?.cancel()
            val activeDatabase = database
            val key = sessionKey?.copyOf() ?: return
            watchPublishJob = viewModelScope.launch {
                try {
                    if (database === activeDatabase && _status.value is VaultStatus.Unlocked)
                        watchSyncPublisher.publish(_securitySettings.value.vaultId, key, current)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (database === activeDatabase && _status.value is VaultStatus.Unlocked)
                        notify("Watch sync did not complete. Reconnect the watch and tap Sync codes.", StatusKind.WARNING)
                    lastWatchAccounts = null
                } finally { key.fill(0) }
            }
        }
    }

    // Parser, crypto and I/O messages can quote the data being processed, so only the app's own
    // require/check messages are shown. Everything else gets the caller's fallback.
    private fun Throwable.userMessage(fallback: String): String {
        val own = (this is IllegalArgumentException && this !is NumberFormatException) ||
            (this is IllegalStateException && this !is kotlinx.coroutines.CancellationException)
        val text = message
        return if (own && text != null && text.length < 160 && cause?.toString() != text &&
            text != "Failed requirement." && text != "Required value was null." && text != "Check failed.") text else fallback
    }

    suspend fun hostDevicePairing(password: CharArray) {
        touch()
        try {
            val activeDatabase = requireNotNull(database) { "Unlock your vault before pairing" }
            val activeKey = requireNotNull(sessionKey) { "Unlock your vault before pairing" }
            require(_canHostDevicePairing.value) { "Add devices from the managing device" }
            val matches = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val verifiedKey = try { keyManager.unlock(password) }
                catch (_: java.security.GeneralSecurityException) {
                    throw IllegalArgumentException("Incorrect master password")
                }
                try { java.security.MessageDigest.isEqual(verifiedKey, activeKey) }
                finally { verifiedKey.fill(0) }
            }
            require(matches) { "Incorrect master password" }
            check(database === activeDatabase && sessionKey === activeKey && _status.value is VaultStatus.Unlocked) {
                "Vault locked during verification. Unlock and try again."
            }
            devicePairing.host(activeDatabase, activeKey, password)
        } finally { password.fill('\u0000') }
    }

    suspend fun joinDevicePairing(link: String, password: CharArray) {
        try {
            val verified = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                keyManager.unlock(password)
            }
            try { require(java.security.MessageDigest.isEqual(verified, requireNotNull(sessionKey))) {
                "Incorrect master password on this device"
            } } finally { verified.fill(0) }
            devicePairing.join(requireNotNull(database), requireNotNull(sessionKey), link, password)
        } finally { password.fill('\u0000') }
    }

    suspend fun changePasswordAndJoinDevice(link: String, current: CharArray, replacement: CharArray) {
        try {
            require(replacement.size >= com.privatevault.app.security.PasswordCrypto.MIN_PASSWORD_LENGTH) {
                "Use at least 12 characters for the new master password"
            }
            com.privatevault.app.sync.PairingInvitation.decode(link.trim())
            val db = requireNotNull(database)
            val vaultId = requireNotNull(db.dao().settings()).vaultId
            require(db.syncDao().activeMembershipCount(vaultId) <= 1 &&
                db.dao().allEntries().isEmpty() && db.dao().allPasskeys().isEmpty() &&
                db.dao().allGroupsWithEntries().isEmpty()) {
                "Change the password here only for an empty, unpaired vault"
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                keyManager.changePassword(current, replacement)
            }
            biometricGate.clearDailySession()
            devicePairing.join(db, requireNotNull(sessionKey), link, replacement)
        } finally { current.fill('\u0000'); replacement.fill('\u0000') }
    }

    fun approveDevicePairing() { touch(); devicePairing.approve() }
    fun cancelDevicePairing() = devicePairing.cancel()

    fun resumeDeviceSync() = securedLaunch {
        lockedSyncStore.publish(requireNotNull(database))
        com.privatevault.app.sync.LanSyncService.start(getApplication(), resume = true)
        notify("Automatic sync on", StatusKind.SUCCESS)
    }

    fun pauseDeviceSync() {
        com.privatevault.app.sync.LanSyncService.pause(getApplication())
        notify("Automatic sync paused", StatusKind.INFO)
    }

    fun setDeviceSyncInterval(interval: Long) {
        com.privatevault.app.sync.LanSyncService.setSyncInterval(getApplication(), interval)
        val label = when (interval) {
            30_000L -> "30 seconds"
            60_000L -> "1 minute"
            300_000L -> "5 minutes"
            else -> "15 minutes"
        }
        notify("Checks every $label", StatusKind.SUCCESS)
    }

    fun syncDevicesNow() = securedLaunch {
        lockedSyncStore.publish(requireNotNull(database))
        com.privatevault.app.sync.LanSyncService.syncNow(getApplication())
        notify("Checking paired devices…", StatusKind.PROGRESS)
    }

    fun setDeviceSyncAddress(deviceId: String, address: String) = securedLaunch {
        lockedSyncStore.recordPeerAddress(deviceId, address.trim())
        com.privatevault.app.sync.LanSyncService.syncNow(getApplication())
    }

    fun nameThisDevice(name: String) = securedLaunch {
        val db = requireNotNull(database)
        com.privatevault.app.sync.LocalDeviceNameChangeWriter(db, deviceIdentityStore)
            .save(name.trim(), requireNotNull(sessionKey))
        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(getApplication(), db)
        refresh()
        notify("Device renamed", StatusKind.SUCCESS)
    }

    fun retryRejectedSyncChanges() = securedLaunch {
        notify("Retrying changes", StatusKind.PROGRESS)
        lockedSyncStore.retryRejected()
        val key = requireNotNull(sessionKey).copyOf()
        try { lockedSyncStore.applyQueued(requireNotNull(database), key) }
        finally { key.fill(0) }
        refresh()
    }

    private suspend fun requireAuthorityTransferReady(peerDeviceId: String) {
        require(_syncConflicts.value.isEmpty() && lockedSyncStore.queuedCount() == 0 &&
            lockedSyncStore.rejectedCount() == 0 && lockedSyncStore.missingPhotos().isEmpty()) {
            "Resolve conflicts and waiting changes before transferring management"
        }
        val db = requireNotNull(database)
        val vaultId = requireNotNull(db.dao().settings()).vaultId
        val peerApplied = requireNotNull(lockedSyncStore.peerProgress(peerDeviceId)?.applied) {
            "Sync with the other device before transferring management"
        }
        require(db.syncDao().deviceHeads(vaultId).all { head ->
            peerApplied[head.deviceId]?.let { it.sequence == head.sequence && it.hash == head.hash } == true
        }) { "Both devices must apply the same changes before transferring management" }
    }

    fun offerAuthorityTransfer(deviceId: String) = securedLaunch {
        requireAuthorityTransferReady(deviceId)
        val db = requireNotNull(database)
        db.offerAuthorityTransfer(deviceIdentityStore, deviceId)
        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(getApplication(), db)
        refresh()
        notify("Management offer sent. The other device must accept it.", StatusKind.INFO)
    }

    fun acceptAuthorityTransfer() = securedLaunch {
        val manager = requireNotNull(_syncManagerDeviceId.value)
        requireAuthorityTransferReady(manager)
        val db = requireNotNull(database)
        db.acceptAuthorityTransfer(deviceIdentityStore)
        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(getApplication(), db)
        refresh()
        notify("Management role accepted. The current manager completes the handoff.", StatusKind.SUCCESS)
    }

    fun completeAuthorityTransfer() = securedLaunch {
        val target = requireNotNull(_authorityTransfer.value?.takeIf { it.accepted }?.targetDeviceId)
        requireAuthorityTransferReady(target)
        val db = requireNotNull(database)
        db.completeAuthorityTransfer(deviceIdentityStore)
        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(getApplication(), db)
        refresh()
        notify("Management transferred. The other device can add devices after it receives this change.", StatusKind.SUCCESS)
    }

    fun cancelAuthorityTransfer() = securedLaunch {
        val db = requireNotNull(database)
        db.cancelAuthorityTransfer(deviceIdentityStore)
        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(getApplication(), db)
        refresh()
        notify("Management transfer cancelled", StatusKind.INFO)
    }

    fun removeOnlyPairedDevice(deviceId: String) = resetSyncGroup(deviceId)

    fun leaveSyncGroup() = resetSyncGroup(null)

    private fun resetSyncGroup(deviceId: String?) = securedLaunch {
        if (deviceId != null) require(_canRemoveOnlyPeer.value &&
            _syncManagerDeviceId.value == deviceIdentityStore.getOrCreate().deviceId &&
            _pairedDevices.value.any {
            it.deviceId == deviceId && it.status == com.privatevault.app.sync.MemberStatus.ACTIVE.name
        }) { "This device cannot be removed from the current sync group" }
        else require(_pairedDevices.value.any {
            it.status == com.privatevault.app.sync.MemberStatus.ACTIVE.name
        }) { "This device is not sharing a vault" }
        require(_syncConflicts.value.isEmpty()) { "Resolve sync conflicts before removing the device" }
        require(lockedSyncStore.queuedCount() == 0 && lockedSyncStore.rejectedCount() == 0 &&
            lockedSyncStore.missingPhotos().isEmpty()) {
            "Apply or retry waiting changes and photos before removing this device"
        }
        val wasPaused = com.privatevault.app.sync.LanSyncService.isPaused(getApplication())
        com.privatevault.app.sync.LanSyncService.pause(getApplication())
        try {
            require(lockedSyncStore.queuedCount() == 0 && lockedSyncStore.rejectedCount() == 0 &&
                lockedSyncStore.missingPhotos().isEmpty()) {
                "A sync was still finishing. Review it, then retry removal"
            }
            requireNotNull(database).let {
                if (deviceId == null) it.leaveSyncGroup(deviceIdentityStore)
                else it.removeOnlyPairedDevice(deviceIdentityStore, deviceId)
                lockedSyncStore.clear()
                lockedSyncStore.publish(it)
            }
        } finally {
            if (!wasPaused) com.privatevault.app.sync.LanSyncService.allowFutureSync(getApplication())
            else com.privatevault.app.sync.LanSyncService.refreshPausedNotification(getApplication())
        }
        _securitySettings.value = requireNotNull(dao().settings())
        refresh()
        notify("This device now has a separate sync group and keeps its vault copy. Pair a new empty device when ready; pair the watch again for codes.", StatusKind.SUCCESS)
    }

    fun resolveSyncConflict(id: String, useIncoming: Boolean, expectedVersion: String) = securedLaunch {
        com.privatevault.app.sync.SyncConflictResolver(requireNotNull(database), deviceIdentityStore,
            lockedSyncStore.photoBlobs, getApplication())
            .resolve(id, useIncoming, requireNotNull(sessionKey), expectedVersion)
        refresh()
        notify("Conflict resolved", StatusKind.SUCCESS)
    }

    init {
        viewModelScope.launch {
            devicePairingState.collect { state ->
                if (state.stage in setOf("paired", "enrolled_pending") && _status.value is VaultStatus.Unlocked) {
                    _securitySettings.value = requireNotNull(dao().settings())
                    refresh()
                }
            }
        }
    }

    override fun onCleared() {
        incomingSyncJob?.cancel()
        devicePairing.close()
        cancelPasswordImport()
        cancelPasswordDuplicateReview()
        cancelRestore()
        clearCamera()
        database?.close()
        sessionKey?.fill(0)
        super.onCleared()
    }
}
