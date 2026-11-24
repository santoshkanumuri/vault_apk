package com.privatevault.app.sync

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncEpochKeyEntity
import com.privatevault.app.data.SyncKeyPackageEntity
import com.privatevault.app.data.VaultDatabase
import java.security.SecureRandom
import java.util.Base64

/**
 * Key rotation at a REMOVE (feature [EpochKeys.FEATURE]) and how its keys travel. The gate is the
 * signed key statements, never the announced feature string (a LAN attacker can strip a hello).
 */
internal object EpochKeyRotation {
    private const val FRAME_PREFIX = "keys1"
    /** Sets per `keys1` frame (Windows `epoch_keys::MAX_SETS`); the frame also stays under [MAX_FRAME_BYTES]. */
    const val MAX_SETS = 64
    private const val MAX_FRAME_BYTES = 200 * 1024
    private val gson = Gson()

    /** Survivors (active, not [removing], not this device) without a valid key statement: they block rotation. */
    fun missingStatements(verified: VerifiedMembership, removing: String?, localDeviceId: String,
        statements: Map<String, KeyStatement>): List<String> {
        val vaultId = verified.events.first().vaultId
        return verified.members.filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != removing && it.deviceId != localDeviceId }
            .filter { member -> statements[member.deviceId]?.valid(vaultId, member.identityPublicKey) != true }
            .map { it.deviceId }
    }

    /**
     * True when [set] is signed by the issuer of the REMOVE that started its epoch, in this group's
     * verified history. A set for an epoch this device has not reached yet is not valid until it has.
     */
    fun validSet(set: PackageSet, vaultId: String, verified: VerifiedMembership): Boolean {
        val remove = verified.events.firstOrNull { it.action == MembershipAction.REMOVE && it.keyEpoch == set.epoch } ?: return false
        val issuer = verified.members.firstOrNull { it.deviceId == remove.issuerDeviceId } ?: return false
        return set.valid(vaultId, issuer.identityPublicKey, remove.hash)
    }

    /**
     * The newest epoch whose keys this device must hold before it sends anything ([LockedSyncStore.stranded]):
     * the epoch of a REMOVE after this device joined, when a valid set for it holds a package for this
     * device, or when no set for it is known yet and its issuer is a build that rotates (a valid key
     * statement of the issuer is known here). A signed marker (a set without this device's package)
     * ends the wait for that epoch. Null when nothing is awaited. An issuer with no known statement is
     * an older build that never rotates, so its REMOVEs are not awaited (version skew).
     */
    fun awaitedEpoch(verified: VerifiedMembership, localDeviceId: String, sets: Collection<PackageSet>,
        statements: Map<String, KeyStatement>): Long? {
        val vaultId = verified.events.first().vaultId
        val byEpoch = sets.filter { validSet(it, vaultId, verified) }.associateBy { it.epoch }
        val joined = verified.events.indexOfLast { it.subjectDeviceId == localDeviceId &&
            (it.action == MembershipAction.ADD || it.action == MembershipAction.GENESIS) }
        if (joined < 0) return null
        return verified.events.withIndex().filter { (index, event) ->
            index > joined && event.action == MembershipAction.REMOVE && event.keyEpoch >= 2 && event.subjectDeviceId != localDeviceId
        }.map { it.value }.filter { remove ->
            val set = byEpoch[remove.keyEpoch]
            if (set != null) set.packages.containsKey(localDeviceId)
            else verified.members.firstOrNull { it.deviceId == remove.issuerDeviceId }?.let { issuer ->
                statements[issuer.deviceId]?.valid(vaultId, issuer.identityPublicKey) == true
            } == true
        }.maxOfOrNull { it.keyEpoch }
    }

    /**
     * Inside the REMOVE transaction ([removeSyncMember]): new random S and P for the epoch [remove]
     * starts, one package per survivor and the signed set, all stored with the REMOVE. K is derived
     * from P and K(1) ([EpochKeys.contentKey]). When a survivor has no valid statement (the dialog
     * said so) it signs an empty marker set instead, so survivors stop waiting. True when it rotated.
     */
    suspend fun rotate(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, after: VerifiedMembership,
        remove: SyncMembershipEvent, statements: Map<String, KeyStatement>, random: SecureRandom = SecureRandom()): Boolean {
        val local = identityStore.getOrCreate().deviceId
        if (remove.action != MembershipAction.REMOVE || remove.issuerDeviceId != local) return false
        val vaultId = after.events.first().vaultId
        val epoch = remove.keyEpoch
        val dao = database.syncDao()
        // Keys of an epoch are made once: making them again would strand changes already written.
        if (dao.epochKeys().any { it.vaultId == vaultId && it.epoch == epoch } ||
            dao.keyPackages(vaultId).any { it.epoch == epoch }) return false
        val survivors = after.members.filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != local }
        if (missingStatements(after, remove.subjectDeviceId, local, statements).isNotEmpty()) {
            if (survivors.isNotEmpty()) dao.saveKeyPackages(SyncKeyPackageEntity(vaultId, epoch,
                gson.toJson(PackageSet.sign(vaultId, epoch, remove.hash, emptyMap(), identityStore::sign))))
            return false
        }
        val first = Base64.getUrlDecoder().decode(requireNotNull(dao.vaultState()) { "Sync group is not ready" }
            .also { require(it.vaultId == vaultId) }.contentKey)
        val secret = ByteArray(32).also(random::nextBytes)
        val packageKey = ByteArray(32).also(random::nextBytes)
        val key = EpochKeys.contentKey(first, vaultId, epoch, packageKey)
        try {
            val packages = survivors.associate { member ->
                val public = requireNotNull(statements.getValue(member.deviceId).publicKey())
                member.deviceId to EpochKeys.seal(vaultId, epoch, member.deviceId, public, secret, packageKey, random)
            }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            dao.saveEpochKey(SyncEpochKeyEntity(vaultId, epoch, encoder.encodeToString(key), encoder.encodeToString(secret)))
            // A lone manager has nobody to send keys to; the new keys still lock the removed device out of Drive.
            if (packages.isNotEmpty()) {
                val set = PackageSet.sign(vaultId, epoch, remove.hash, packages, identityStore::sign)
                dao.saveKeyPackages(SyncKeyPackageEntity(vaultId, epoch, gson.toJson(set)))
            }
            return true
        } finally { secret.fill(0); packageKey.fill(0); key.fill(0); first.fill(0) }
    }

    /**
     * Managing device, unlocked: gives every REMOVE it signed a set. A REMOVE answered from a leave
     * request (Wi-Fi or Drive, signed while locked) has none yet: the one that started the current
     * epoch rotates now when the gate is open ([rotate]); any other gets an empty marker. Survivors
     * wait for this ([awaitedEpoch]), so a device that left can't read what they add next. Returns
     * the number of sets made.
     */
    suspend fun coverRemovals(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore,
        statements: Map<String, KeyStatement>, random: SecureRandom = SecureRandom()): Int = database.withTransaction {
        val local = identityStore.getOrCreate().deviceId
        val dao = database.syncDao()
        val vaultId = database.dao().settings()?.vaultId ?: return@withTransaction 0
        val events = dao.membershipEvents(vaultId).map { it.toEvent() }
        if (events.isEmpty()) return@withTransaction 0
        val verified = SyncMembershipManager.verify(events)
        if (verified.managerDeviceId != local) return@withTransaction 0
        var made = 0
        events.withIndex().filter { (_, event) -> event.action == MembershipAction.REMOVE && event.issuerDeviceId == local &&
            event.keyEpoch >= 2 }.forEach { (index, remove) ->
            val covered = dao.keyPackages(vaultId).any { it.epoch == remove.keyEpoch } ||
                dao.epochKeys().any { it.vaultId == vaultId && it.epoch == remove.keyEpoch }
            if (covered) return@forEach
            if (remove.keyEpoch == verified.keyEpoch) {
                rotate(database, identityStore, verified, remove, statements, random)
                made++
                return@forEach
            }
            val after = SyncMembershipManager.verify(events.take(index + 1))
            if (after.members.none { it.status == MemberStatus.ACTIVE.name && it.deviceId != local }) return@forEach
            dao.saveKeyPackages(SyncKeyPackageEntity(vaultId, remove.keyEpoch,
                gson.toJson(PackageSet.sign(vaultId, remove.keyEpoch, remove.hash, emptyMap(), identityStore::sign))))
            made++
        }
        made
    }

    /**
     * `keys1{"statement": …, "packages": […]}`: one frame each way after the identity proofs. Sets go
     * oldest first, so a survivor many epochs behind gets the ones it needs first, up to [MAX_SETS] and
     * [MAX_FRAME_BYTES].
     */
    fun encodeFrame(statement: KeyStatement?, sets: List<PackageSet>): ByteArray = (FRAME_PREFIX + JsonObject().apply {
        statement?.let { add("statement", gson.toJsonTree(it)) }
        var bytes = 0
        add("packages", JsonArray().apply {
            for (set in sets.sortedBy { it.epoch }.take(MAX_SETS)) {
                val tree = gson.toJsonTree(set)
                bytes += tree.toString().length + 1
                if (bytes > MAX_FRAME_BYTES) break
                add(tree)
            }
        })
    }.toString()).toByteArray(Charsets.UTF_8)

    fun parseFrame(bytes: ByteArray): Pair<KeyStatement?, List<PackageSet>> {
        val text = bytes.toString(Charsets.UTF_8)
        require(text.startsWith(FRAME_PREFIX) && bytes.size <= 256 * 1024) { "Invalid key frame" }
        val json = JsonParser.parseString(text.removePrefix(FRAME_PREFIX)).asJsonObject
        val statement = json.get("statement")?.takeIf { it.isJsonObject }?.let {
            runCatching { gson.fromJson(it, KeyStatement::class.java) }.getOrNull()
        }
        val sets = json.getAsJsonArray("packages")?.take(MAX_SETS)?.mapNotNull {
            runCatching { gson.fromJson(it, PackageSet::class.java) }.getOrNull()
        }.orEmpty()
        return statement to sets
    }
}
