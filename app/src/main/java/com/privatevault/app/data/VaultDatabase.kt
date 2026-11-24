package com.privatevault.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.UUID

enum class EntryType { CARD, QUESTION, PASSWORD, NOTE, AUTHENTICATOR, AUTOFILL }
enum class CardKind { CREDIT, DEBIT }

@Entity(tableName = "vault_settings")
data class VaultSettings(
    @androidx.room.PrimaryKey val id: Int = 1,
    val lightMode: Boolean = false,
    val nfcEnabled: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "''") val vaultId: String = "",
    @androidx.room.ColumnInfo(defaultValue = "10000") val backgroundTimeoutMs: Long = 10_000L,
    @androidx.room.ColumnInfo(defaultValue = "60000") val inactivityTimeoutMs: Long = 60_000L,
    @androidx.room.ColumnInfo(defaultValue = "86400000") val masterPasswordIntervalMs: Long = 86_400_000L,
    @androidx.room.ColumnInfo(defaultValue = "0") val watchSyncEnabled: Boolean = false,
)

@Entity(tableName = "entries")
data class VaultEntry(
    @androidx.room.PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: EntryType,
    val title: String,
    val primaryValue: String = "",
    val secondaryValue: String = "",
    val tertiaryValue: String = "",
    val fourthValue: String = "",
    val cardKind: CardKind = CardKind.CREDIT,
    val network: String = "",
    val totpAlgorithm: String = "SHA1",
    val totpDigits: Int = 6,
    val totpPeriod: Int = 30,
    val linkedApps: String = "",
    val autofillSignatures: String = "",
    val autofillOrigins: String = "",
    val linkedAuthenticatorId: String = "",
    val notes: String = "",
    val color: Long = 0xFF08704AL,
    val tags: String = "",
    val favorite: Boolean = false,
    val lastOpenedAt: Long = 0,
    val sortOrder: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A login's earlier password, kept on this phone only (Recently deleted and previous passwords
 * are not synced; Nuvori for Windows has the same rules in `local_recovery.rs`). Newest first,
 * up to [PasswordHistoryEntry.LIMIT] per login.
 */
@Entity(tableName = "password_history", indices = [Index("entryId")])
data class PasswordHistoryEntry(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: String,
    val password: String,
    val replacedAt: Long,
) {
    companion object { const val LIMIT = 10 }
}

/**
 * A deleted entry, kept on this phone for [TrashedEntry.DAYS] days. [entryJson] is the sync entry
 * format (`toSyncJson`). A restore makes a new entry with a new ID, which syncs as a new item.
 * Photos are not kept.
 */
@Entity(tableName = "trash")
data class TrashedEntry(
    @androidx.room.PrimaryKey val id: String = UUID.randomUUID().toString(),
    val entryId: String,
    val type: EntryType,
    val title: String,
    val entryJson: String,
    /** Folder IDs, one per line. */
    val groupIds: String,
    val deletedAt: Long,
    /** Deleted on a paired device; this phone kept its copy. */
    val deletedElsewhere: Boolean,
) {
    val expiresAt: Long get() = deletedAt + DAYS * 86_400_000L
    companion object { const val DAYS = 30 }
}

@Entity(tableName = "vault_groups")
data class VaultGroup(
    @androidx.room.PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val folderType: EntryType? = null
)

@Entity(
    tableName = "entry_group",
    primaryKeys = ["entryId", "groupId"],
    foreignKeys = [
        ForeignKey(entity = VaultEntry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = VaultGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("entryId"), Index("groupId")]
)
data class EntryGroupCrossRef(val entryId: String, val groupId: String)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = VaultEntry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("entryId")]
)
data class VaultPhoto(
    @androidx.room.PrimaryKey val id: String = UUID.randomUUID().toString(),
    val entryId: String,
    val encryptedFileName: String,
    val encryptedThumbnailFileName: String = "",
    val isCover: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

data class EntryWithDetails(
    @Embedded val entry: VaultEntry,
    @Relation(parentColumn = "id", entityColumn = "entryId") val photos: List<VaultPhoto>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(EntryGroupCrossRef::class, parentColumn = "entryId", entityColumn = "groupId")
    ) val groups: List<VaultGroup>
)

data class GroupWithEntries(
    @Embedded val group: VaultGroup,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(EntryGroupCrossRef::class, parentColumn = "groupId", entityColumn = "entryId")
    ) val entries: List<VaultEntry>
)

data class LoginImportResult(
    val added: Int,
    val updated: Int,
    val skippedExact: Int,
    val skippedConflicts: Int
)

data class PasskeyImportResult(val added: Int, val alreadySaved: Int)
data class AuthenticatorImportResult(val added: Int, val alreadySaved: Int, val conflicts: Int)

enum class LoginImportAction { ADD, SKIP_EXACT, KEEP_SAVED, USE_IMPORTED, SKIP_AMBIGUOUS }

data class LoginImportMatch(val id: String, val updatedAt: Long, val passwordFingerprint: String) {
    companion object {
        fun from(entry: VaultEntry): LoginImportMatch {
            require(entry.type == EntryType.PASSWORD)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(entry.secondaryValue.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return LoginImportMatch(entry.id, entry.updatedAt, digest)
        }
    }
}

data class LoginImportRequest(
    val incoming: VaultEntry,
    val action: LoginImportAction,
    val expectedMatches: List<LoginImportMatch>,
    val matchedSavedEntryId: String? = null
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_settings WHERE id = 1") suspend fun settings(): VaultSettings?
    @androidx.room.Upsert suspend fun saveSettings(settings: VaultSettings)
    @Transaction
    @Query("SELECT * FROM entries ORDER BY updatedAt DESC")
    fun observeEntries(): Flow<List<EntryWithDetails>>

    @Transaction
    @Query("SELECT * FROM entries ORDER BY updatedAt DESC")
    suspend fun allEntries(): List<EntryWithDetails>

    @Transaction
    suspend fun importAuthenticatorAccounts(accounts: List<com.privatevault.app.security.TotpSetup>): AuthenticatorImportResult {
        val statuses = com.privatevault.app.security.authenticatorImportStatuses(allEntries().map { it.entry }, accounts)
        accounts.zip(statuses).forEach { (account, status) ->
            if (status == com.privatevault.app.security.AuthenticatorImportStatus.NEW) {
                insertEntry(VaultEntry(type = EntryType.AUTHENTICATOR, title = account.issuer.ifBlank { account.account }, primaryValue = account.account,
                    secondaryValue = com.privatevault.app.security.Totp.normalizeSecret(account.secret),
                    totpAlgorithm = account.algorithm, totpDigits = account.digits, totpPeriod = account.period))
            }
        }
        return AuthenticatorImportResult(statuses.count { it == com.privatevault.app.security.AuthenticatorImportStatus.NEW },
            statuses.count { it == com.privatevault.app.security.AuthenticatorImportStatus.ALREADY_SAVED },
            statuses.count { it == com.privatevault.app.security.AuthenticatorImportStatus.CONFLICT })
    }

    @Query("SELECT * FROM entries WHERE type = 'AUTHENTICATOR' ORDER BY title COLLATE NOCASE, primaryValue COLLATE NOCASE")
    suspend fun authenticatorEntries(): List<VaultEntry>

    @Query("SELECT * FROM entries WHERE type IN ('PASSWORD', 'AUTHENTICATOR') ORDER BY title COLLATE NOCASE")
    suspend fun loginAndCodeEntries(): List<VaultEntry>

    /** The Quick copy tile: logins, codes, cards and security questions. Never notes or contact details. */
    @Query("SELECT * FROM entries WHERE type IN ('PASSWORD', 'AUTHENTICATOR', 'CARD', 'QUESTION') ORDER BY title COLLATE NOCASE")
    suspend fun quickCopyEntries(): List<VaultEntry>

    /** Contact details only, for the profile picker: no passwords or authenticator secrets are read. */
    @Query("SELECT * FROM entries WHERE type = 'AUTOFILL' ORDER BY favorite DESC, title COLLATE NOCASE")
    suspend fun autofillProfileEntries(): List<VaultEntry>

    /** Cards only, for the card picker: no passwords or authenticator secrets are read. Favorites, then recent use. */
    @Query("SELECT * FROM entries WHERE type = 'CARD' ORDER BY favorite DESC, lastOpenedAt DESC, title COLLATE NOCASE")
    suspend fun cardEntries(): List<VaultEntry>

    @Query("UPDATE entries SET linkedAuthenticatorId = '' WHERE linkedAuthenticatorId = :id")
    suspend fun clearAuthenticatorLinks(id: String)

    /** Deletes an entry. [elsewhere]: a paired device deleted it. Either way this phone keeps a copy in Recently deleted. */
    @Transaction
    suspend fun deleteEntryAndLinks(entry: VaultEntry, elsewhere: Boolean = false) {
        keepDeleted(entry.id, elsewhere)
        clearAuthenticatorLinks(entry.id)
        deleteEntry(entry)
    }

    @Transaction
    suspend fun deleteExactPasswordDuplicates(selectedIds: Set<String>): Int {
        if (selectedIds.isEmpty()) return 0
        val groups = com.privatevault.app.security.exactPasswordDuplicateGroups(allEntries())
        val deletable = groups.flatMap { it.duplicates }.associateBy { it.entry.id }
        require(selectedIds.all(deletable::containsKey)) {
            "Passwords changed after review. Check for duplicates again."
        }
        selectedIds.forEach { keepDeleted(it, elsewhere = false); deleteEntry(requireNotNull(deletable[it]).entry) }
        return selectedIds.size
    }

    @Transaction
    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: String): EntryWithDetails?

    @androidx.room.Upsert
    suspend fun upsertEntryRow(entry: VaultEntry)

    @Update suspend fun updateEntryRow(entry: VaultEntry)

    @Query("SELECT * FROM entries WHERE id = :id") suspend fun entryRow(id: String): VaultEntry?

    /** Every entry write: local edits, sync, autofill and imports. A login keeps its replaced password. */
    @Transaction
    suspend fun insertEntry(entry: VaultEntry) { keepReplacedPassword(entry); upsertEntryRow(entry) }

    @Transaction
    suspend fun updateEntry(entry: VaultEntry) { keepReplacedPassword(entry); updateEntryRow(entry) }

    suspend fun keepReplacedPassword(entry: VaultEntry, now: Long = System.currentTimeMillis()) {
        val old = entryRow(entry.id) ?: return
        if (old.type != EntryType.PASSWORD || old.secondaryValue.isEmpty() || old.secondaryValue == entry.secondaryValue) return
        insertPasswordHistory(PasswordHistoryEntry(entryId = entry.id, password = old.secondaryValue, replacedAt = now))
        trimPasswordHistory(entry.id, PasswordHistoryEntry.LIMIT)
    }

    @Insert suspend fun insertPasswordHistory(item: PasswordHistoryEntry)

    @Query("DELETE FROM password_history WHERE entryId = :entryId AND id NOT IN (SELECT id FROM password_history WHERE entryId = :entryId ORDER BY replacedAt DESC, id DESC LIMIT :limit)")
    suspend fun trimPasswordHistory(entryId: String, limit: Int)

    @Query("SELECT * FROM password_history WHERE entryId = :entryId ORDER BY replacedAt DESC, id DESC")
    suspend fun passwordHistory(entryId: String): List<PasswordHistoryEntry>

    @Query("DELETE FROM password_history WHERE entryId = :entryId") suspend fun clearPasswordHistory(entryId: String)

    @Query("UPDATE password_history SET entryId = :to WHERE entryId = :from") suspend fun moveHistory(from: String, to: String)

    // Recently deleted (TrashedEntry)

    @Query("SELECT groupId FROM entry_group WHERE entryId = :entryId ORDER BY groupId") suspend fun groupIdsOf(entryId: String): List<String>

    @Insert suspend fun insertTrash(item: TrashedEntry)

    /** Before a delete: keeps a copy in Recently deleted. Nothing when the entry is not here. */
    suspend fun keepDeleted(entryId: String, elsewhere: Boolean, now: Long = System.currentTimeMillis()) {
        val stored = entryRow(entryId) ?: return
        insertTrash(TrashedEntry(entryId = stored.id, type = stored.type, title = stored.title,
            entryJson = with(com.privatevault.app.sync.SyncEntryCodec) { stored.toTrashJson() },
            groupIds = groupIdsOf(stored.id).joinToString("\n"), deletedAt = now, deletedElsewhere = elsewhere))
    }

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC") suspend fun trash(): List<TrashedEntry>
    @Query("SELECT * FROM trash WHERE id = :id") suspend fun trashed(id: String): TrashedEntry?
    @Query("DELETE FROM trash WHERE id = :id") suspend fun deleteTrashRow(id: String)

    /** Deletes one trashed entry for good, with its previous passwords. */
    @Transaction
    suspend fun forgetTrashed(id: String) {
        if (trashed(id) == null) return
        deleteTrashRow(id)
        purgeOrphanHistory()
    }

    @Transaction
    suspend fun emptyTrash() { clearTrash(); purgeOrphanHistory() }
    @Query("DELETE FROM trash") suspend fun clearTrash()

    @Query("DELETE FROM password_history WHERE entryId NOT IN (SELECT id FROM entries) AND entryId NOT IN (SELECT entryId FROM trash)")
    suspend fun purgeOrphanHistory()

    /** Removes trash older than [TrashedEntry.DAYS] days, and the previous passwords of entries that are gone. */
    @Transaction
    suspend fun purgeTrash(now: Long = System.currentTimeMillis()) {
        deleteTrashBefore(now - TrashedEntry.DAYS * 86_400_000L)
        purgeOrphanHistory()
    }
    @Query("DELETE FROM trash WHERE deletedAt < :before") suspend fun deleteTrashBefore(before: Long)

    @Transaction
    suspend fun saveBrowserLogin(origin: String, username: String, password: String, expected: VaultEntry?, title: String? = null) {
        require(com.privatevault.app.security.httpsOrigin(origin) == origin && username.isNotBlank() && password.isNotEmpty())
        if (expected == null) {
            val duplicate = loginAndCodeEntries().any { it.type == EntryType.PASSWORD &&
                com.privatevault.app.security.httpsOrigin(it.tertiaryValue) == origin && it.primaryValue == username && it.secondaryValue == password }
            if (!duplicate) insertEntry(VaultEntry(type = EntryType.PASSWORD, title = com.privatevault.app.security.newLoginTitle(title, origin.removePrefix("https://")),
                primaryValue = username, secondaryValue = password, tertiaryValue = origin))
        } else {
            val current = entry(expected.id)?.entry
            check(current == expected) { "This login changed. Cancel and try saving again." }
            require(expected.type == EntryType.PASSWORD && expected.primaryValue == username &&
                com.privatevault.app.security.httpsOrigin(expected.tertiaryValue) == origin)
            updateEntry(expected.copy(secondaryValue = password, updatedAt = System.currentTimeMillis()))
        }
    }

    @Transaction
    suspend fun saveNativeLogin(packageName: String, identity: String, appName: String, username: String, password: String, expected: VaultEntry?, title: String? = null) {
        require(packageName.isNotBlank() && '\n' !in packageName && '=' !in packageName && identity.isNotBlank() && '\n' !in identity)
        require(username.isNotBlank() && password.isNotEmpty())
        if (expected == null) {
            val duplicate = loginAndCodeEntries().any { it.type == EntryType.PASSWORD && it.primaryValue == username &&
                it.secondaryValue == password && com.privatevault.app.security.loginAuthorized(it, packageName, identity) }
            if (!duplicate) insertEntry(VaultEntry(type = EntryType.PASSWORD, title = com.privatevault.app.security.newLoginTitle(title, appName.ifBlank { packageName }),
                primaryValue = username, secondaryValue = password, autofillSignatures = "$packageName=$identity"))
        } else {
            val current = entry(expected.id)?.entry
            check(current == expected) { "This login changed. Cancel and try saving again." }
            require(expected.type == EntryType.PASSWORD && expected.primaryValue == username &&
                com.privatevault.app.security.loginAuthorized(expected, packageName, identity))
            updateEntry(expected.copy(secondaryValue = password, updatedAt = System.currentTimeMillis()))
        }
    }

    @Transaction
    suspend fun saveGeneratedLogin(origin: String, username: String, password: String, expected: VaultEntry?) {
        require(com.privatevault.app.security.httpsOrigin(origin) == origin && username.isNotBlank() && username.length <= 1024 && password.isNotEmpty())
        val previous = expected?.let { entry(it.id) }
        if (expected != null) {
            check(previous?.entry == expected)
            require(expected.type == EntryType.PASSWORD && expected.primaryValue == username &&
                com.privatevault.app.security.httpsOrigin(expected.tertiaryValue) == origin)
        }
        val generated = VaultEntry(type = EntryType.PASSWORD, title = "${expected?.title ?: origin.removePrefix("https://")} (generated)",
            primaryValue = username, secondaryValue = password, tertiaryValue = origin,
            linkedAuthenticatorId = expected?.linkedAuthenticatorId.orEmpty(),
            notes = "Generated for a website form. Confirm that the website accepted it before removing an older login.")
        saveEntry(generated, previous?.groups.orEmpty().map { it.id }.toSet())
    }

    @Query("UPDATE entries SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun markOpened(id: String, openedAt: Long)

    @Query("UPDATE entries SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long)

    @Delete suspend fun deleteEntry(entry: VaultEntry)

    @Query("SELECT * FROM vault_groups ORDER BY name COLLATE NOCASE")
    fun observeGroups(): Flow<List<VaultGroup>>

    @Transaction
    @Query("SELECT * FROM vault_groups ORDER BY name COLLATE NOCASE")
    suspend fun allGroupsWithEntries(): List<GroupWithEntries>

    @androidx.room.Upsert
    suspend fun insertGroup(group: VaultGroup)

    @Query("UPDATE vault_groups SET folderType = NULL WHERE folderType = 'CARD'")
    suspend fun convertCardFoldersToGroups()

    @Delete suspend fun deleteGroup(group: VaultGroup)
    @Query("DELETE FROM vault_groups WHERE id = :id") suspend fun deleteGroupById(id: String)
    /** Only the row (photos and folder links go with it); no Recently deleted copy. */
    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteEntryRow(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: EntryGroupCrossRef)

    @Query("DELETE FROM entry_group WHERE entryId = :entryId")
    suspend fun clearLinks(entryId: String)

    @Query("DELETE FROM entry_group WHERE groupId = :groupId")
    suspend fun clearGroupLinks(groupId: String)

    @Transaction
    suspend fun setGroupEntries(groupId: String, ids: Set<String>) {
        clearGroupLinks(groupId)
        ids.forEach { link(EntryGroupCrossRef(it, groupId)) }
    }

    @Query("SELECT * FROM entry_group")
    suspend fun allLinks(): List<EntryGroupCrossRef>

    @Transaction
    suspend fun backupSnapshot(): com.privatevault.app.backup.BackupData {
        val entries = allEntries()
        val storedOptions = settings()
        val options = when {
            storedOptions == null -> VaultSettings(vaultId = UUID.randomUUID().toString())
            storedOptions.vaultId.isBlank() -> storedOptions.copy(vaultId = UUID.randomUUID().toString())
            else -> storedOptions
        }
        if (storedOptions != options) saveSettings(options)
        return com.privatevault.app.backup.BackupData(entries.map { it.entry }, allGroupsWithEntries().map { it.group }, allLinks(), entries.flatMap { it.photos }, options.lightMode, options.nfcEnabled, allPasskeys(), options.vaultId, options.backgroundTimeoutMs, options.inactivityTimeoutMs, options.masterPasswordIntervalMs)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: VaultPhoto)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun photo(id: String): VaultPhoto?

    @Query("SELECT * FROM photos")
    suspend fun allPhotos(): List<VaultPhoto>

    @Query("SELECT id FROM entries")
    suspend fun entryIds(): List<String>

    @Update suspend fun updatePhoto(photo: VaultPhoto)

    @Query("UPDATE photos SET isCover = 0 WHERE entryId = :entryId")
    suspend fun clearPhotoCovers(entryId: String)

    @Transaction
    suspend fun setPhotoCover(photo: VaultPhoto) {
        clearPhotoCovers(photo.entryId)
        updatePhoto(photo.copy(isCover = true))
    }

    @Delete suspend fun deletePhoto(photo: VaultPhoto)

    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM sync_epoch_keys") suspend fun clearSyncEpochKeys()
    @Query("DELETE FROM sync_key_packages") suspend fun clearSyncKeyPackages()
    @Query("DELETE FROM vault_groups") suspend fun clearGroups()
    @Query("DELETE FROM sync_operations") suspend fun clearSyncOperations()
    @Query("DELETE FROM sync_device_heads") suspend fun clearSyncDeviceHeads()
    @Query("DELETE FROM sync_record_states") suspend fun clearSyncRecordStates()
    @Query("DELETE FROM sync_tombstones") suspend fun clearSyncTombstones()
    @Query("DELETE FROM sync_attachment_manifests") suspend fun clearSyncAttachments()
    @Query("DELETE FROM sync_conflicts") suspend fun clearSyncConflicts()
    @Query("DELETE FROM sync_peer_acknowledgements") suspend fun clearSyncAcknowledgements()
    @Query("DELETE FROM sync_memberships") suspend fun clearSyncMemberships()
    @Query("DELETE FROM sync_membership_events") suspend fun clearSyncMembershipEvents()
    @Query("DELETE FROM sync_vault_state") suspend fun clearSyncVaultState()
    @Query("DELETE FROM sync_peers") suspend fun clearSyncPeers()
    @Query("DELETE FROM sync_legacy_operations") suspend fun clearSyncLegacyOperations()
    @Query("SELECT * FROM passkeys ORDER BY rpId, username") suspend fun allPasskeys(): List<VaultPasskey>
    @Query("SELECT id, rpId, username, createdAt FROM passkeys ORDER BY rpId, username") suspend fun passkeySummaries(): List<PasskeySummary>
    @Insert suspend fun insertPasskeys(passkeys: List<VaultPasskey>)
    @Query("DELETE FROM passkeys") suspend fun clearPasskeys()
    @Query("DELETE FROM passkeys WHERE id = :id") suspend fun deletePasskey(id: String)
    @Insert suspend fun insertPasskeyHistory(events: List<PasskeyHistoryEvent>)

    @Transaction
    suspend fun importPasskeys(passkeys: List<VaultPasskey>): PasskeyImportResult {
        require(passkeys.map { it.id }.distinct().size == passkeys.size)
        passkeys.forEach(com.privatevault.app.passkeys.PasskeyCrypto::validateStored)
        val existing = allPasskeys().associateBy { it.id }
        var alreadySaved = 0
        val additions = passkeys.filter { incoming ->
            val saved = existing[incoming.id] ?: return@filter true
            require(saved.copy(createdAt = incoming.createdAt) == incoming) {
                "A different passkey already uses this credential ID. Nothing was imported."
            }
            alreadySaved++
            false
        }
        if (additions.isNotEmpty()) insertPasskeys(additions)
        return PasskeyImportResult(additions.size, alreadySaved)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEntries(entries: List<VaultEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertGroups(groups: List<VaultGroup>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLinks(links: List<EntryGroupCrossRef>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPhotos(photos: List<VaultPhoto>)

    @Transaction
    suspend fun saveEntry(entry: VaultEntry, groupIds: Set<String>) {
        saveEntryExact(entry.copy(updatedAt = System.currentTimeMillis()), groupIds)
    }

    @Transaction
    suspend fun saveEntryExact(entry: VaultEntry, groupIds: Set<String>) {
        require(entry.linkedAuthenticatorId.isBlank() || (entry.type == EntryType.PASSWORD &&
            this.entry(entry.linkedAuthenticatorId)?.entry?.type == EntryType.AUTHENTICATOR)) { "Choose an existing authenticator." }
        val selectedGroups = allGroupsWithEntries().map { it.group }.filter { it.id in groupIds }
        require(selectedGroups.size == groupIds.size && selectedGroups.all { it.folderType == null || it.folderType == entry.type } &&
            selectedGroups.count { it.folderType != null } <= 1) { "Choose one folder for this entry type." }
        insertEntry(entry)
        clearLinks(entry.id)
        groupIds.forEach { link(EntryGroupCrossRef(entry.id, it)) }
    }

    @Transaction
    suspend fun importLogins(requests: List<LoginImportRequest>): LoginImportResult {
        require(requests.all { it.incoming.type == EntryType.PASSWORD })
        require(requests.map { it.incoming.id }.distinct().size == requests.size)
        require(com.privatevault.app.security.deduplicateImportedLogins(requests.map { it.incoming }).size == requests.size)
        val known = loginAndCodeEntries().filter { it.type == EntryType.PASSWORD }.toMutableList()
        var added = 0
        var updated = 0
        var skippedExact = 0
        var skippedConflicts = 0
        for (request in requests) {
            val entry = request.incoming
            val matches = known.filter { com.privatevault.app.security.sameImportedAccount(it, entry) }
            val currentSnapshot = matches.map(LoginImportMatch::from).sortedBy { it.id }
            require(currentSnapshot == request.expectedMatches.sortedBy { it.id }) {
                "Saved passwords changed after review. Review the import again."
            }
            require(request.matchedSavedEntryId == matches.singleOrNull()?.id) {
                "Saved passwords changed after review. Review the import again."
            }
            when (request.action) {
                LoginImportAction.ADD -> {
                    require(matches.isEmpty()) { "Saved passwords changed after review. Review the import again." }
                    require(this.entry(entry.id) == null) { "Saved passwords changed after review. Review the import again." }
                    insertEntry(entry)
                    known.add(entry)
                    added++
                }
                LoginImportAction.SKIP_EXACT -> {
                    require(matches.any { com.privatevault.app.security.sameImportedLogin(it, entry) }) {
                        "Saved passwords changed after review. Review the import again."
                    }
                    skippedExact++
                }
                LoginImportAction.KEEP_SAVED -> {
                    require(matches.size == 1 && !com.privatevault.app.security.sameImportedLogin(matches.single(), entry)) {
                        "Saved passwords changed after review. Review the import again."
                    }
                    skippedConflicts++
                }
                LoginImportAction.USE_IMPORTED -> {
                    require(matches.size == 1 && !com.privatevault.app.security.sameImportedLogin(matches.single(), entry)) {
                        "Saved passwords changed after review. Review the import again."
                    }
                    val index = known.indexOfFirst { it.id == matches.single().id }
                    val replacement = known[index].copy(secondaryValue = entry.secondaryValue, updatedAt = System.currentTimeMillis())
                    updateEntry(replacement)
                    known[index] = replacement
                    updated++
                }
                LoginImportAction.SKIP_AMBIGUOUS -> {
                    require(matches.size > 1) { "Saved passwords changed after review. Review the import again." }
                    skippedConflicts++
                }
            }
        }
        return LoginImportResult(added, updated, skippedExact, skippedConflicts)
    }

    @Transaction
    suspend fun replaceAll(
        entries: List<VaultEntry>,
        groups: List<VaultGroup>,
        links: List<EntryGroupCrossRef>,
        photos: List<VaultPhoto>,
        settings: VaultSettings = VaultSettings(),
        passkeys: List<VaultPasskey> = emptyList(),
        /** The passkey ledger line for each passkey this brings in: restored from a backup, or copied from a sync group. */
        passkeyEvent: PasskeyEventKind = PasskeyEventKind.RESTORED,
        passkeyEventDetail: String = "",
    ) {
        clearSyncOperations()
        clearSyncDeviceHeads()
        clearSyncRecordStates()
        clearSyncTombstones()
        clearSyncAttachments()
        clearSyncConflicts()
        clearSyncAcknowledgements()
        clearSyncMemberships()
        clearSyncMembershipEvents()
        clearSyncVaultState()
        clearSyncPeers()
        clearSyncEpochKeys()
        clearSyncKeyPackages()
        clearEntries()
        clearGroups()
        clearPasskeys()
        insertPasskeys(passkeys)
        // The ledger stays: it is this device's own history, not part of the vault being replaced.
        val now = System.currentTimeMillis()
        insertPasskeyHistory(passkeys.map {
            PasskeyHistoryEvent(passkeyId = it.id, rpId = it.rpId.take(253), username = it.username.take(256),
                kind = passkeyEvent.name, at = now, detail = passkeyEventDetail.take(200))
        })
        insertGroups(groups)
        insertEntries(entries)
        insertLinks(links)
        insertPhotos(photos)
        saveSettings(settings)
    }
}

class EntryTypeConverter {
    @androidx.room.TypeConverter fun fromType(type: EntryType): String = type.name
    @androidx.room.TypeConverter fun toType(value: String): EntryType = EntryType.valueOf(value)
    @androidx.room.TypeConverter fun fromCardKind(kind: CardKind): String = kind.name
    @androidx.room.TypeConverter fun toCardKind(value: String): CardKind = CardKind.valueOf(value)
}

@Database(
    entities = [VaultEntry::class, VaultGroup::class, EntryGroupCrossRef::class, VaultPhoto::class, VaultSettings::class, VaultPasskey::class,
        SyncOperationEntity::class, SyncDeviceHeadEntity::class, SyncRecordStateEntity::class, SyncTombstoneEntity::class,
        SyncAttachmentManifestEntity::class, SyncConflictEntity::class, SyncPeerAcknowledgementEntity::class,
        SyncMembershipEntity::class, SyncVaultStateEntity::class, SyncPeerEntity::class,
        SyncLegacyOperationEntity::class, SyncMembershipEventEntity::class, PasswordHistoryEntry::class, TrashedEntry::class,
        PasskeyHistoryEvent::class, PasskeyOrigin::class, SyncedSiteIcon::class, SyncCheckpointEntity::class,
        SignInMethod::class, SyncLostManagerEntity::class, SyncLineageEntity::class, SyncEpochKeyEntity::class,
        SyncKeyPackageEntity::class],
    version = 27,
    exportSchema = false
)
@androidx.room.TypeConverters(EntryTypeConverter::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao
    abstract fun syncDao(): SyncDao
    abstract fun passkeyHistory(): PasskeyHistoryDao
    abstract fun syncedSiteIcons(): SyncedSiteIconDao
    abstract fun signInMethods(): SignInMethodDao

    companion object {
        /** The content digest of each lineage row, so a merge join never drops an item edited after leaving. */
        internal val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_lineage` ADD COLUMN `contentDigest` TEXT NOT NULL DEFAULT ''")
            }
        }
        /** Keys of later epochs and their signed packages (sync protocol `epoch-keys-v1`). */
        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_epoch_keys` (`vaultId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `contentKey` TEXT NOT NULL, `transportSecret` TEXT NOT NULL, PRIMARY KEY(`vaultId`, `epoch`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_key_packages` (`vaultId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `setJson` TEXT NOT NULL, PRIMARY KEY(`vaultId`, `epoch`))")
            }
        }
        /** A lost managing device marked on this device, and the lineage of the group it left (sync merge join). */
        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_lost_manager` (`vaultId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `membershipCount` INTEGER NOT NULL, `headSequence` INTEGER NOT NULL, `headHash` TEXT NOT NULL, `markedAt` INTEGER NOT NULL, PRIMARY KEY(`vaultId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_lineage` (`fromVaultId` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `recordVersionJson` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`entityType`, `entityId`))")
            }
        }
        /** Sign-in methods shared by Nuvori for Windows (sync protocol `sign-in-methods-v1`). */
        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sign_in_methods` (`site` TEXT NOT NULL, `provider` TEXT NOT NULL, `accountKey` TEXT NOT NULL, `entityId` TEXT NOT NULL, `account` TEXT NOT NULL, `firstUsedAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, `updatedAtUtc` TEXT NOT NULL, `updatedByDevice` TEXT NOT NULL, `pendingDelete` INTEGER NOT NULL, PRIMARY KEY(`site`, `provider`, `accountKey`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sign_in_methods_entityId` ON `sign_in_methods` (`entityId`)")
            }
        }
        /** The enrollment checkpoint this device joined from and its own compaction floor (sync history checkpoints). */
        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_checkpoints` (`vaultId` TEXT NOT NULL, `checkpointJson` TEXT NOT NULL, `floorJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`vaultId`))")
            }
        }
        /** Website icons shared by other devices (sync protocol `site-icons-v1`). */
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `synced_site_icons` (`site` TEXT NOT NULL, `png` BLOB NOT NULL, `updatedAtUtc` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, `updatedByDevice` TEXT NOT NULL, PRIMARY KEY(`site`))")
            }
        }
        /**
         * The passkey ledger and the app that created each passkey, kept on this device only. Passkeys saved before
         * get one "already saved" line at their own creation time, so every passkey has a start in its history.
         */
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `passkey_history` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `passkeyId` TEXT NOT NULL, `rpId` TEXT NOT NULL, `username` TEXT NOT NULL, `kind` TEXT NOT NULL, `at` INTEGER NOT NULL, `appPackage` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `detail` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_passkey_history_passkeyId` ON `passkey_history` (`passkeyId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `passkey_origins` (`passkeyId` TEXT NOT NULL, `appPackage` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `icon` BLOB, PRIMARY KEY(`passkeyId`))")
                db.execSQL("INSERT INTO passkey_history (passkeyId, rpId, username, kind, at, appPackage, appLabel, detail) " +
                    "SELECT id, rpId, username, 'ADDED_BEFORE_HISTORY', createdAt, '', '', '' FROM passkeys")
            }
        }
        /** Previous passwords and Recently deleted, kept on this phone only. */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `password_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entryId` TEXT NOT NULL, `password` TEXT NOT NULL, `replacedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_password_history_entryId` ON `password_history` (`entryId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `trash` (`id` TEXT NOT NULL, `entryId` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `entryJson` TEXT NOT NULL, `groupIds` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, `deletedElsewhere` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_vault_state ADD COLUMN transportSecret TEXT NOT NULL DEFAULT ''")
            }
        }
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_membership_events (vaultId TEXT NOT NULL, sequence INTEGER NOT NULL, formatVersion INTEGER NOT NULL, previousHash TEXT NOT NULL, action TEXT NOT NULL, issuerDeviceId TEXT NOT NULL, subjectDeviceId TEXT NOT NULL, subjectPublicKey TEXT NOT NULL, keyEpoch INTEGER NOT NULL, signature TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(vaultId, sequence))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_membership_events_hash ON sync_membership_events (hash)")
            }
        }
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_legacy_operations (mutationId TEXT NOT NULL PRIMARY KEY, vaultId TEXT NOT NULL, signedOperationJson TEXT NOT NULL)")
            }
        }
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_vault_state (id INTEGER NOT NULL PRIMARY KEY, vaultId TEXT NOT NULL, contentKey TEXT NOT NULL, keyEpoch INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_peers (vaultId TEXT NOT NULL, deviceId TEXT NOT NULL, publicKey TEXT NOT NULL, transportSecret TEXT NOT NULL, PRIMARY KEY(vaultId, deviceId))")
            }
        }
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_memberships (vaultId TEXT NOT NULL, deviceId TEXT NOT NULL, displayName TEXT NOT NULL, identityPublicKey TEXT NOT NULL, status TEXT NOT NULL, addedByDeviceId TEXT NOT NULL, membershipSequence INTEGER NOT NULL, keyEpoch INTEGER NOT NULL, PRIMARY KEY(vaultId, deviceId))")
            }
        }
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_settings ADD COLUMN watchSyncEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_settings ADD COLUMN backgroundTimeoutMs INTEGER NOT NULL DEFAULT 10000")
                db.execSQL("ALTER TABLE vault_settings ADD COLUMN inactivityTimeoutMs INTEGER NOT NULL DEFAULT 60000")
                db.execSQL("ALTER TABLE vault_settings ADD COLUMN masterPasswordIntervalMs INTEGER NOT NULL DEFAULT 86400000")
            }
        }
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN autofillOrigins TEXT NOT NULL DEFAULT ''")
            }
        }
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_settings ADD COLUMN vaultId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_record_states (entityType TEXT NOT NULL, entityId TEXT NOT NULL, revision INTEGER NOT NULL, recordVersionJson TEXT NOT NULL, PRIMARY KEY(entityType, entityId))")
            }
        }
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_operations (mutationId TEXT NOT NULL PRIMARY KEY, formatVersion INTEGER NOT NULL, vaultId TEXT NOT NULL, deviceId TEXT NOT NULL, sequence INTEGER NOT NULL, previousHash TEXT NOT NULL, entityType TEXT NOT NULL, entityId TEXT NOT NULL, kind TEXT NOT NULL, baseRevision INTEGER NOT NULL, recordVersionJson TEXT NOT NULL, occurredAtUtc TEXT NOT NULL, payloadCiphertext TEXT NOT NULL, payloadNonce TEXT NOT NULL, deviceSignature TEXT NOT NULL, hash TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_operations_vaultId_deviceId_sequence ON sync_operations (vaultId, deviceId, sequence)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_operations_hash ON sync_operations (hash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_operations_entityType_entityId ON sync_operations (entityType, entityId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_device_heads (vaultId TEXT NOT NULL, deviceId TEXT NOT NULL, sequence INTEGER NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(vaultId, deviceId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_tombstones (tombstoneId TEXT NOT NULL PRIMARY KEY, entityType TEXT NOT NULL, entityId TEXT NOT NULL, deletedByDeviceId TEXT NOT NULL, deleteSequence INTEGER NOT NULL, recordVersionJson TEXT NOT NULL, deletedAtUtc TEXT NOT NULL, changeHash TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_tombstones_entityType_entityId ON sync_tombstones (entityType, entityId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_attachment_manifests (attachmentId TEXT NOT NULL PRIMARY KEY, ownerEntityType TEXT NOT NULL, ownerEntityId TEXT NOT NULL, encryptedFileName TEXT NOT NULL, ciphertextHash TEXT NOT NULL, sizeBytes INTEGER NOT NULL, keyEpoch INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_attachment_manifests_ownerEntityType_ownerEntityId ON sync_attachment_manifests (ownerEntityType, ownerEntityId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_conflicts (conflictId TEXT NOT NULL PRIMARY KEY, entityType TEXT NOT NULL, entityId TEXT NOT NULL, localChangeHash TEXT NOT NULL, remoteChangeHash TEXT NOT NULL, localVersionJson TEXT NOT NULL, remoteVersionJson TEXT NOT NULL, detectedAtUtc TEXT NOT NULL, resolvedAtUtc TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_conflicts_entityType_entityId ON sync_conflicts (entityType, entityId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_peer_acknowledgements (vaultId TEXT NOT NULL, peerDeviceId TEXT NOT NULL, sourceDeviceId TEXT NOT NULL, sequence INTEGER NOT NULL, acknowledgedAtUtc TEXT NOT NULL, PRIMARY KEY(vaultId, peerDeviceId, sourceDeviceId))")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_groups ADD COLUMN folderType TEXT")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS passkeys (id TEXT NOT NULL PRIMARY KEY, rpId TEXT NOT NULL, userHandle TEXT NOT NULL, username TEXT NOT NULL, displayName TEXT NOT NULL, privateKey TEXT NOT NULL, publicKey TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN autofillSignatures TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN linkedAuthenticatorId TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN linkedApps TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN totpAlgorithm TEXT NOT NULL DEFAULT 'SHA1'")
                db.execSQL("ALTER TABLE entries ADD COLUMN totpDigits INTEGER NOT NULL DEFAULT 6")
                db.execSQL("ALTER TABLE entries ADD COLUMN totpPeriod INTEGER NOT NULL DEFAULT 30")
                db.execSQL("CREATE TABLE IF NOT EXISTS vault_settings (id INTEGER NOT NULL PRIMARY KEY, lightMode INTEGER NOT NULL, nfcEnabled INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN network TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN cardKind TEXT NOT NULL DEFAULT 'CREDIT'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN lastOpenedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photos ADD COLUMN encryptedThumbnailFileName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE photos ADD COLUMN isCover INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context, key: ByteArray): VaultDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(key.copyOf())
            return Room.databaseBuilder(context, VaultDatabase::class.java, "vault.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27)
                .build()
        }
    }
}
