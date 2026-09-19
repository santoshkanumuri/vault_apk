package com.privatevault.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privatevault.app.backup.VaultBackupManager
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.security.EncryptedPhotoStore
import com.privatevault.app.security.BiometricGate
import com.privatevault.app.security.VaultKeyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import java.util.UUID

enum class LockReason { STARTUP, BACKGROUND, INACTIVITY, SCREEN_OFF }

sealed interface VaultStatus {
    data object NeedsSetup : VaultStatus
    data class Locked(val reason: LockReason, val canUseBiometric: Boolean) : VaultStatus
    data object Unlocked : VaultStatus
}

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val keyManager = VaultKeyManager(application)
    private val biometricGate = BiometricGate(application)
    private val photoStore = EncryptedPhotoStore(application)
    private var preparedRestore: com.privatevault.app.backup.PreparedRestore? = null
    private val _restoreSummary = MutableStateFlow<String?>(null)
    val restoreSummary = _restoreSummary.asStateFlow()
    private var pendingLogins = emptyList<VaultEntry>()
    private val _importPreview = MutableStateFlow<List<String>>(emptyList())
    val importPreview = _importPreview.asStateFlow()

    fun cancelPasswordImport() { pendingLogins = emptyList(); _importPreview.value = emptyList() }

    fun previewPasswordImport(uri: Uri) = securedLaunch {
        cancelPasswordImport()
        val activeKey = requireNotNull(sessionKey)
        val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            java.io.InputStreamReader(requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)), decoder).buffered().use {
                com.privatevault.app.security.readBrowserPasswords(it)
            }
        }
        if (sessionKey === activeKey && _status.value is VaultStatus.Unlocked) {
            pendingLogins = parsed
            _importPreview.value = parsed.map { "${it.title} · ${it.primaryValue}" }
        }
    }

    fun confirmPasswordImport() = securedLaunch {
        val incoming = pendingLogins
        cancelPasswordImport()
        if (incoming.isNotEmpty()) {
            val added = dao().importLogins(incoming)
            refresh()
            _message.value = "Imported $added logins. Skipped ${incoming.size - added} exact duplicates. Delete the readable CSV export after checking your logins."
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
    @Volatile private var backgrounded = false

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
        val value = com.privatevault.app.data.VaultSettings(lightMode = _lightMode.value, nfcEnabled = _nfcEnabled.value)
        viewModelScope.launch { runCatching { db.dao().saveSettings(value) } }
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
                _requestDailyBiometric.value = true
            }
            .onFailure { _message.value = it.userMessage("Could not create the vault") }
        password.fill('\u0000')
    }

    fun unlock(password: CharArray) = viewModelScope.launch {
        runCatching { keyManager.unlock(password) }
            .onSuccess {
                open(it)
                _requestDailyBiometric.value = true
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
        val options = dao().settings()
        if (options != null) {
            _lightMode.value = options.lightMode
            _nfcEnabled.value = options.nfcEnabled && nfcSupported
            preferences.edit().putBoolean("light_mode", _lightMode.value).putBoolean("nfc_enabled", _nfcEnabled.value).commit()
        } else dao().saveSettings(com.privatevault.app.data.VaultSettings(lightMode = _lightMode.value, nfcEnabled = _nfcEnabled.value))
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
        runCatching { biometricGate.enableDailySession(cipher, key) }
            .onSuccess { _requestDailyBiometric.value = false; _message.value = "Fingerprint enabled for 24 hours." }
            .onFailure { biometricGate.clearDailySession(); _requestDailyBiometric.value = false; _message.value = "Could not enable fingerprint. Use the master password next time." }
    }

    fun skipDailyBiometric() { _requestDailyBiometric.value = false }

    fun lock(reason: LockReason) {
        cancelPasswordImport()
        cancelRestore()
        clearCamera()
        cancelNfcScan()
        externalFlowActive = false
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
        if (_status.value !is VaultStatus.Unlocked || backgrounded) return
        inactivityJob?.cancel()
        inactivityDeadline = android.os.SystemClock.elapsedRealtime() + 60_000L
        inactivityJob = viewModelScope.launch {
            delay(60_000)
            lock(LockReason.INACTIVITY)
        }
    }

    fun onAppBackgrounded() {
        backgrounded = true
        if (_status.value !is VaultStatus.Unlocked) return
        val now = android.os.SystemClock.elapsedRealtime()
        val deadline = com.privatevault.app.security.backgroundLockDeadline(now, inactivityDeadline, externalFlowActive)
        externalFlowActive = false
        backgroundDeadline = deadline
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            delay((deadline - now).coerceAtLeast(0L))
            lock(LockReason.BACKGROUND)
        }
    }

    fun onAppForegrounded() {
        val now = android.os.SystemClock.elapsedRealtime()
        // Check elapsed time before extending inactivity; Android may delay background jobs.
        if (backgroundDeadline?.let { now >= it } == true) lock(LockReason.BACKGROUND)
        backgroundJob?.cancel()
        backgroundDeadline = null
        backgrounded = false
        touch()
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
        dao().saveEntry(entry, groupIds)
        refresh()
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
        dao().saveEntry(duplicate, item.groups.map { it.id }.toSet())
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

    private suspend fun refresh() {
        _passkeys.value = dao().passkeySummaries()
        _entries.value = dao().allEntries()
        _groups.value = dao().allGroupsWithEntries().map { it.group }
    }

    private fun Throwable.userMessage(fallback: String): String = message?.takeIf { it.length < 160 } ?: fallback

    override fun onCleared() {
        cancelPasswordImport()
        cancelRestore()
        clearCamera()
        database?.close()
        sessionKey?.fill(0)
        super.onCleared()
    }
}
