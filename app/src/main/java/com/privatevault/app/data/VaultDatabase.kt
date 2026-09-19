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

enum class EntryType { CARD, QUESTION, PASSWORD, NOTE, AUTHENTICATOR }
enum class CardKind { CREDIT, DEBIT }

@Entity(tableName = "vault_settings")
data class VaultSettings(@androidx.room.PrimaryKey val id: Int = 1, val lightMode: Boolean = false, val nfcEnabled: Boolean = false)

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

    @Query("SELECT * FROM entries WHERE type = 'AUTHENTICATOR' ORDER BY title COLLATE NOCASE, primaryValue COLLATE NOCASE")
    suspend fun authenticatorEntries(): List<VaultEntry>

    @Query("SELECT * FROM entries WHERE type IN ('PASSWORD', 'AUTHENTICATOR') ORDER BY title COLLATE NOCASE")
    suspend fun loginAndCodeEntries(): List<VaultEntry>

    @Query("UPDATE entries SET linkedAuthenticatorId = '' WHERE linkedAuthenticatorId = :id")
    suspend fun clearAuthenticatorLinks(id: String)

    @Transaction
    suspend fun deleteEntryAndLinks(entry: VaultEntry) {
        clearAuthenticatorLinks(entry.id)
        deleteEntry(entry)
    }

    @Transaction
    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: String): EntryWithDetails?

    @androidx.room.Upsert
    suspend fun insertEntry(entry: VaultEntry)

    @Update suspend fun updateEntry(entry: VaultEntry)

    @Transaction
    suspend fun saveBrowserLogin(origin: String, username: String, password: String, expected: VaultEntry?) {
        require(com.privatevault.app.security.httpsOrigin(origin) == origin && username.isNotBlank() && password.isNotEmpty())
        if (expected == null) {
            val duplicate = loginAndCodeEntries().any { it.type == EntryType.PASSWORD &&
                com.privatevault.app.security.httpsOrigin(it.tertiaryValue) == origin && it.primaryValue == username && it.secondaryValue == password }
            if (!duplicate) insertEntry(VaultEntry(type = EntryType.PASSWORD, title = origin.removePrefix("https://"),
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

    @Delete suspend fun deleteGroup(group: VaultGroup)

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
        val options = settings() ?: VaultSettings()
        return com.privatevault.app.backup.BackupData(entries.map { it.entry }, allGroupsWithEntries().map { it.group }, allLinks(), entries.flatMap { it.photos }, options.lightMode, options.nfcEnabled, allPasskeys())
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: VaultPhoto)

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
    @Query("DELETE FROM vault_groups") suspend fun clearGroups()
    @Query("SELECT * FROM passkeys ORDER BY rpId, username") suspend fun allPasskeys(): List<VaultPasskey>
    @Query("SELECT id, rpId, username, createdAt FROM passkeys ORDER BY rpId, username") suspend fun passkeySummaries(): List<PasskeySummary>
    @Insert suspend fun insertPasskeys(passkeys: List<VaultPasskey>)
    @Query("DELETE FROM passkeys") suspend fun clearPasskeys()
    @Query("DELETE FROM passkeys WHERE id = :id") suspend fun deletePasskey(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEntries(entries: List<VaultEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertGroups(groups: List<VaultGroup>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLinks(links: List<EntryGroupCrossRef>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPhotos(photos: List<VaultPhoto>)

    @Transaction
    suspend fun saveEntry(entry: VaultEntry, groupIds: Set<String>) {
        require(entry.linkedAuthenticatorId.isBlank() || (entry.type == EntryType.PASSWORD &&
            this.entry(entry.linkedAuthenticatorId)?.entry?.type == EntryType.AUTHENTICATOR)) { "Choose an existing authenticator." }
        val selectedGroups = allGroupsWithEntries().map { it.group }.filter { it.id in groupIds }
        require(selectedGroups.size == groupIds.size && selectedGroups.all { it.folderType == null || it.folderType == entry.type } &&
            selectedGroups.count { it.folderType != null } <= 1) { "Choose one folder for this entry type." }
        insertEntry(entry.copy(updatedAt = System.currentTimeMillis()))
        clearLinks(entry.id)
        groupIds.forEach { link(EntryGroupCrossRef(entry.id, it)) }
    }

    @Transaction
    suspend fun importLogins(incoming: List<VaultEntry>): Int {
        val known = loginAndCodeEntries().toMutableList()
        var added = 0
        for (entry in incoming) {
            require(entry.type == EntryType.PASSWORD)
            if (known.none { com.privatevault.app.security.sameImportedLogin(it, entry) }) {
                insertEntry(entry)
                known.add(entry)
                added++
            }
        }
        return added
    }

    @Transaction
    suspend fun replaceAll(
        entries: List<VaultEntry>,
        groups: List<VaultGroup>,
        links: List<EntryGroupCrossRef>,
        photos: List<VaultPhoto>,
        settings: VaultSettings = VaultSettings(),
        passkeys: List<VaultPasskey> = emptyList()
    ) {
        clearEntries()
        clearGroups()
        clearPasskeys()
        insertPasskeys(passkeys)
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
    entities = [VaultEntry::class, VaultGroup::class, EntryGroupCrossRef::class, VaultPhoto::class, VaultSettings::class, VaultPasskey::class],
    version = 9,
    exportSchema = false
)
@androidx.room.TypeConverters(EntryTypeConverter::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
        }
    }
}
