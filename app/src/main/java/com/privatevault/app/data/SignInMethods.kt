package com.privatevault.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A remembered sign-in method (sync protocol `sign-in-methods-v1`): which "Continue with <provider>" button, and
 * which account, the user used on a site. Nuvori for Windows notices it through the browser extension; this phone
 * receives it and can delete it. Mirrors the Windows table `local_sign_ins`, one row per (site, provider, account
 * key), inside the encrypted vault. Nothing here is a secret: no password, token or URL.
 *
 * [entityId] is the sync entity id under the group's content key. [updatedAtUtc] and [updatedByDevice] are the
 * last writer of the stored value, so a concurrent change can be decided by last writer wins.
 * [pendingDelete]: deleted on this phone while the change could not be written yet (another member's build does not
 * read sign-in methods). The row is hidden everywhere and the delete goes out when the group allows it.
 */
@Entity(tableName = "sign_in_methods", primaryKeys = ["site", "provider", "accountKey"],
    indices = [Index(value = ["entityId"], unique = true)])
data class SignInMethod(
    val site: String,
    val provider: String,
    val accountKey: String,
    val entityId: String,
    val account: String,
    val firstUsedAt: Long,
    val lastUsedAt: Long,
    val useCount: Long,
    val updatedAtUtc: String,
    val updatedByDevice: String,
    val pendingDelete: Boolean = false,
)

@Dao
interface SignInMethodDao {
    @Query("SELECT * FROM sign_in_methods WHERE pendingDelete = 0 ORDER BY lastUsedAt DESC, site, provider, accountKey")
    fun observe(): Flow<List<SignInMethod>>

    @Query("SELECT * FROM sign_in_methods WHERE pendingDelete = 0 ORDER BY lastUsedAt DESC, site, provider, accountKey")
    suspend fun all(): List<SignInMethod>

    @Query("SELECT * FROM sign_in_methods WHERE pendingDelete = 0 AND site IN (:sites) ORDER BY lastUsedAt DESC, provider, accountKey")
    suspend fun forSites(sites: List<String>): List<SignInMethod>

    @Query("SELECT * FROM sign_in_methods WHERE pendingDelete = 1 ORDER BY updatedAtUtc, site LIMIT :limit")
    suspend fun pendingDeletes(limit: Int): List<SignInMethod>

    @Query("SELECT * FROM sign_in_methods WHERE entityId = :entityId")
    suspend fun byEntity(entityId: String): SignInMethod?

    @Query("SELECT * FROM sign_in_methods WHERE site = :site AND provider = :provider AND accountKey = :accountKey")
    suspend fun byKey(site: String, provider: String, accountKey: String): SignInMethod?

    @Query("SELECT COUNT(*) FROM sign_in_methods WHERE pendingDelete = 0")
    suspend fun count(): Int

    @Upsert suspend fun save(method: SignInMethod)

    @Query("DELETE FROM sign_in_methods WHERE entityId = :entityId") suspend fun deleteEntity(entityId: String)

    @Query("DELETE FROM sign_in_methods") suspend fun clear()

    /** The same limit as the Windows table: the least recently used go first. Housekeeping: no delete is written. */
    @Query("DELETE FROM sign_in_methods WHERE pendingDelete = 0 AND entityId NOT IN (SELECT entityId FROM sign_in_methods ORDER BY lastUsedAt DESC, site LIMIT :keep)")
    suspend fun trim(keep: Int = MAX_ROWS)

    /** A method used again after its delete: the live record counts, the old tombstone goes. */
    @Query("DELETE FROM sync_tombstones WHERE entityType = 'sign_in_method' AND entityId = :entityId")
    suspend fun deleteTombstone(entityId: String)

    companion object {
        const val MAX_ROWS = 5000
    }
}
