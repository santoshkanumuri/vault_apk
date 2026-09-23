package com.privatevault.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privatevault.app.backup.VaultBackupManager
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

    internal fun checkPasswordDuplicates() = securedLaunch {
        val groups = com.privatevault.app.security.exactPasswordDuplicateGroups(dao().allEntries())
        if (groups.isEmpty()) {
            _passwordDuplicateReview.value = null
            _message.value = "No exact password duplicates found."
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

    internal fun deleteSelectedPasswordDuplicates() = securedLaunch {
        val selectedIds = _passwordDuplicateReview.value?.selectedIds.orEmpty()
        require(selectedIds.isNotEmpty()) { "Select at least one duplicate." }
        val deleted = dao().deleteExactPasswordDuplicates(selectedIds)
        cancelPasswordDuplicateReview()
        refresh()
        _message.value = "Deleted $deleted exact password ${if (deleted == 1) "duplicate" else "duplicates"}."
    }

    fun cancelCredentialTransfer() {
        pendingTransferredPasskeys = emptyList()
        _passkeyTransferPreview.value = null
    }

    fun previewCredentialTransfer(json: String, sourcePackage: String) = securedLaunch {
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

    fun confirmCredentialTransfer() = securedLaunch {
        val preview = requireNotNull(_passkeyTransferPreview.value)
        require(preview.items.none { it.status == PasskeyTransferStatus.CONFLICT }) {
            "Resolve passkey credential-ID conflicts before importing."
        }
        val result = dao().importPasskeys(pendingTransferredPasskeys)
        cancelCredentialTransfer()
        refreshPasskeys()
        _message.value = "Imported ${result.added} passkeys. ${result.alreadySaved} were already saved."
    }

    fun credentialTransferFailed(cancelled: Boolean) {
        externalFlowActive = false
        touch()
        if (!cancelled) _message.value = "No compatible passkey transfer was available. The source manager must support Android credential transfer."
    }

    internal fun previewPasswordImport(uri: Uri, mapping: PasswordColumnMapping? = null) = securedLaunch {
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

    fun confirmPasswordImport() = securedLaunch {
        val preview = _importPreview.value
        if (preview != null) {
            require(preview?.items?.filter { it.status == PasswordImportStatus.PASSWORD_DIFFERS }
                ?.all { it.decision != null } == true) { "Choose how to handle every changed password." }
            val selectedNewRows = preview.items.filter { it.status == PasswordImportStatus.NEW && it.selected }
                .mapTo(hashSetOf()) { it.rowId }
            val requests = pendingLoginRequests.filter { it.action != LoginImportAction.ADD || it.incoming.id in selectedNewRows }
            val result = dao().importLogins(requests)
            cancelPasswordImport()
            refresh()
            val duplicateRows = preview?.duplicateRows ?: 0
            _message.value = "Added ${result.added} logins and updated ${result.updated}. " +
                "Skipped ${result.skippedExact + duplicateRows} duplicates and ${result.skippedConflicts} incoming password changes. " +
                "Delete the readable export file after checking your logins."
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
        }.getOrElse { clearCamera(); _message.value = "Could not open the camera."; null }
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
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _requestDailyBiometric = MutableStateFlow(false)
    val requestDailyBiometric = _requestDailyBiometric.asStateFlow()

    var externalFlowActive = false
    private val preferences = application.getSharedPreferences("vault_preferences", Application.MODE_PRIVATE)
    private val _lightMode = MutableStateFlow(preferences.getBoolean("light_mode", false))
    val lightMode = _lightMode.asStateFlow()

    fun setLightMode(enabled: Boolean) {
        if (preferences.edit().putBoolean("light_mode", enabled).commit()) _lightMode.value = enabled
        else _message.value = "Could not save appearance preference."
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
        else { _nfcEnabled.value = false; _message.value = "Could not save NFC preference. NFC is off for this session." }
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
            }.onFailure { _message.value = "Could not save security settings." }
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
    fun failNfcScan(message: String) { cancelNfcScan(); _message.value = message }
    fun consumeNfcResult() { _nfcResult.value = null }

    fun setup(password: CharArray, enableNfc: Boolean = false) = viewModelScope.launch {
        runCatching { keyManager.create(password) }
            .onSuccess {
                open(it)
                setNfcEnabled(enableNfc)
                _requestDailyBiometric.value = _securitySettings.value.masterPasswordIntervalMs > 0
            }
            .onFailure { _message.value = it.userMessage("Could not create the vault") }
        password.fill('\u0000')
    }

    fun unlock(password: CharArray) = viewModelScope.launch {
        runCatching { keyManager.unlock(password) }
            .onSuccess {
                open(it)
                _requestDailyBiometric.value = _securitySettings.value.masterPasswordIntervalMs > 0
            }
            .onFailure { _message.value = "The master password is incorrect." }
        password.fill('\u0000')
    }

    fun requireMasterPasswordForBiometric() {
        val current = _status.value
        if (current is VaultStatus.Locked) _status.value = current.copy(canUseBiometric = false)
        _message.value = "Fingerprint session is unavailable or expired. Enter the master password."
    }

    fun unlockWithBiometric(key: ByteArray) {
        _requestDailyBiometric.value = false
        viewModelScope.launch {
            runCatching { open(key) }
                .onFailure { key.fill(0); biometricGate.clearDailySession(); _message.value = "Biometric session expired. Use the master password." }
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
        _securitySettings.value = options
        _status.value = VaultStatus.Unlocked
        refresh()
        val retained = _entries.value.flatMap { it.photos }.flatMap { listOf(it.encryptedFileName, it.encryptedThumbnailFileName) }.toSet()
        photoStore.cleanupAbandonedRestore(retained)
        touch()
    }

    fun dailyBiometricEncryptionCipher(): Cipher? = runCatching { biometricGate.dailyEncryptionCipher() }
        .onFailure { _message.value = "Fingerprint is unavailable. Use the master password next time." }
        .getOrNull()

    fun enableDailyBiometric(cipher: Cipher) {
        val key = sessionKey ?: return
        val interval = _securitySettings.value.masterPasswordIntervalMs
        if (interval == 0L) { _requestDailyBiometric.value = false; return }
        runCatching { biometricGate.enableDailySession(cipher, key, interval) }
            .onSuccess { _requestDailyBiometric.value = false; _message.value = "Fingerprint enabled for the selected interval." }
            .onFailure { biometricGate.clearDailySession(); _requestDailyBiometric.value = false; _message.value = "Could not enable fingerprint. Use the master password next time." }
    }

    fun skipDailyBiometric() { _requestDailyBiometric.value = false }

    fun lock(reason: LockReason) {
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
        _passkeys.value = emptyList()
        _groups.value = emptyList()
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

    fun saveEntry(entry: VaultEntry, groupIds: Set<String>) = securedLaunch {
        if (entry.type == com.privatevault.app.data.EntryType.AUTHENTICATOR) {
            com.privatevault.app.security.Totp.validate(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod)
        }
        saveLocalEntry(entry, groupIds)
        refresh()
    }

    fun importAuthenticatorAccounts(accounts: List<com.privatevault.app.security.TotpSetup>) = securedLaunch {
        require(accounts.isNotEmpty() && accounts.size <= 100)
        val result = dao().importAuthenticatorAccounts(accounts)
        refresh()
        _message.value = "Imported ${result.added} authenticator ${if (result.added == 1) "account" else "accounts"}. ${result.alreadySaved} already saved. ${result.conflicts} conflicts skipped."
    }

    fun refreshPasskeys() = securedLaunch { _passkeys.value = dao().passkeySummaries() }
    fun deletePasskey(id: String) = securedLaunch { dao().deletePasskey(id); refreshPasskeys() }

    fun markOpened(id: String) = securedLaunch {
        dao().markOpened(id, System.currentTimeMillis())
        refresh()
    }

    fun toggleFavorite(entry: VaultEntry) = securedLaunch {
        dao().setFavorite(entry.id, !entry.favorite, System.currentTimeMillis())
        refresh()
    }

    fun duplicateEntry(item: EntryWithDetails) = securedLaunch {
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
    }

    fun deleteEntry(item: EntryWithDetails) = securedLaunch {
        dao().deleteEntryAndLinks(item.entry)
        item.photos.forEach {
            photoStore.delete(it.encryptedFileName)
            if (it.encryptedThumbnailFileName.isNotBlank()) photoStore.delete(it.encryptedThumbnailFileName)
        }
        refresh()
    }

    fun addGroup(name: String, notes: String = "") = securedLaunch {
        require(name.isNotBlank()) { "Enter a group name." }
        dao().insertGroup(VaultGroup(name = name.trim(), notes = notes.trim()))
        refresh()
    }

    fun addFolder(name: String, type: com.privatevault.app.data.EntryType) = securedLaunch {
        require(name.isNotBlank()) { "Enter a folder name." }
        require(type != com.privatevault.app.data.EntryType.CARD) { "Cards do not use folders." }
        dao().insertGroup(VaultGroup(name = name.trim(), folderType = type))
        refresh()
    }

    fun renameFolder(folder: VaultGroup, name: String) = securedLaunch {
        require(folder.folderType != null && name.isNotBlank()) { "Enter a folder name." }
        dao().insertGroup(folder.copy(name = name.trim()))
        refresh()
    }

    fun deleteGroup(group: VaultGroup) = securedLaunch {
        dao().deleteGroup(group)
        refresh()
    }

    fun editGroup(group: VaultGroup, name: String, notes: String) = securedLaunch {
        require(name.isNotBlank()) { "Enter a group name." }
        dao().insertGroup(group.copy(name = name.trim(), notes = notes.trim()))
        refresh()
    }

    fun setGroupEntries(group: VaultGroup, ids: Set<String>) = securedLaunch {
        dao().setGroupEntries(group.id, ids)
        refresh()
    }

    fun addPhoto(entryId: String, uri: Uri) = securedLaunch {
        val key = requireNotNull(sessionKey)
        val id = UUID.randomUUID().toString()
        val name = "$id.vaultphoto"
        getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open that image." }
        photoStore.encrypt(input, name, key)
        }
        val thumbnailName = "$id.vaultthumb"
        photoStore.createThumbnail(name, thumbnailName, key)
        dao().insertPhoto(VaultPhoto(id, entryId, name, thumbnailName))
        refresh()
    }

    fun deletePhoto(photo: VaultPhoto) = securedLaunch {
        dao().deletePhoto(photo)
        photoStore.delete(photo.encryptedFileName)
        if (photo.encryptedThumbnailFileName.isNotBlank()) photoStore.delete(photo.encryptedThumbnailFileName)
        refresh()
    }

    fun loadPhoto(photo: VaultPhoto): ByteArray? = runCatching {
        photoStore.decryptedBytes(photo.encryptedFileName, requireNotNull(sessionKey))
    }.getOrNull()

    fun loadThumbnail(photo: VaultPhoto): ByteArray? = runCatching {
        val file = photo.encryptedThumbnailFileName.ifBlank { photo.encryptedFileName }
        photoStore.decryptedBytes(file, requireNotNull(sessionKey))
    }.getOrNull()

    fun setCoverPhoto(photo: VaultPhoto) = securedLaunch {
        dao().setPhotoCover(photo)
        refresh()
    }

    fun transformPhoto(photo: VaultPhoto, rotateDegrees: Float = 0f, crop: com.privatevault.app.security.PhotoCrop? = null) = securedLaunch {
        val thumbnailName = photo.encryptedThumbnailFileName.ifBlank { "${photo.id}.vaultthumb" }
        photoStore.transform(photo.encryptedFileName, thumbnailName, requireNotNull(sessionKey), rotateDegrees, crop)
        if (photo.encryptedThumbnailFileName.isBlank()) dao().updatePhoto(photo.copy(encryptedThumbnailFileName = thumbnailName))
        refresh()
    }

    fun changePassword(current: CharArray, replacement: CharArray) = securedLaunch {
        try {
            keyManager.changePassword(current, replacement)
            biometricGate.clearDailySession()
            _requestDailyBiometric.value = true
            _message.value = "Master password changed. Existing backups still use their original password."
        } finally {
            current.fill('\u0000'); replacement.fill('\u0000')
        }
    }

    fun exportBackup(uri: Uri, password: CharArray) = securedLaunch {
        try {
            // Authenticate before opening/truncating the chosen destination.
            keyManager.unlock(password).fill(0)
            val output = requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "w"))
            output.use { VaultBackupManager(getApplication(), dao(), photoStore).export(it, password, requireNotNull(sessionKey)) }
            _message.value = "Encrypted backup created."
        } finally {
            password.fill('\u0000')
        }
    }

    fun restoreBackup(uri: Uri, password: CharArray) = securedLaunch {
        try {
            cancelRestore()
            val activeKey = requireNotNull(sessionKey)
            val input = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri))
            val prepared = input.use { VaultBackupManager(getApplication(), dao(), photoStore).prepareRestore(it, password, activeKey) }
            if (sessionKey !== activeKey || _status.value !is VaultStatus.Unlocked) { prepared.close(); return@securedLaunch }
            preparedRestore = prepared
            val data = prepared.data
            _restoreSummary.value = "Validated ${data.entries.size} entries, ${data.groups.size} groups, ${data.photos.size} photos, ${data.passkeys.size} passkeys, and ${data.entries.count { it.type == com.privatevault.app.data.EntryType.AUTHENTICATOR }} authenticators. Replace the contents of this vault?"
        } finally {
            password.fill('\u0000')
        }
    }

    fun confirmRestore() = securedLaunch {
        val prepared = preparedRestore ?: return@securedLaunch
        VaultBackupManager(getApplication(), dao(), photoStore).commitRestore(prepared)
        _lightMode.value = prepared.data.lightMode
        _nfcEnabled.value = prepared.data.nfcEnabled && nfcSupported
        _securitySettings.value = (dao().settings() ?: com.privatevault.app.data.VaultSettings())
        preferences.edit().putBoolean("light_mode", _lightMode.value).putBoolean("nfc_enabled", _nfcEnabled.value).commit()
        biometricGate.clearDailySession()
        cancelRestore()
        _message.value = "Backup restored. Use this vault's master password to unlock. Set up fingerprint access again on this phone."
        lock(LockReason.BACKGROUND)
    }

    fun clearMessage() { _message.value = null }

    private fun securedLaunch(block: suspend () -> Unit) = viewModelScope.launch {
        touch()
        runCatching { block() }.onFailure { _message.value = it.userMessage("That action failed") }
    }

    private fun dao() = requireNotNull(database) { "Vault is locked" }.dao()

    private suspend fun saveLocalEntry(entry: VaultEntry, groupIds: Set<String>) {
        val activeDatabase = requireNotNull(database) { "Vault is locked" }
        com.privatevault.app.sync.LocalEntryChangeWriter(activeDatabase, deviceIdentityStore)
            .save(entry, groupIds, requireNotNull(sessionKey))
    }

    private suspend fun refresh() {
        _passkeys.value = dao().passkeySummaries()
        _entries.value = dao().allEntries()
        _groups.value = dao().allGroupsWithEntries().map { it.group }
    }

    private fun Throwable.userMessage(fallback: String): String = message?.takeIf { it.length < 160 } ?: fallback

    override fun onCleared() {
        cancelPasswordImport()
        cancelPasswordDuplicateReview()
        cancelRestore()
        clearCamera()
        database?.close()
        sessionKey?.fill(0)
        super.onCleared()
    }
}
