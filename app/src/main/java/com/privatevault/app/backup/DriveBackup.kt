package com.privatevault.app.backup

import android.content.Context
import com.privatevault.app.sync.CloudError
import com.privatevault.app.sync.CloudException
import com.privatevault.app.sync.CloudFile
import com.privatevault.app.sync.DriveAccess
import com.privatevault.app.sync.GoogleDrive
import com.privatevault.app.sync.GoogleDriveAccess
import com.privatevault.app.sync.GoogleDriveSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Google Drive backup: a scheduled `.pvault` copy of the vault in the hidden app folder, so a lost
 * phone without a sync group can be restored on a new phone with the same Google account.
 *
 * - Only Android writes and reads these files. Sync on Android and Windows reads only names that
 *   start with `nv1.<group>.`, so it ignores them.
 * - A backup is an ordinary `.pvault` file. It opens with the master password that was current
 *   when backup was turned on or the password last changed ([BackupCipher]).
 * - A restore makes a separate vault with a new vault ID ([VaultBackupManager.commitRestore]), so
 *   the restored phone is not part of any sync chain.
 */

/** One backup file in the Drive. [backupId] names the phone that wrote it. */
internal data class DriveBackupFile(val fileId: String, val backupId: String, val createdAt: Long, val size: Long)

/** `nvbak1.<backup ID: 16 hex>.<created: 13-digit Unix milliseconds>` */
internal object DriveBackupNames {
    const val PREFIX = "nvbak1."
    private val ID = Regex("[0-9a-f]{16}")
    private val TIME = Regex("[0-9]{13}")

    fun newBackupId(random: SecureRandom = SecureRandom()): String =
        ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun name(backupId: String, createdAt: Long): String {
        require(ID.matches(backupId) && createdAt in 0..9_999_999_999_999L) { "Invalid backup name" }
        return PREFIX + backupId + "." + createdAt.toString().padStart(13, '0')
    }

    fun parse(file: CloudFile): DriveBackupFile? {
        if (!file.name.startsWith(PREFIX)) return null
        val parts = file.name.substring(PREFIX.length).split('.')
        if (parts.size != 2 || !ID.matches(parts[0]) || !TIME.matches(parts[1])) return null
        return DriveBackupFile(file.id, parts[0], parts[1].toLong(), file.size)
    }
}

/** The rules of the schedule and of what is kept. Pure, so the unit tests cover them. */
internal object DriveBackupPlan {
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L
    const val WEEK = 7 * DAY
    const val MONTH = 30 * DAY
    val INTERVALS = listOf(HOUR, DAY, WEEK, MONTH)
    const val DEFAULT_INTERVAL = DAY
    /** Each phone keeps its newest backup and the one before it. */
    const val KEEP = 2
    /** After a failed attempt, wait this long before the next automatic try. */
    const val RETRY_AFTER = 15 * 60_000L

    /** A backup is due when none was made, the interval passed, or the clock moved backwards. */
    fun due(now: Long, lastBackupAt: Long, lastAttemptAt: Long, interval: Long): Boolean {
        val retryWaits = lastAttemptAt > lastBackupAt && now >= lastAttemptAt && now - lastAttemptAt < RETRY_AFTER
        if (retryWaits) return false
        return lastBackupAt <= 0 || now < lastBackupAt || now - lastBackupAt >= interval
    }

    /** All backups in the Drive, newest first. */
    fun available(files: List<CloudFile>): List<DriveBackupFile> =
        files.mapNotNull(DriveBackupNames::parse).sortedWith(compareByDescending<DriveBackupFile> { it.createdAt }.thenBy { it.fileId })

    /** This phone's backups beyond the newest [KEEP]; other phones' backups are never touched. */
    fun obsolete(files: List<CloudFile>, backupId: String): List<DriveBackupFile> =
        available(files).filter { it.backupId == backupId }.drop(KEEP)
}

/** The Drive operations a backup needs. [GoogleBackupDrive] implements it; tests use one in memory. */
internal interface BackupDrive {
    fun list(): List<CloudFile>
    fun upload(name: String, size: Long, content: InputStream): CloudFile
    fun download(id: String, maxBytes: Long, read: (InputStream) -> Unit)
    fun delete(id: String)
}

internal class GoogleBackupDrive(private val drive: GoogleDrive) : BackupDrive {
    override fun list() = drive.list(null)
    override fun upload(name: String, size: Long, content: InputStream) = drive.upload(name, size, content)
    override fun download(id: String, maxBytes: Long, read: (InputStream) -> Unit) = drive.download(id, maxBytes, read)
    override fun delete(id: String) = drive.delete(id)
}

/** One backup: write, upload, check, then remove this phone's older copies. */
internal object DriveBackupPass {
    /**
     * Writes the backup into [temporary] with [write], uploads it as a new file, and then deletes
     * this phone's backups beyond the newest [DriveBackupPlan.KEEP]. Older copies stay when any
     * step fails, so a failed backup never removes a good one. [temporary] is always deleted.
     */
    suspend fun run(drive: BackupDrive, backupId: String, now: Long, temporary: File,
        write: suspend (OutputStream) -> Unit): DriveBackupFile {
        try {
            FileOutputStream(temporary).use { write(it) }
            val size = temporary.length()
            check(size > 0) { "The backup is empty" }
            val name = DriveBackupNames.name(backupId, now)
            val uploaded = FileInputStream(temporary).use { drive.upload(name, size, it) }
            if (uploaded.size != size || uploaded.name != name) throw CloudException(CloudError.OTHER, "upload incomplete")
            // Best effort: a copy left behind is removed after the next backup.
            runCatching { DriveBackupPlan.obsolete(drive.list(), backupId).forEach { drive.delete(it.fileId) } }
            return DriveBackupFile(uploaded.id, backupId, now, size)
        } finally {
            temporary.delete()
        }
    }
}

/**
 * The backup key on this phone, sealed with a key derived from the vault key: AES-256-GCM under
 * HMAC-SHA256(vault key, label), with the label as associated data. So it opens only while the
 * vault is unlocked, and a new vault (another vault key) cannot open an old file.
 */
internal class DriveBackupKeyFile(private val file: File) {
    private val label = "nuvori-drive-backup-key-v1".toByteArray(Charsets.US_ASCII)

    fun exists() = file.isFile

    fun save(key: BackupCipher.Key, vaultKey: ByteArray, random: SecureRandom = SecureRandom()) {
        val plain = BackupCipher.encode(key)
        val nonce = ByteArray(12).also(random::nextBytes)
        val sealed = try { cipher(Cipher.ENCRYPT_MODE, vaultKey, nonce).doFinal(plain) } finally { plain.fill(0) }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { it.write(nonce); it.write(sealed); it.fd.sync() }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("Could not save the backup key")
        }
    }

    /** The key, or null when there is none or this vault key cannot open it. */
    fun load(vaultKey: ByteArray): BackupCipher.Key? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size <= 12 + 16) return null
        val plain = runCatching {
            cipher(Cipher.DECRYPT_MODE, vaultKey, bytes.copyOfRange(0, 12)).doFinal(bytes, 12, bytes.size - 12)
        }.getOrNull() ?: return null
        return try { BackupCipher.decode(plain) } catch (_: Exception) { null } finally { plain.fill(0) }
    }

    fun delete() { file.delete() }

    private fun cipher(mode: Int, vaultKey: ByteArray, nonce: ByteArray): Cipher {
        val derived = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(vaultKey, "HmacSHA256"))
            doFinal(label)
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(label)
            }
        } finally { derived.fill(0) }
    }
}

/** What the Google Drive backup card shows. [lastError] is a fixed sentence without account or file data. */
data class DriveBackupStatus(
    val enabled: Boolean = false,
    val interval: Long = DriveBackupPlan.DEFAULT_INTERVAL,
    val account: String = "",
    val lastBackupAt: Long = 0L,
    val running: Boolean = false,
    val needsSignIn: Boolean = false,
    val lastError: String? = null,
)

/**
 * Settings, status and the one-at-a-time runner of Google Drive backups. The settings are not
 * secret and live in shared preferences; the backup key lives in [DriveBackupKeyFile].
 */
internal object DriveBackupRunner {
    private const val PREFERENCES = "drive_backup"
    private const val KEY_FILE = "drive-backup-key.bin"
    /** A backup may be large (photos); a download above this is refused. */
    private const val MAX_DOWNLOAD = 4L * 1024 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(DriveBackupStatus())
    val status: StateFlow<DriveBackupStatus> = mutableStatus.asStateFlow()

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun keyFile(context: Context) = DriveBackupKeyFile(File(context.applicationContext.filesDir, KEY_FILE))

    /** Reads the saved settings into [status]. */
    fun load(context: Context) {
        val saved = preferences(context)
        mutableStatus.update { current ->
            current.copy(
                enabled = saved.getBoolean("enabled", false) && keyFile(context).exists(),
                interval = saved.getLong("interval_ms", DriveBackupPlan.DEFAULT_INTERVAL)
                    .takeIf { it in DriveBackupPlan.INTERVALS } ?: DriveBackupPlan.DEFAULT_INTERVAL,
                account = saved.getString("account", "").orEmpty(),
                lastBackupAt = saved.getLong("last_backup_at", 0L),
                needsSignIn = saved.getBoolean("needs_sign_in", false),
                lastError = saved.getString("last_error", null),
            )
        }
    }

    /** Reads the Google account address with a fresh [accessToken] and remembers it. */
    suspend fun connect(context: Context, accessToken: String): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val account = GoogleDrive(object : DriveAccess {
            override fun token() = accessToken
            override fun refresh(stale: String) = GoogleDriveSignIn.clearToken(app, stale)
        }).account()
        preferences(app).edit().putString("account", account).putBoolean("needs_sign_in", false).apply()
        mutableStatus.update { it.copy(account = account, needsSignIn = false) }
        account
    }

    /**
     * Turns backups on: makes a backup key that opens with [password] (the master password, already
     * checked by the caller) and seals it with [vaultKey]. A phone keeps its backup ID, so turning
     * backups off and on again keeps replacing the same phone's files. Slow (Argon2id).
     */
    suspend fun turnOn(context: Context, password: CharArray, vaultKey: ByteArray, interval: Long) = withContext(Dispatchers.Default) {
        require(interval in DriveBackupPlan.INTERVALS)
        mutex.withLock {
            BackupCipher.create(password).use { key -> keyFile(context).save(key, vaultKey) }
            val saved = preferences(context)
            val backupId = saved.getString("backup_id", null) ?: DriveBackupNames.newBackupId()
            saved.edit().putBoolean("enabled", true).putLong("interval_ms", interval).putString("backup_id", backupId)
                .putLong("last_attempt_at", 0L).remove("last_error").commit()
        }
        load(context)
    }

    /**
     * After a master password change: the next backups open with [password]. The backups already in
     * the Drive keep the old password until the newer backups replace them.
     */
    suspend fun changePassword(context: Context, password: CharArray, vaultKey: ByteArray) = withContext(Dispatchers.Default) {
        if (!status.value.enabled) return@withContext
        mutex.withLock { BackupCipher.create(password).use { key -> keyFile(context).save(key, vaultKey) } }
    }

    /** Stops backups on this phone and deletes its backup key. The files in the Drive stay. */
    suspend fun turnOff(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            keyFile(context).delete()
            preferences(context).edit().putBoolean("enabled", false).remove("last_error")
                .putBoolean("needs_sign_in", false).commit()
        }
        load(context)
    }

    fun setInterval(context: Context, interval: Long) {
        require(interval in DriveBackupPlan.INTERVALS)
        preferences(context).edit().putLong("interval_ms", interval).apply()
        mutableStatus.update { it.copy(interval = interval) }
    }

    /**
     * Makes a backup when one is due, or now when [force] is set. Runs outside the view model, so a
     * backup that has started finishes even if the vault locks. Takes ownership of [vaultKey] and
     * clears it.
     */
    fun request(context: Context, manager: VaultBackupManager, vaultKey: ByteArray, force: Boolean) {
        val app = context.applicationContext
        scope.launch {
            try {
                mutex.withLock { backUp(app, manager, vaultKey, force) }
            } finally { vaultKey.fill(0) }
        }
    }

    private suspend fun backUp(app: Context, manager: VaultBackupManager, vaultKey: ByteArray, force: Boolean) {
        val saved = preferences(app)
        if (!saved.getBoolean("enabled", false)) return
        val now = System.currentTimeMillis()
        val interval = saved.getLong("interval_ms", DriveBackupPlan.DEFAULT_INTERVAL)
        if (!force && !DriveBackupPlan.due(now, saved.getLong("last_backup_at", 0L), saved.getLong("last_attempt_at", 0L), interval)) return
        val backupId = saved.getString("backup_id", null) ?: return
        saved.edit().putLong("last_attempt_at", now).apply()
        mutableStatus.update { it.copy(running = true) }
        var error: String? = null
        var needsSignIn = false
        var finishedAt = 0L
        try {
            val key = keyFile(app).load(vaultKey)
            if (key == null) {
                error = KEY_MISSING
            } else key.use {
                val temporary = File(app.cacheDir, "drive-backup-${java.util.UUID.randomUUID()}.tmp")
                DriveBackupPass.run(GoogleBackupDrive(GoogleDrive(GoogleDriveAccess(app))), backupId, now, temporary) { output ->
                    manager.export(output, it, vaultKey)
                }
                finishedAt = now
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CloudException) {
            needsSignIn = failure.error == CloudError.AUTH
            error = message(failure.error)
        } catch (_: Exception) {
            error = message(CloudError.OTHER)
        } finally {
            saved.edit().apply {
                if (finishedAt > 0) putLong("last_backup_at", finishedAt)
                if (error != null) putString("last_error", error) else remove("last_error")
                putBoolean("needs_sign_in", needsSignIn)
            }.apply()
            mutableStatus.update {
                it.copy(running = false, lastBackupAt = if (finishedAt > 0) finishedAt else it.lastBackupAt,
                    needsSignIn = needsSignIn, lastError = error)
            }
        }
    }

    /** Every backup in the Drive of the signed-in account, newest first. Blocking network work. */
    suspend fun list(context: Context): List<DriveBackupFile> = withContext(Dispatchers.IO) {
        DriveBackupPlan.available(GoogleBackupDrive(GoogleDrive(GoogleDriveAccess(context.applicationContext))).list())
    }

    /** Downloads [backup] into [target]. */
    suspend fun download(context: Context, backup: DriveBackupFile, target: File) = withContext(Dispatchers.IO) {
        val drive = GoogleBackupDrive(GoogleDrive(GoogleDriveAccess(context.applicationContext)))
        FileOutputStream(target).use { output ->
            drive.download(backup.fileId, minOf(MAX_DOWNLOAD, backup.size + 1)) { input -> input.copyTo(output, 64 * 1024) }
        }
        if (target.length() != backup.size) throw CloudException(CloudError.OTHER, "download incomplete")
    }

    const val KEY_MISSING = "Backups need the master password again. Turn Google Drive backup off and on."

    fun message(error: CloudError): String = when (error) {
        CloudError.AUTH -> "Google Drive needs you to sign in again."
        CloudError.OFFLINE -> "Can't reach Google Drive. The backup runs again later."
        CloudError.QUOTA -> "Your Google Drive is full. Free some space, then back up again."
        CloudError.BUSY -> "Google Drive is busy. The backup runs again later."
        CloudError.NOT_FOUND, CloudError.OTHER -> "The backup didn't finish. It runs again later."
    }
}
