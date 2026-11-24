package com.privatevault.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** What happened to a passkey, for its history. Stored by name, so new kinds never renumber old rows. */
enum class PasskeyEventKind {
    /** The passkey was already saved when this device started keeping history. */
    ADDED_BEFORE_HISTORY,
    CREATED, IMPORTED, SYNCED_IN, RESTORED, USED, DELETED, DELETED_ON_OTHER_DEVICE;

    /** A use is not a change: "Last changed" and "Added from" skip it. */
    val isChange: Boolean get() = this != USED

    companion object {
        fun of(name: String): PasskeyEventKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * One line of the passkey ledger. It lives in the encrypted vault on this device only: it never syncs and is not in
 * backups. [rpId] and [username] are copied, so the line still reads well after the passkey is deleted. [detail] is
 * a device name, an exporter or a website, never a key, challenge or user handle.
 */
@Entity(tableName = "passkey_history", indices = [Index("passkeyId")])
data class PasskeyHistoryEvent(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val passkeyId: String,
    val rpId: String,
    val username: String,
    val kind: String,
    val at: Long,
    val appPackage: String = "",
    val appLabel: String = "",
    val detail: String = "",
) {
    val eventKind: PasskeyEventKind? get() = PasskeyEventKind.of(kind)
}

/**
 * The app that asked this device to create a passkey, with its icon as a small PNG ([icon], at most
 * [PasskeyOrigins.MAX_ICON_BYTES]), so it still shows after that app is uninstalled. This device only.
 */
@Entity(tableName = "passkey_origins")
class PasskeyOrigin(
    @PrimaryKey val passkeyId: String,
    val appPackage: String,
    val appLabel: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val icon: ByteArray?,
)

/** A passkey's public details for the passkeys page. The private key is never read. */
data class PasskeyInfo(
    val id: String,
    val rpId: String,
    val userHandle: String,
    val username: String,
    val displayName: String,
    val publicKey: String,
    val createdAt: Long,
)

object PasskeyOrigins {
    const val MAX_ICON_BYTES = 64 * 1024
    /** The ledger keeps this many lines; the oldest go first. */
    const val MAX_EVENTS = 3000
    /** Sign-ins are the most frequent lines: at most this many stay, so changes are never pushed out by uses. */
    const val MAX_USE_EVENTS = 500
}

@Dao
interface PasskeyHistoryDao {
    @Insert suspend fun insert(events: List<PasskeyHistoryEvent>)
    @Query("SELECT * FROM passkey_history ORDER BY at DESC, seq DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PasskeyHistoryEvent>
    @Query("SELECT COUNT(*) FROM passkey_history") suspend fun count(): Int
    @Query("DELETE FROM passkey_history") suspend fun clear()
    @Query("DELETE FROM passkey_history WHERE kind = 'USED' AND seq NOT IN (SELECT seq FROM passkey_history WHERE kind = 'USED' ORDER BY seq DESC LIMIT :keep)")
    suspend fun trimUses(keep: Int)
    @Query("DELETE FROM passkey_history WHERE seq NOT IN (SELECT seq FROM passkey_history ORDER BY seq DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveOrigin(origin: PasskeyOrigin)
    @Query("SELECT * FROM passkey_origins") suspend fun origins(): List<PasskeyOrigin>
    @Query("DELETE FROM passkey_origins WHERE passkeyId = :id") suspend fun deleteOrigin(id: String)
    @Query("SELECT id, rpId, userHandle, username, displayName, publicKey, createdAt FROM passkeys ORDER BY rpId, username")
    suspend fun passkeyInfos(): List<PasskeyInfo>
}

/** Adds ledger lines for [passkeys] and keeps the ledger within its limits. Call inside the change's transaction. */
suspend fun VaultDatabase.logPasskeyEvents(passkeys: List<VaultPasskey>, kind: PasskeyEventKind, at: Long = System.currentTimeMillis(),
    appPackage: String = "", appLabel: String = "", detail: String = "") {
    if (passkeys.isEmpty()) return
    val history = passkeyHistory()
    history.insert(passkeys.map {
        PasskeyHistoryEvent(passkeyId = it.id, rpId = it.rpId.take(253), username = it.username.take(256), kind = kind.name,
            at = at, appPackage = appPackage.take(255), appLabel = appLabel.take(80), detail = detail.take(200))
    })
    if (kind == PasskeyEventKind.USED) history.trimUses(PasskeyOrigins.MAX_USE_EVENTS)
    history.trim(PasskeyOrigins.MAX_EVENTS)
}
