package com.privatevault.app.sync

import android.content.Context
import android.net.InetAddresses
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.annotation.Keep
import androidx.room.withTransaction
import kotlinx.coroutines.ensureActive
import com.google.gson.Gson
import com.privatevault.app.data.*
import android.database.sqlite.SQLiteConstraintException
import org.json.JSONException
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [transportSecret] is the group's epoch-1 transport secret (from `sync_vault_state`); the secret in
 * use is [TransportEpochs.secret] for [keyEpoch], the epoch of the verified membership history.
 * [epochRetiredAt] maps an epoch (as text) to when this device first saw the next one.
 */
@Keep
internal data class TransportMirror(val vaultId: String, val localDeviceId: String,
    val publicKey: String, val transportSecret: String, val keyEpoch: Long,
    val peers: List<SyncPeerEntity>, val members: List<SyncMembershipEntity>,
    val membershipEvents: List<SyncMembershipEventEntity>, val heads: List<SyncDeviceHeadEntity>,
    val peerProgress: Map<String, SyncProgress> = emptyMap(),
    val peerAddresses: Map<String, String> = emptyMap(),
    val lastContactAt: Map<String, Long> = emptyMap(),
    val lastExchangeAt: Map<String, Long> = emptyMap(),
    val pendingPhotoHashes: Set<String> = emptySet(),
    val epochRetiredAt: Map<String, Long> = emptyMap(),
    val clientOnlyPeers: Set<String> = emptySet(),
    /** What each peer announced in its last sync handshake (see [RemovalCutoff.FEATURE]). */
    val peerFeatures: Map<String, List<String>> = emptyMap(),
    /** Photo blob hashes the vault's attachments reference, for the cloud upload ([CloudSyncPass]). */
    val attachmentHashes: Set<String> = emptySet(),
    /** Per author, the highest change the relay folder dropped because nobody needs it from here ([RelayRetention]). */
    val relayFloor: Map<String, Long> = emptyMap(),
    /** "Mark as lost" on this device ([LostManagerFreeze]); null when nothing is frozen. From the vault database. */
    val lostManager: LostManagerFreeze.Freeze? = null,
    /** Random transport secrets of rotated epochs (epoch -> base64url, [EpochKeys]). Never a content key. */
    val transportSecrets: Map<String, String> = emptyMap(),
    /** Verified key statements of members, this device's own included ([KeyStatement]). */
    val keyStatements: Map<String, KeyStatement> = emptyMap(),
    /** Verified package sets by epoch ([PackageSet]): sealed, so the locked store may keep and relay them. */
    val keyPackages: Map<String, PackageSet> = emptyMap()) {
    /** [transportSecrets] by epoch. */
    fun randomSecrets(): Map<Long, String> = transportSecrets.mapNotNull { (epoch, secret) -> epoch.toLongOrNull()?.let { it to secret } }.toMap()
    fun epochSecret(epoch: Long): String = TransportEpochs.secret(transportSecret, vaultId, epoch, randomSecrets())
    /** The Google Drive keys new files use now ([EpochKeys.driveSecret]). */
    /** The newest epoch this device must hold keys for before it sends anything ([EpochKeyRotation.awaitedEpoch]). */
    fun awaitedEpoch(): Long? = runCatching {
        EpochKeyRotation.awaitedEpoch(SyncMembershipManager.verify(membershipEvents.map { it.toEvent() }), localDeviceId,
            keyPackages.values, keyStatements)
    }.getOrNull()

    /**
     * True while this device knows a REMOVE that made new keys (or may have) but its package has not
     * arrived. It then sends nothing, over Wi-Fi or Google Drive: its own changes stay on this device
     * until the new keys arrive, and then travel only under them. So a removed device, which knows
     * every older key and prefix, never gets anything this device wrote after it learned of the REMOVE.
     */
    fun stranded(): Boolean {
        val awaited = awaitedEpoch() ?: return false
        return awaited > (randomSecrets().keys.maxOrNull() ?: 1L)
    }

    fun driveKeys(): CloudKeys? {
        val decoder = Base64.getUrlDecoder()
        val root = decoder.decode(transportSecret)
        val random = randomSecrets().mapValues { decoder.decode(it.value) }
        return try { CloudKeys.derive(EpochKeys.driveSecret(root, keyEpoch, random).second, vaultId) }
        finally { root.fill(0); random.values.forEach { it.fill(0) } }
    }
}

/**
 * A leave request kept after this device already left locally. It holds only what is needed to
 * prove the request again in a later session: the old group's IDs, the signed request and the old
 * epoch's transport secret (never the content key or vault data).
 */
@Keep
internal data class PendingLeaveNotice(val vaultId: String, val deviceId: String, val membershipHead: String,
    val keyEpoch: Long, val createdAt: Long, val signature: String, val transportSecret: String,
    val addresses: List<String> = emptyList(), val port: Int = 0, val lastAttemptAt: Long = 0,
    /** The sealed Google Drive copy of the request ([CloudLeave]), made before leaving; empty without Drive sync. */
    val driveName: String = "", val driveFile: String = "", val driveUploadedAt: Long = 0) {
    fun request() = LeaveRequest(vaultId, deviceId, membershipHead, keyEpoch, createdAt)
}

/** Shown after a verified REMOVE of this device. [byName] is the manager's device label, if known. */
@Keep
data class SyncRemovalNotice(val vaultId: String, val byDeviceId: String, val byName: String,
    val at: Long, val leftLocally: Boolean = false)

/** Devices whose changes a peer accepts: the active members of the signed history. */
internal fun relayableAuthors(members: List<SyncMembershipEntity>): Set<String> =
    members.filter { it.status == MemberStatus.ACTIVE.name }.mapTo(hashSetOf()) { it.deviceId }

internal class SyncQueueFullException : Exception("Unlock this device to apply queued changes")

/**
 * Checks a received change against the signed membership in [mirror]: an active member, or a removed
 * one within its [RemovalCutoff], with a valid signature and hash. Shared by Wi-Fi and cloud sync.
 */
internal fun verifyIncomingOperation(mirror: TransportMirror, operation: SyncOperationEntity) {
    require(operation.vaultId == mirror.vaultId)
    val member = requireNotNull(mirror.members.firstOrNull { it.deviceId == operation.deviceId }) { "Unknown sender" }
    require(member.status == MemberStatus.ACTIVE.name || RemovalCutoff.allows(
        RemovalCutoff.cutoffsOf(mirror.membershipEvents)[operation.deviceId], operation.sequence, operation.hash)) {
        "Unknown or revoked sender"
    }
    // A managing device marked as lost here: nothing after its last known change.
    require(LostManagerFreeze.allowsChange(mirror.lostManager, operation.deviceId, operation.sequence, operation.hash)) {
        "That device was marked as lost"
    }
    val change = operation.toSyncChange()
    change.validate()
    // A change can't claim a key epoch the signed membership has not reached: it would wait forever.
    // (A malformed nonce is set aside when it applies, as before.)
    require((EpochKeys.epochOf(change.payloadNonce) ?: 1L) <= mirror.keyEpoch) { "Change of an unknown key epoch" }
    require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey),
        change.signingBytes(), Base64.getUrlDecoder().decode(change.deviceSignature))) { "Invalid sender signature" }
}

/**
 * [mirror] with the signed history [events] adopted, or null when [events] adds nothing. Throws for
 * an invalid chain, another vault, an older key epoch or a history that conflicts with the stored one.
 */
internal fun mirrorWithMembership(mirror: TransportMirror, events: List<SyncMembershipEventEntity>): TransportMirror? {
    val current = mirror.membershipEvents.map { it.toEvent() }
    // After "Mark as lost" the old group's membership stays as it was ([LostManagerFreeze]).
    val kept = LostManagerFreeze.keptEvents(mirror.lostManager, events)
    val incoming = kept.map { it.toEvent() }
    SyncMembershipManager.verify(current)
    val verified = SyncMembershipManager.verify(incoming)
    require(verified.events.first().vaultId == mirror.vaultId && verified.keyEpoch >= mirror.keyEpoch)
    require(current.zip(incoming).all { (left, right) -> left.hash == right.hash }) {
        "Conflicting membership history"
    }
    if (incoming.size <= current.size) return null
    val members = verified.members.map { member ->
        val label = mirror.members.firstOrNull { it.deviceId == member.deviceId }?.displayName
        member.copy(displayName = label ?: member.displayName)
    }
    val retiredAt = if (verified.keyEpoch <= mirror.keyEpoch) mirror.epochRetiredAt else {
        val now = System.currentTimeMillis()
        mirror.epochRetiredAt + (mirror.keyEpoch until verified.keyEpoch).associate { it.toString() to now }
    }
    return mirror.copy(membershipEvents = kept, members = members, keyEpoch = verified.keyEpoch, epochRetiredAt = retiredAt)
}

/**
 * The membership to write into the vault when queued changes apply: the longer of the vault's
 * [stored] history and the transport [mirrored] one, which must share a prefix. A mirror published
 * before a REMOVE committed in the vault is shorter; projecting it would make the removed device
 * ACTIVE again while the REMOVE stays in the event log.
 */
internal fun newerMembership(stored: List<SyncMembershipEventEntity>,
    mirrored: List<SyncMembershipEventEntity>): VerifiedMembership {
    require(stored.zip(mirrored).all { (left, right) -> left.hash == right.hash }) { "Conflicting membership history" }
    return SyncMembershipManager.verify((if (mirrored.size >= stored.size) mirrored else stored).map { it.toEvent() })
}

/** Contains transport secrets and encrypted operations only. It never stores a vault content key. */
internal class LockedSyncStore(private val context: Context,
    private val directory: File = File(context.noBackupFilesDir, "sync-transport")) : CloudSyncStore {
    private data class OperationPointer(val vaultId: String, val deviceId: String,
        val sequence: Long, val hash: String, val previousHash: String)
    private data class QueuedPointer(val vaultId: String, val link: ChainLink, val meta: QueuedOperationMeta)

    private val outgoing = File(directory, "outgoing")
    private val incoming = File(directory, "incoming")
    private val rejected = File(directory, "rejected")
    private val leaveNoticeFile = File(directory, "leave-notice")
    private val removalNoticeFile = File(directory, "removal-notice")
    private val cloudStateFile = File(directory, "cloud-state")
    private val cloudFilesFile = File(directory, "cloud-files")
    /** The sealed chain coordinates of the relay folder ([outgoingPointers]). */
    private val relayIndexFile = File(directory, "outgoing-index")
    override val photoBlobs = PhotoSyncBlobs(File(directory, "blobs"))
    private val gson = Gson()
    private var outgoingIndex: List<OperationPointer>? = null
    /** Chain coordinates of the incoming folder, so each received change is not a full rescan. */
    private var incomingIndex: List<QueuedPointer>? = null
    /** Chain coordinates of the set-aside folder: they count as received ([progress]). */
    private var rejectedIndex: List<Pair<String, ChainLink>>? = null
    @Volatile private var cachedWrappingKey: SecretKey? = null

    /** Serializes [publish] with the moves of applied changes into the relay folder ([applyQueued]). */
    private val relayLock = Mutex()
    /** The database whose relay folder the last full scan rebuilt; null after [clear]. */
    @Volatile private var fullScanFor: WeakReference<VaultDatabase>? = null
    /** Raised by [clear], so a publish that read the folder before it does not write a stale one. */
    private var generation = 0L

    /**
     * Writes the transport mirror and the relay folder from the vault database. Usually it loads
     * only the changes above what the folder already reflects ([RelayRetention.relayTop]): O(new
     * changes). The first publish for an unlocked database, the first after [clear], or one with
     * [full] reads the chain coordinates of every change instead (no payloads) and loads only the
     * relay files that are missing.
     */
    suspend fun publish(database: VaultDatabase, full: Boolean = false) {
        val identity = AndroidDeviceIdentityStore(context).getOrCreate()
        val changed = relayLock.withLock {
            publishChanged = false
            val scanAll = full || fullScanFor?.get() !== database
            // An incremental publish that finds the folder reset falls back to a full one.
            check(publishLocked(database, identity, scanAll) || !scanAll && publishLocked(database, identity, true)) {
                "Sync relay changed during publish"
            }
            fullScanFor = WeakReference(database)
            publishChanged
        }
        // H4: whatever put a change in the relay folder (the main app, autofill, pairing) also asks
        // Google Drive to upload it; the request does nothing when Drive is off.
        if (changed) CloudSyncRunner.request(context)
    }

    /** Set by [finishRelay] when a publish wrote a change or new membership events. Read under [relayLock]. */
    @Volatile private var publishChanged = false

    private data class PublishedState(val mirror: TransportMirror, val newOperations: List<SyncOperationEntity>?,
        val pointers: List<SyncOperationPointer>?, val photoHashes: Set<String>, val hasConflicts: Boolean)

    private data class RelayPlan(val generation: Long, val state: PublishedState, val selected: TransportMirror,
        val keep: List<OperationPointer>, val drop: List<OperationPointer>, val load: List<String>,
        val write: List<SyncOperationEntity>, val full: Boolean)

    /** False when an incremental publish can't trust the folder, or [clear] ran meanwhile. */
    private suspend fun publishLocked(database: VaultDatabase, identity: DeviceIdentity, scanAll: Boolean): Boolean {
        // Lock order: the store's monitor is never held while waiting on the database.
        val top = if (scanAll) null else synchronized(this) {
            val mirror = snapshot() ?: return false
            mirror.vaultId to RelayRetention.relayTop(outgoingPointers().filter { it.vaultId == mirror.vaultId }
                .map { RelayRetention.Held(it.deviceId, it.sequence, it.hash) }, mirror.relayFloor)
        }
        val state = database.withTransaction {
            val dao = database.syncDao()
            val vaultId = requireNotNull(database.dao().settings()).vaultId
            val group = requireNotNull(dao.vaultState()) { "Sync group is not ready" }
            require(group.vaultId == vaultId && Base64.getUrlDecoder().decode(group.transportSecret).size == 32)
            if (top != null && top.first != vaultId) return@withTransaction null
            val events = dao.membershipEvents(vaultId)
            val epoch = events.takeIf { it.isNotEmpty() }?.let { list ->
                SyncMembershipManager.verify(list.map { it.toEvent() }).keyEpoch
            } ?: group.keyEpoch
            val heads = dao.deviceHeads(vaultId)
            val epochKeys = dao.epochKeys().filter { it.vaultId == vaultId }
            val mirror = TransportMirror(vaultId, identity.deviceId, identity.publicKeyBase64Url,
                group.transportSecret, epoch, dao.peers(vaultId), dao.memberships(vaultId), events, heads,
                lostManager = LostManagerFreeze.of(dao.lostManager(vaultId)),
                transportSecrets = epochKeys.associate { it.epoch.toString() to it.transportSecret },
                keyPackages = dao.keyPackages(vaultId).mapNotNull { row ->
                    runCatching { gson.fromJson(row.setJson, PackageSet::class.java) }.getOrNull()?.let { row.epoch.toString() to it }
                }.toMap())
            val newOperations = top?.second?.let { relayTop ->
                heads.flatMap { head -> operationsAbove(database, vaultId, head, relayTop[head.deviceId] ?: 0L) }
            }
            val photoHashes = dao.attachments().mapTo(hashSetOf()) { it.ciphertextHash }
            PublishedState(mirror.copy(attachmentHashes = photoHashes), newOperations,
                if (top == null) dao.operationPointers() else null, photoHashes,
                dao.conflicts().any { it.resolvedAtUtc.isEmpty() })
        } ?: return false
        val plan = synchronized(this) { planRelay(state) } ?: return false
        // Payloads only for retained changes whose relay file is missing, 500 a query.
        val loaded = plan.load.chunked(500).flatMap { database.syncDao().operationsByHashes(it) }
        return synchronized(this) { finishRelay(plan, loaded) }
    }

    /** Changes of [head]'s author above [after], up to the head, 500 a query. */
    private suspend fun operationsAbove(database: VaultDatabase, vaultId: String, head: SyncDeviceHeadEntity,
        after: Long): List<SyncOperationEntity> {
        val result = ArrayList<SyncOperationEntity>()
        var reach = after
        while (reach < head.sequence) {
            val batch = database.syncDao().operationsAfter(vaultId, head.deviceId, reach, 500)
            if (batch.isEmpty()) break
            result += batch
            reach = batch.last().sequence
        }
        return result
    }

    /** The mirror to write: the database's, keeping what only the transport store knows. */
    private fun selectMirror(mirror: TransportMirror, previous: TransportMirror?): TransportMirror {
        if (previous == null || previous.vaultId != mirror.vaultId) return mirror
        require(previous.transportSecret == mirror.transportSecret)
        val databaseEvents = mirror.membershipEvents.map { it.toEvent() }
        // A freeze ([LostManagerFreeze]) caps what the transport store may hold beyond the vault's history.
        val storedEntities = LostManagerFreeze.keptEvents(mirror.lostManager, previous.membershipEvents)
        val storedEvents = storedEntities.map { it.toEvent() }
        SyncMembershipManager.verify(databaseEvents)
        val stored = SyncMembershipManager.verify(storedEvents)
        require(databaseEvents.zip(storedEvents).all { (left, right) -> left.hash == right.hash }) {
            "Conflicting membership history"
        }
        val kept = if (storedEvents.size > databaseEvents.size) mirror.copy(
            membershipEvents = storedEntities, members = if (storedEntities.size == previous.membershipEvents.size)
                previous.members else stored.members.map { member ->
                    member.copy(displayName = previous.members.firstOrNull { it.deviceId == member.deviceId }?.displayName
                        ?: member.displayName) },
            keyEpoch = stored.keyEpoch)
            else mirror
        return kept.copy(peerProgress = previous.peerProgress, peerAddresses = previous.peerAddresses,
            lastContactAt = previous.lastContactAt, lastExchangeAt = previous.lastExchangeAt,
            pendingPhotoHashes = previous.pendingPhotoHashes, clientOnlyPeers = previous.clientOnlyPeers,
            peerFeatures = previous.peerFeatures, relayFloor = previous.relayFloor,
            // Keys learned while locked stay until the vault stores them; the vault's own win.
            transportSecrets = previous.transportSecrets + kept.transportSecrets,
            keyStatements = previous.keyStatements, keyPackages = previous.keyPackages + kept.keyPackages,
            epochRetiredAt = retire(previous.epochRetiredAt, previous.keyEpoch, kept.keyEpoch))
    }

    private fun retentionContext(selected: TransportMirror): RelayRetention.Context {
        val cloud = cloudState().normalized()
        return RelayRetention.Context(selected.localDeviceId,
            selected.members.filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != selected.localDeviceId }
                .mapTo(hashSetOf()) { it.deviceId },
            selected.peerProgress.mapValues { (_, progress) -> progress.applied.mapValues { it.value.sequence } },
            RemovalCutoff.cutoffsOf(selected.membershipEvents), relayableAuthors(selected.members),
            // Cloud sync needs a gap-free history on the drive, so changes stay until the drive holds them.
            if (!cloud.enabled) null else if (cloud.vaultId == selected.vaultId) cloud.covered else emptyMap())
    }

    /** Null when the folder state no longer matches an incremental publish (another group or a cleared store). */
    private fun planRelay(state: PublishedState): RelayPlan? {
        val previous = snapshot()
        val full = state.pointers != null
        if (!full && (previous == null || previous.vaultId != state.mirror.vaultId)) return null
        if (previous != null && previous.vaultId != state.mirror.vaultId) clear()
        val selected = selectMirror(state.mirror, previous)
        val context = retentionContext(selected)
        fun kept(pointer: OperationPointer) = RelayRetention.keep(
            RelayRetention.Held(pointer.deviceId, pointer.sequence, pointer.hash), context)
        fun pointer(it: SyncOperationEntity) = OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash, it.previousHash)
        return if (full) {
            val (keep, drop) = state.pointers!!.map { OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash,
                it.previousHash) }.partition(::kept)
            RelayPlan(generation, state, selected, keep, drop,
                keep.filter { !File(outgoing, it.hash).exists() }.map { it.hash }, emptyList(), full = true)
        } else {
            val held = outgoingPointers().filter { it.vaultId == selected.vaultId }
            val heldHashes = held.mapTo(hashSetOf()) { it.hash }
            val (keepFresh, dropFresh) = state.newOperations!!.filter { it.hash !in heldHashes }
                .partition { kept(pointer(it)) }
            val (keepHeld, dropHeld) = held.partition(::kept)
            RelayPlan(generation, state, selected, keepHeld + keepFresh.map(::pointer),
                dropHeld + dropFresh.map(::pointer), emptyList(), keepFresh, full = false)
        }
    }

    private fun finishRelay(plan: RelayPlan, loaded: List<SyncOperationEntity>): Boolean {
        if (plan.generation != generation) return false
        val eventsBefore = snapshot()?.takeIf { it.vaultId == plan.selected.vaultId }?.membershipEvents?.size
        // A full scan sees every change, so its floor starts fresh; an incremental one only rises.
        val selected = plan.selected.copy(relayFloor = RelayRetention.raiseFloor(
            if (plan.full) emptyMap() else plan.selected.relayFloor,
            plan.drop.map { RelayRetention.Held(it.deviceId, it.sequence, it.hash) }))
        outgoing.mkdirs(); incoming.mkdirs()
        val keepHashes = plan.keep.mapTo(hashSetOf()) { it.hash }
        val loadedByHash = loaded.associateBy { it.hash }
        (plan.write + plan.load.mapNotNull { loadedByHash[it] }).forEach { operation ->
            require(operation.hash in keepHashes && operation.vaultId == selected.vaultId)
            val file = File(outgoing, operation.hash)
            if (!file.exists()) {
                writeProtected(file, gson.toJson(operation).toByteArray(Charsets.UTF_8))
                publishChanged = true
            }
        }
        if (eventsBefore != plan.selected.membershipEvents.size) publishChanged = true
        // A full scan also removes stray files that no held change explains. [relayLock] keeps
        // [applyQueued] from moving a file in meanwhile.
        val dropHashes = plan.drop.mapTo(hashSetOf()) { it.hash }
        outgoing.listFiles().orEmpty().filter { it.name.matches(HASH) && it.name !in keepHashes &&
            (plan.full || it.name in dropHashes) }
            .forEach { check(it.delete()) { "Could not prune acknowledged sync change" } }
        writeProtected(File(directory, "peers"), gson.toJson(selected).toByteArray(Charsets.UTF_8))
        photoBlobs.pruneAbandonedPartials(selected.pendingPhotoHashes)
        val activePeers = selected.members.filter {
            it.status == MemberStatus.ACTIVE.name && it.deviceId != selected.localDeviceId
        }
        // A removed device's history is no longer relayed, so a peer may never reach its head.
        val authors = relayableAuthors(selected.members)
        val allApplied = activePeers.isNotEmpty() && selected.heads.filter { it.deviceId in authors }.all { head ->
            activePeers.all { peer ->
                (selected.peerProgress[peer.deviceId]?.applied?.get(head.deviceId)?.sequence ?: 0L) >= head.sequence
            }
        }
        // Blobs no attachment manifest references (deleted or replaced photos) go once every peer
        // applied everything; the short age only protects a blob whose manifest is being written.
        if (allApplied && !plan.state.hasConflicts && selected.pendingPhotoHashes.isEmpty() &&
            incoming.listFiles().isNullOrEmpty() && rejected.listFiles().isNullOrEmpty())
            photoBlobs.pruneCompleted(plan.state.photoHashes, System.currentTimeMillis() - 24L * 60 * 60 * 1000)
        // A retained change whose row went meanwhile (compacted) has no file and is left out.
        outgoingIndex = plan.keep.filter { File(outgoing, it.hash).exists() }
            .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
        writeRelayIndex()
        return true
    }

    @Synchronized override fun snapshot(): TransportMirror? {
        val file = File(directory, "peers")
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = readProtected(file)
        return try {
            val mirror = gson.fromJson(bytes.toString(Charsets.UTF_8), TransportMirror::class.java)
            @Suppress("USELESS_ELVIS")
            mirror.copy(peerProgress = mirror.peerProgress ?: emptyMap(),
                peerAddresses = mirror.peerAddresses ?: emptyMap(),
                lastContactAt = mirror.lastContactAt ?: emptyMap(),
                lastExchangeAt = mirror.lastExchangeAt ?: emptyMap(),
                pendingPhotoHashes = mirror.pendingPhotoHashes ?: emptySet(),
                epochRetiredAt = mirror.epochRetiredAt ?: emptyMap(),
                clientOnlyPeers = mirror.clientOnlyPeers ?: emptySet(),
                peerFeatures = mirror.peerFeatures ?: emptyMap(),
                attachmentHashes = mirror.attachmentHashes ?: emptySet(),
                relayFloor = mirror.relayFloor ?: emptyMap(),
                transportSecrets = mirror.transportSecrets ?: emptyMap(),
                keyStatements = mirror.keyStatements ?: emptyMap(),
                keyPackages = mirror.keyPackages ?: emptyMap())
        }
        finally { bytes.fill(0) }
    }

    @Synchronized override fun acceptMembershipEvents(events: List<SyncMembershipEventEntity>) {
        val accepted = mirrorWithMembership(requireNotNull(snapshot()), events) ?: return
        writeProtected(File(directory, "peers"), gson.toJson(accepted).toByteArray(Charsets.UTF_8))
    }

    /**
     * Managing device: answers a signed leave request found on Google Drive, as [LanSyncExchange]
     * answers one over Wi-Fi. True when it appended new signed events (the REMOVE).
     */
    @Synchronized override fun answerDriveLeave(request: LeaveRequest, signature: ByteArray): Boolean {
        val mirror = snapshot() ?: return false
        val decision = LeaveRequestHandler.handleFromDrive(request, signature, mirror.membershipEvents.map { it.toEvent() },
            setOf(mirror.keyEpoch) + retainedEpochs(), mirror.localDeviceId, System.currentTimeMillis(),
            ::signIdentity, ::removalCutoff)
        if (decision !is LeaveDecision.Left || decision.append.isEmpty()) return false
        appendMembershipEvents(decision.append.map { SyncMembershipEventEntity.from(it) })
        return true
    }

    /** Signs nothing itself: [events] are already signed by this device as the manager. */
    @Synchronized fun appendMembershipEvents(events: List<SyncMembershipEventEntity>) {
        if (events.isEmpty()) return
        acceptMembershipEvents(requireNotNull(snapshot()).membershipEvents + events)
    }

    private fun retire(retiredAt: Map<String, Long>, from: Long, to: Long): Map<String, Long> {
        if (to <= from) return retiredAt
        val now = System.currentTimeMillis()
        return retiredAt + (from until to).associate { it.toString() to now }
    }

    /** The secret for [epoch] of the current group (epoch 1 is the stored group secret; rotated ones are random). */
    @Synchronized fun transportSecret(epoch: Long): String = requireNotNull(snapshot()).epochSecret(epoch)

    /** This device's key statement for the current group, made once and kept in the mirror. */
    @Synchronized override fun ownKeyStatement(): KeyStatement {
        val mirror = requireNotNull(snapshot())
        mirror.keyStatements[mirror.localDeviceId]?.takeIf { it.vaultId == mirror.vaultId }?.let { return it }
        val statement = KeyStatement.of(AndroidDeviceIdentityStore(context), mirror.vaultId)
        require(statement.deviceId == mirror.localDeviceId) { "Device identity changed" }
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            keyStatements = mirror.keyStatements + (mirror.localDeviceId to statement))).toByteArray(Charsets.UTF_8))
        return statement
    }

    /** Keeps a member's statement when its signed identity key signed it for this group. */
    @Synchronized override fun acceptKeyStatement(statement: KeyStatement): Boolean {
        val mirror = snapshot() ?: return false
        val member = mirror.members.firstOrNull { it.deviceId == statement.deviceId && it.status == MemberStatus.ACTIVE.name }
            ?: return false
        if (!statement.valid(mirror.vaultId, member.identityPublicKey) || mirror.keyStatements[statement.deviceId] == statement) return false
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            keyStatements = mirror.keyStatements + (statement.deviceId to statement))).toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * Keeps each package set the manager of its REMOVE signed (never replacing a valid one already
     * kept), and opens this device's package: its transport secret goes into the mirror (Wi-Fi and
     * Drive work again, even while locked). The package key is dropped here: the content key needs it
     * and K(1) ([EpochKeys.contentKey]), so only [installEpochKeys] makes it, once the vault is open.
     * True when a new secret arrived.
     */
    @Synchronized override fun acceptKeyPackages(sets: List<PackageSet>): Boolean {
        val mirror = snapshot() ?: return false
        val verified = runCatching { SyncMembershipManager.verify(mirror.membershipEvents.map { it.toEvent() }) }.getOrNull()
            ?: return false
        var packages = mirror.keyPackages
        var secrets = mirror.transportSecrets
        for (incoming in sets.take(EpochKeyRotation.MAX_SETS)) {
            if (!EpochKeyRotation.validSet(incoming, mirror.vaultId, verified)) continue
            val kept = packages[incoming.epoch.toString()]?.takeIf { EpochKeyRotation.validSet(it, mirror.vaultId, verified) }
            val set = kept ?: incoming
            if (kept == null) packages = packages + (set.epoch.toString() to set)
            if (secrets.containsKey(set.epoch.toString())) continue
            val sealed = set.packages[mirror.localDeviceId] ?: continue
            val private = AndroidDeviceIdentityStore(context).x25519Private()
            val opened = try { EpochKeys.open(mirror.vaultId, set.epoch, mirror.localDeviceId, private,
                Base64.getUrlDecoder().decode(sealed)) } finally { private.fill(0) } ?: continue
            secrets = secrets + (set.epoch.toString() to Base64.getUrlEncoder().withoutPadding().encodeToString(opened.first))
            opened.first.fill(0); opened.second.fill(0)
        }
        if (packages == mirror.keyPackages && secrets == mirror.transportSecrets) return false
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(keyPackages = packages, transportSecrets = secrets))
            .toByteArray(Charsets.UTF_8))
        return secrets != mirror.transportSecrets
    }

    /** Epochs whose content key a queued change needed and this device lacks ([MissingEpochKeyException]). */
    @Volatile private var missingEpochs: Set<Long> = emptySet()

    /**
     * True while a received change waits for the key package of its epoch, or while this device holds
     * back what it sends because its package has not arrived ([TransportMirror.stranded]).
     */
    fun waitingForKeys(): Boolean = missingEpochs.isNotEmpty() || runCatching { snapshot()?.stranded() == true }.getOrDefault(false)

    /** True while this device sends nothing until its new keys arrive ([TransportMirror.stranded]). */
    @Synchronized fun stranded(): Boolean = snapshot()?.stranded() == true

    /** Package sets to relay to a peer (each package is sealed to one survivor). */
    @Synchronized override fun keyPackages(): List<PackageSet> = snapshot()?.keyPackages?.values?.sortedBy { it.epoch }.orEmpty()

    /** Statements known here, for the managing device's rotation gate ([EpochKeyRotation.gate]). */
    @Synchronized fun keyStatements(): Map<String, KeyStatement> = snapshot()?.keyStatements.orEmpty()

    /**
     * Unlocked: stores the content key of each epoch whose package for this device arrived, with its
     * transport secret ([SyncKeyring]), and keeps every valid set in the vault. K(N) is derived from
     * the package key and the vault's K(1) ([EpochKeys.contentKey]), so it exists only here. Call it
     * after the transport store's membership is in the vault ([applyQueued] does): a set is valid only
     * against a history that holds its REMOVE. Changes that waited for it then apply.
     */
    suspend fun installEpochKeys(database: VaultDatabase): Int {
        val mirror = snapshot() ?: return 0
        val vaultId = database.dao().settings()?.vaultId ?: return 0
        if (vaultId != mirror.vaultId) return 0
        val dao = database.syncDao()
        val held = dao.epochKeys().filter { it.vaultId == vaultId }.mapTo(hashSetOf()) { it.epoch }
        val stored = dao.keyPackages(vaultId).mapTo(hashSetOf()) { it.epoch }
        val verified = runCatching { newerMembership(dao.membershipEvents(vaultId), mirror.membershipEvents) }.getOrNull() ?: return 0
        val group = dao.vaultState()?.takeIf { it.vaultId == vaultId } ?: return 0
        var installed = 0
        val first = Base64.getUrlDecoder().decode(group.contentKey)
        try {
            require(first.size == 32) { "Invalid sync key state" }
            for (set in mirror.keyPackages.values.sortedBy { it.epoch }) {
                if (!EpochKeyRotation.validSet(set, vaultId, verified)) continue
                if (set.epoch !in stored) dao.saveKeyPackages(SyncKeyPackageEntity(vaultId, set.epoch, gson.toJson(set)))
                if (set.epoch in held) continue
                val sealed = set.packages[mirror.localDeviceId]?.let(EpochKeys::strictDecode) ?: continue
                val private = AndroidDeviceIdentityStore(context).x25519Private()
                val opened = try { EpochKeys.open(vaultId, set.epoch, mirror.localDeviceId, private, sealed) }
                    finally { private.fill(0) } ?: continue
                val key = EpochKeys.contentKey(first, vaultId, set.epoch, opened.second)
                val encoder = Base64.getUrlEncoder().withoutPadding()
                try {
                    dao.saveEpochKey(SyncEpochKeyEntity(vaultId, set.epoch, encoder.encodeToString(key), encoder.encodeToString(opened.first)))
                } finally { opened.first.fill(0); opened.second.fill(0); key.fill(0) }
                installed++
            }
        } finally { first.fill(0) }
        return installed
    }

    /**
     * The rotated epoch whose HMAC-derived secret (what a survivor without its package uses,
     * [TransportMirror.stranded]) the client proved, or null. Such a client gets the sealed packages.
     */
    @Synchronized fun strandedEpochFor(supplied: ByteArray, challenge: ByteArray): Long? {
        val mirror = snapshot() ?: return null
        val decoder = Base64.getUrlDecoder()
        val root = decoder.decode(mirror.transportSecret)
        val random = mirror.randomSecrets().mapValues { decoder.decode(it.value) }
        try {
            return random.keys.sorted().firstOrNull { epoch ->
                val derived = EpochKeys.transportSecret(root, mirror.vaultId, epoch, random.filterKeys { it < epoch })
                try { MembershipWire.proofMatches(supplied, Base64.getUrlEncoder().withoutPadding().encodeToString(derived), challenge) }
                finally { derived.fill(0) }
            }
        } finally { root.fill(0); random.values.forEach { it.fill(0) } }
    }

    /** Previous epochs whose secrets this server still answers with a removal notice ([TransportEpochs.retained]). */
    @Synchronized fun retainedEpochs(): List<Long> {
        val mirror = snapshot() ?: return emptyList()
        return TransportEpochs.retained(mirror.keyEpoch)
    }

    private fun retiredAt(mirror: TransportMirror): Map<Long, Long> =
        mirror.epochRetiredAt.mapNotNull { (epoch, at) -> epoch.toLongOrNull()?.let { it to at } }.toMap()

    fun signIdentity(bytes: ByteArray): ByteArray = AndroidDeviceIdentityStore(context).sign(bytes)

    override fun signProgress(bytes: ByteArray): ByteArray? = signIdentity(bytes)

    @Synchronized override fun progress(): SyncProgress {
        val mirror = requireNotNull(snapshot())
        val applied = mirror.heads.associate { it.deviceId to SyncChainHead(it.sequence, it.hash) }
        // Applied changes move from the incoming queue to the relay folder before the published
        // heads catch up, so both folders count; the reported frontier never moves backward.
        val relayed = outgoingPointers().filter { it.vaultId == mirror.vaultId }
            .map { ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash) }
        // A set-aside change was verified and stored like any other, so it counts as received, as on
        // Windows (H2): dropping it here made the reported head move backward and the sender refuse
        // every later session. What waits behind it shows on Home ([blockedChanges]).
        val queued = incomingPointers().filter { it.vaultId == mirror.vaultId }.map { it.link } +
            rejectedPointers(mirror.vaultId)
        return SyncProgress(IncomingChain.received(applied, relayed, queued), applied).also { it.validate() }
    }

    @Synchronized fun frontier(): SyncFrontier = SyncFrontier(progress().received.mapValues { it.value.sequence })

    @Synchronized fun peerProgress(deviceId: String): SyncProgress? = snapshot()?.peerProgress?.get(deviceId)

    suspend fun hasPendingMembership(database: VaultDatabase): Boolean {
        val mirror = snapshot() ?: return false
        if (database.dao().settings()?.vaultId != mirror.vaultId) return false
        return mirror.membershipEvents.size > database.syncDao().membershipEvents(mirror.vaultId).size
    }

    @Synchronized fun recordPeerContact(deviceId: String, completed: Boolean) {
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        val now = System.currentTimeMillis()
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            lastContactAt = mirror.lastContactAt + (deviceId to now),
            lastExchangeAt = if (completed) mirror.lastExchangeAt + (deviceId to now)
                else mirror.lastExchangeAt)).toByteArray(Charsets.UTF_8))
    }

    /**
     * A peer's progress report on the drive was written at [at]. The peer writes it at the end of a
     * finished drive pass, so it counts as an exchange as well as a contact (Home and Devices read
     * the exchange time). Neither time moves backward.
     */
    @Synchronized override fun recordPeerSeen(deviceId: String, at: Long) {
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        val contact = mirror.lastContactAt[deviceId] ?: 0L
        val exchange = mirror.lastExchangeAt[deviceId] ?: 0L
        if (at <= contact && at <= exchange) return
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            lastContactAt = mirror.lastContactAt + (deviceId to maxOf(contact, at)),
            lastExchangeAt = mirror.lastExchangeAt + (deviceId to maxOf(exchange, at)))).toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun heldOperations(author: String, after: Long, limit: Int): List<SyncOperationEntity> {
        val mirror = requireNotNull(snapshot())
        return outgoingPointers().asSequence()
            .filter { it.vaultId == mirror.vaultId && it.deviceId == author && it.sequence > after }
            .sortedBy { it.sequence }.take(limit).map { pointer ->
                val bytes = readProtected(File(outgoing, pointer.hash))
                try {
                    gson.fromJson(bytes.toString(Charsets.UTF_8), SyncOperationEntity::class.java).also {
                        require(it.vaultId == pointer.vaultId && it.deviceId == pointer.deviceId &&
                            it.sequence == pointer.sequence && it.hash == pointer.hash) { "Outgoing index changed" }
                    }
                } finally { bytes.fill(0) }
            }.toList()
    }

    // Cloud sync settings and state. They survive [clear]: the Drive connection is per device, and
    // the pass state names the group it belongs to (see [CloudSyncState.forGroup]).

    @Synchronized override fun cloudState(): CloudSyncState {
        if (!cloudStateFile.exists() && !File(cloudStateFile.path + ".bak").exists()) return CloudSyncState()
        return runCatching {
            val bytes = readProtected(cloudStateFile)
            try { gson.fromJson(bytes.toString(Charsets.UTF_8), CloudSyncState::class.java).normalized() }
            finally { bytes.fill(0) }
        }.getOrDefault(CloudSyncState())
    }

    @Synchronized override fun updateCloudState(transform: (CloudSyncState) -> CloudSyncState) {
        writeProtected(cloudStateFile, gson.toJson(transform(cloudState()).normalized()).toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun cloudFiles(): List<CloudFile> {
        if (!cloudFilesFile.exists() && !File(cloudFilesFile.path + ".bak").exists()) return emptyList()
        return runCatching {
            val bytes = readProtected(cloudFilesFile, CLOUD_FILES_LIMIT)
            try {
                @Suppress("SENSELESS_COMPARISON")
                gson.fromJson(bytes.toString(Charsets.UTF_8), Array<CloudFile>::class.java).filter {
                    it != null && it.id != null && it.name != null && it.modified != null
                }
            } finally { bytes.fill(0) }
        }.getOrDefault(emptyList())
    }

    /** A list too large to keep is dropped; the next pass then lists the whole folder again. */
    @Synchronized override fun saveCloudFiles(files: List<CloudFile>) {
        val bytes = gson.toJson(files).toByteArray(Charsets.UTF_8)
        if (bytes.size <= CLOUD_FILES_LIMIT) writeProtected(cloudFilesFile, bytes, CLOUD_FILES_LIMIT)
        else { bytes.fill(0); forgetCloudFiles() }
    }

    /** Signs out locally: the connection, the cached file list and the pass state go. */
    @Synchronized fun forgetCloud() {
        AtomicFile(cloudStateFile).delete()
        forgetCloudFiles()
    }

    private fun forgetCloudFiles() { AtomicFile(cloudFilesFile).delete() }

    /** Platform metadata for enrollment limits. Every platform can accept sync connections. */
    @Synchronized fun recordPeerPlatform(deviceId: String, platform: String) {
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        val clientOnly = platform == "windows"
        if ((deviceId in mirror.clientOnlyPeers) == clientOnly) return
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(clientOnlyPeers =
            if (clientOnly) mirror.clientOnlyPeers + deviceId else mirror.clientOnlyPeers - deviceId))
            .toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun recordPeerFeatures(deviceId: String, features: Set<String>) {
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        val list = features.sorted().take(32)
        if (mirror.peerFeatures[deviceId] != list) writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            peerFeatures = mirror.peerFeatures + (deviceId to list))).toByteArray(Charsets.UTF_8))
        // The appearance gate may have opened; the unlocked app writes a pending appearance.
        AppearanceSync.requestFlush()
    }

    /**
     * True when this device may write an appearance change: every other active member announced
     * [SyncFeatures.APPEARANCE] in its last Wi-Fi handshake (see [AppearanceSyncRules.gateOpen]).
     */
    @Synchronized fun appearanceSyncOpen(): Boolean {
        val mirror = snapshot() ?: return false
        return AppearanceSyncRules.gateOpen(mirror.localDeviceId, mirror.members, mirror.peerFeatures)
    }

    /** The same gate for sign-in method changes ([SignInMethodSyncRules.gateOpen]). */
    @Synchronized fun signInMethodSyncOpen(): Boolean {
        val mirror = snapshot() ?: return false
        return SignInMethodSyncRules.gateOpen(mirror.localDeviceId, mirror.members, mirror.peerFeatures)
    }

    /**
     * The cutoff to write into a REMOVE of [subjectDeviceId]: this device's received head of its
     * changes, when every other active device announced [RemovalCutoff.FEATURE]. Otherwise ""
     * (the earlier behavior), because older builds reject a REMOVE that carries one.
     */
    @Synchronized fun removalCutoff(subjectDeviceId: String): String {
        val mirror = snapshot() ?: return ""
        val others = mirror.members.filter {
            it.status == MemberStatus.ACTIVE.name && it.deviceId != mirror.localDeviceId && it.deviceId != subjectDeviceId
        }
        if (others.any { RemovalCutoff.FEATURE !in mirror.peerFeatures[it.deviceId].orEmpty() }) return ""
        val head = runCatching { progress().received[subjectDeviceId] }.getOrNull() ?: return ""
        return if (head.sequence >= 1) RemovalCutoff.encode(head) else ""
    }

    @Synchronized fun recordPeerAddress(deviceId: String, address: String) {
        val numeric = InetAddresses.parseNumericAddress(address)
        require(isPrivateAddress(numeric) && !numeric.isLinkLocalAddress)
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        if (mirror.peerAddresses[deviceId] == address) return
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            peerAddresses = mirror.peerAddresses + (deviceId to address))).toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun requestPhoto(hash: String) {
        require(hash.matches(HASH))
        val mirror = requireNotNull(snapshot())
        if (hash in mirror.pendingPhotoHashes) return
        require(mirror.pendingPhotoHashes.size < 1024)
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            pendingPhotoHashes = mirror.pendingPhotoHashes + hash)).toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun receivedPhoto(hash: String) {
        val mirror = requireNotNull(snapshot())
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            pendingPhotoHashes = mirror.pendingPhotoHashes - hash)).toByteArray(Charsets.UTF_8))
    }

    /** Stops asking peers for an image that a later change made unnecessary. */
    @Synchronized fun forgetPhoto(hash: String) {
        require(hash.matches(HASH))
        val mirror = requireNotNull(snapshot())
        if (hash in mirror.pendingPhotoHashes) writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            pendingPhotoHashes = mirror.pendingPhotoHashes - hash)).toByteArray(Charsets.UTF_8))
        if (photoBlobs.file(hash) == null) photoBlobs.discardPartial(hash)
    }

    /**
     * M13: up to 32 images a session may ask for. Those a peer recently said it lacks go last, so the
     * rest are asked for instead of the same 32 every time.
     */
    @Synchronized override fun missingPhotos(): List<Pair<String, Long>> = PhotoRequestOrder.pick(
        requireNotNull(snapshot()).pendingPhotoHashes.filter { photoBlobs.file(it) == null }, photoLackedAt, 32)
        .map { it to photoBlobs.partialSize(it) }

    @Synchronized override fun photoUnavailable(hash: String) {
        photoLackedAt[hash] = System.currentTimeMillis()
        if (photoLackedAt.size > 2048) photoLackedAt.keys.retainAll(requireNotNull(snapshot()).pendingPhotoHashes)
    }

    /** When a peer last said it lacks an image (this process only). */
    private val photoLackedAt = HashMap<String, Long>()

    @Synchronized override fun recordPeerProgress(deviceId: String, progress: SyncProgress) =
        recordPeer(deviceId, progress, live = false)

    @Synchronized override fun recordLivePeerProgress(deviceId: String, progress: SyncProgress) =
        recordPeer(deviceId, progress, live = true)

    private fun recordPeer(deviceId: String, progress: SyncProgress, live: Boolean) {
        progress.validate()
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        val known = outgoingPointers()
        val receivedLocally = progress().received
        val queuedLocally = incomingPointers()
        (progress.received.entries + progress.applied.entries).forEach { (author, head) ->
            val latest = mirror.heads.firstOrNull { it.deviceId == author }
            if (author == mirror.localDeviceId) require(head.sequence <= (receivedLocally[author]?.sequence ?: 0L)) {
                "Peer reported an unknown change head"
            }
            val localHash = known.firstOrNull { it.deviceId == author && it.sequence == head.sequence }?.hash
                ?: queuedLocally.firstOrNull { it.link.deviceId == author && it.link.sequence == head.sequence }?.link?.hash
                ?: latest?.takeIf { it.sequence == head.sequence }?.hash
            require(localHash == null || localHash == head.hash) { "Peer reported a conflicting change head" }
        }
        val previous = mirror.peerProgress[deviceId]
        if (previous != null) {
            var lostGround = false
            fun check(heads: Map<String, SyncChainHead>, old: Map<String, SyncChainHead>, what: String) = heads.forEach { (author, head) ->
                val before = old[author] ?: return@forEach
                // The same sequence with another change is a fork: always refused.
                require(head.sequence != before.sequence || head.hash == before.hash) { "Peer reported a conflicting change head" }
                if (head.sequence < before.sequence) {
                    // A Drive report may be an old copy; a live peer that lost ground is told the truth.
                    require(live) { "Peer $what progress moved backward" }
                    lostGround = true
                }
            }
            check(progress.received, previous.received, "received")
            check(progress.applied, previous.applied, "applied")
            if (lostGround) runCatching { android.util.Log.w("NuvoriSync", "A peer reported less than before; its changes go again") }
        }
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            peerProgress = mirror.peerProgress + (deviceId to progress))).toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun pending(frontier: SyncFrontier, relayRemoved: Boolean): List<SyncOperationEntity> {
        frontier.validate()
        val mirror = requireNotNull(snapshot())
        // Waiting for new keys: nothing leaves this device until they arrive ([TransportMirror.stranded]).
        if (mirror.stranded()) return emptyList()
        val vaultId = mirror.vaultId
        // A receiver rejects a change by a device that is not an active member, and that ends the
        // session. Changes of a removed device go only to a peer that accepts them up to the cutoff.
        // Single changes are never left out by type (for example an appearance change for a peer
        // without SyncFeatures.APPEARANCE): each author's changes form a hash chain, so a gap would
        // stop every later change of that author on the peer just as a rejected change does. The
        // writer's gate (AppearanceSyncRules.gateOpen) keeps such changes out of the chain instead.
        // Each author's run stops at the first missing change ([RelayRetention.gapFree]).
        val authors = relayableAuthors(mirror.members)
        val cutoffs = if (relayRemoved) RemovalCutoff.cutoffsOf(mirror.membershipEvents) else emptyMap()
        val pointers = outgoingPointers().filter { it.vaultId == vaultId }
        val held = pointers.mapTo(hashSetOf()) { it.hash }
        // M1: changes stored here but not applied yet go on too, so a locked device passes on what it
        // received. Each passed the signature, membership and chain checks when it was stored ([queue]).
        val queued = incomingPointers().filter { it.vaultId == vaultId && it.link.hash !in held }
        val byHash = pointers.associate { it.hash to Triple(outgoing, it.deviceId, it.sequence) } +
            queued.associate { it.link.hash to Triple(incoming, it.link.deviceId, it.link.sequence) }
        // gapFree reads each author's run in sequence order.
        val links = (pointers.map { ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash) } + queued.map { it.link })
            .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
        return RelayRetention.gapFree(links, frontier.counters, { it.deviceId in authors || RemovalCutoff.allows(cutoffs[it.deviceId], it.sequence, it.hash) },
            100).map { link ->
            val (folder, deviceId, sequence) = byHash.getValue(link.hash)
            val bytes = readProtected(File(folder, link.hash))
            try {
                gson.fromJson(bytes.toString(Charsets.UTF_8), SyncOperationEntity::class.java).also {
                    require(it.vaultId == vaultId && it.deviceId == deviceId &&
                        it.sequence == sequence && it.hash == link.hash) { "Relay index changed" }
                }
            } finally { bytes.fill(0) }
        }.toList()
    }

    @Synchronized override fun queue(operation: SyncOperationEntity) {
        val mirror = requireNotNull(snapshot())
        verifyIncomingOperation(mirror, operation)
        val change = operation.toSyncChange()
        val file = File(incoming, change.hash)
        // A resend of something already held (or set aside) is acknowledged again without a second copy.
        if (file.exists() || File(outgoing, change.hash).exists() || File(rejected, change.hash).exists()) return
        val link = ChainLink(change.deviceId, change.sequence, change.hash, change.previousHash)
        // An older change resent (its relay file may be gone) is acknowledged, never stored again.
        if (IncomingChain.admit(link, progress().received, emptySet()) != IncomingChain.Admission.STORE) return
        val bytes = gson.toJson(operation).toByteArray(Charsets.UTF_8)
        try {
            val files = incoming.listFiles().orEmpty().filter { it.isFile }
            if (files.size >= 2048 || files.sumOf { it.length() } + bytes.size + 28 > 32L * 1024 * 1024)
                throw SyncQueueFullException()
            incoming.mkdirs()
            val pointers = incomingPointers()
            writeProtected(file, bytes)
            incomingIndex = pointers + QueuedPointer(operation.vaultId, link, meta(operation))
        } finally { bytes.fill(0) }
        mutableQueueEvents.value = mutableQueueEvents.value + 1
    }

    /**
     * Rebuilds the relay folder from the vault when a Drive pass asked for it, or Drive was just
     * connected ([CloudSyncState.relayRefill]), so the next pass can upload what the drive lacks (C1).
     * The flag goes first: a pass that raises it again meanwhile is not lost. True when it ran.
     */
    suspend fun refillRelayIfNeeded(database: VaultDatabase): Boolean {
        if (!cloudState().normalized().relayRefill) return false
        updateCloudState { it.copy(relayRefill = false) }
        try { publish(database, full = true) }
        catch (failure: Exception) { updateCloudState { it.copy(relayRefill = true) }; throw failure }
        return true
    }

    /**
     * Per author, the stored changes that wait for an app update: the next change to apply is of a
     * kind this version doesn't know ([UnsupportedSyncEntityException]).
     */
    @Synchronized fun waitingForUpdate(): Map<String, Long> {
        val mirror = snapshot() ?: return emptyMap()
        val progress = progress()
        val queued = incomingPointers().filter { it.vaultId == mirror.vaultId }
        return queued.filter { pointer ->
            pointer.meta.entityType !in SUPPORTED_ENTITY_TYPES &&
                pointer.link.sequence == (progress.applied[pointer.link.deviceId]?.sequence ?: 0L) + 1
        }.associate { pointer ->
            pointer.link.deviceId to (progress.received[pointer.link.deviceId]?.sequence ?: 0L) - pointer.link.sequence + 1
        }
    }

    /** Per author, the stored changes that wait behind one set aside here: they apply after a retry. */
    @Synchronized fun blockedBehindSetAside(): Map<String, Long> {
        val mirror = snapshot() ?: return emptyMap()
        val progress = progress()
        return rejectedPointers(mirror.vaultId).map { it.deviceId }.distinct().associateWith { author ->
            (progress.received[author]?.sequence ?: 0L) - (progress.applied[author]?.sequence ?: 0L)
        }.filterValues { it > 0 }
    }

    /**
     * Applies the stored changes that can apply now. One run at a time in this process: the main app
     * and autofill both apply ([APPLY_LOCK]), and two runs could apply the same change twice.
     */
    suspend fun applyQueued(database: VaultDatabase, localKey: ByteArray): Int =
        APPLY_LOCK.withLock { applyQueuedLocked(database, localKey) }

    private suspend fun applyQueuedLocked(database: VaultDatabase, localKey: ByteArray): Int {
        missingEpochs = emptySet()
        val mirror = requireNotNull(snapshot())
        database.withTransaction {
            val dao = database.syncDao()
            val current = dao.membershipEvents(mirror.vaultId)
            val accepted = newerMembership(current, mirror.membershipEvents)
            mirror.membershipEvents.drop(current.size).forEach { dao.insertMembershipEvent(it) }
            accepted.members.forEach { member ->
                val label = dao.membership(mirror.vaultId, member.deviceId)?.displayName
                dao.upsertMembership(member.copy(displayName = label ?: member.displayName))
            }
        }
        // The managing device gives REMOVEs answered while locked (leave requests) their keys or a
        // marker. Then the keys that arrived while locked: their sets are valid only against the
        // history just stored, so this comes after it. Changes of their epoch can then apply.
        runCatching { EpochKeyRotation.coverRemovals(database, AndroidDeviceIdentityStore(context), keyStatements()) }
        runCatching { installEpochKeys(database) }
        val applier = IncomingEntryChangeApplier(database, photoBlobs, context)
        var applied = 0
        var progress: Boolean
        do {
            progress = false
            val operations = synchronized(this) { readOperations(incoming).sortedWith(compareBy({ it.deviceId }, { it.sequence })) }
            for (operation in operations) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val appliedHead = database.syncDao().deviceHead(operation.vaultId, operation.deviceId)
                if (operation.sequence > (appliedHead?.sequence ?: 0L) + 1L) continue
                // A group or linked authenticator from another device may need to be applied first.
                val result = applyOne(applier, operation, localKey) ?: continue
                relayLock.withLock { synchronized(this) {
                    val queued = File(incoming, operation.hash)
                    if (queued.exists()) {
                        outgoing.mkdirs()
                        writeProtected(File(outgoing, operation.hash), gson.toJson(operation).toByteArray(Charsets.UTF_8))
                        outgoingIndex = outgoingIndex?.let { index ->
                            (index.filterNot { it.hash == operation.hash } + OperationPointer(operation.vaultId,
                                operation.deviceId, operation.sequence, operation.hash, operation.previousHash))
                                .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
                        }
                        check(queued.delete()) { "Could not acknowledge queued change" }
                        incomingIndex = incomingIndex?.filterNot { it.link.hash == operation.hash }
                    }
                } }
                if (result != IncomingResult.REPLAY) applied++
                progress = true
            }
        } while (progress)
        // M2: concurrent edits of different fields of one item merge into one signed change, sent with
        // the publish below. A conflict that can't merge stays for the user. Never stops the pass.
        if (database.syncDao().conflicts().any { it.resolvedAtUtc.isEmpty() && it.entityType in EntryFieldMerge.ENTITY_TYPES })
            runCatching { SyncConflictResolver(database, AndroidDeviceIdentityStore(context), photoBlobs, context).autoMerge(localKey) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        // M13: every queued photo's image is asked for now, not only the first one each device's chain
        // stops at, so N photos arrive in one session instead of N.
        val queuedPhotos = synchronized(this) { readOperations(incoming) }.filter { it.entityType == "photo" && it.kind == "upsert" }
        for (operation in queuedPhotos) {
            val hash = runCatching { applier.photoBlobHash(operation, localKey) }.getOrNull() ?: continue
            if (photoBlobs.file(hash) == null) runCatching { requestPhoto(hash) }
        }
        publish(database)
        return applied
    }

    /**
     * Before a forced leave: keeps what [applyQueued] could not apply (the incoming and set-aside
     * changes) in Recently deleted, while the old group's content key still exists. See
     * [RemovalArchive]. Only changes that still pass the signed membership check are read. Throws
     * when the vault can't be written; the caller then must not leave yet.
     */
    suspend fun archiveUnapplied(database: VaultDatabase, localKey: ByteArray,
        now: Long = System.currentTimeMillis()): RemovalArchive.Summary {
        val mirror = snapshot() ?: return RemovalArchive.Summary(0, 0)
        val operations = synchronized(this) { readOperations(incoming) + readOperations(rejected) }
            .filter { it.vaultId == mirror.vaultId && runCatching { verifyIncomingOperation(mirror, it) }.isSuccess }
            .distinctBy { it.hash }
        if (operations.isEmpty()) return RemovalArchive.Summary(0, 0)
        val contentKey = database.syncKeyring(localKey)
        val candidates = try {
            operations.filter { it.entityType == "entry" && it.kind == "upsert" }.mapNotNull { operation ->
                runCatching {
                    val payload = IncomingEntryChangeApplier.decryptBytes(operation.toSyncChange(), contentKey)
                    val json = try { org.json.JSONObject(payload.toString(Charsets.UTF_8)) } finally { payload.fill(0) }
                    val groups = json.optJSONArray("groupIds")
                    RemovalArchive.Candidate(operation,
                        SyncEntryCodec.parseEntry(json.getJSONObject("entry").toString(), operation.entityId),
                        if (groups == null) emptyList() else (0 until groups.length()).map(groups::getString))
                }.getOrNull()
            }
        } finally { contentKey.wipe() }
        val local = candidates.mapNotNull { database.dao().entryRow(it.operation.entityId) }.associateBy { it.id }
        val kept = RemovalArchive.pick(candidates, local)
        database.withTransaction {
            kept.forEach { candidate ->
                val row = RemovalArchive.trashRow(candidate, now)
                if (database.dao().trashed(row.id) == null) database.dao().insertTrash(row)
            }
        }
        return RemovalArchive.Summary(kept.size, RemovalArchive.otherChanges(operations))
    }

    /** Returns null when the change must wait or was set aside; the loop then moves on. */
    private suspend fun applyOne(applier: IncomingEntryChangeApplier, operation: SyncOperationEntity,
        localKey: ByteArray, skipPhotoBlob: Boolean = false): IncomingResult? = try {
        applier.apply(operation, localKey, skipPhotoBlob)
    }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (missing: MissingPhotoBlobException) {
        val queued = synchronized(this) { incomingPointers().map { it.meta } }
        if (!skipPhotoBlob && missing.photoId.isNotBlank() &&
            PhotoSupersession.isSuperseded(meta(operation), missing.photoId, missing.entryId, queued)) {
            forgetPhoto(missing.hash)
            applyOne(applier, operation, localKey, skipPhotoBlob = true)
        } else { requestPhoto(missing.hash); null }
    }
    catch (_: MissingPhotoDependencyException) { null }
    // A newer kind of change waits for an app update; it is counted as received meanwhile (M4).
    catch (_: UnsupportedSyncEntityException) { null }
    catch (_: MissingRecordDependencyException) { null }
    // A change of an epoch whose key has not arrived yet waits for its package ([EpochKeys]), never set aside.
    catch (missing: MissingEpochKeyException) { missingEpochs = missingEpochs + missing.epoch; null }
    catch (_: SQLiteConstraintException) { quarantine(operation.hash); null }
    catch (_: IllegalArgumentException) { quarantine(operation.hash); null }
    catch (_: JSONException) { quarantine(operation.hash); null }
    catch (_: javax.crypto.AEADBadTagException) { quarantine(operation.hash); null }

    @Synchronized fun queuedCount(): Int = incoming.listFiles().orEmpty().count { it.name.matches(HASH) }

    @Synchronized fun rejectedCount(): Int = rejected.listFiles().orEmpty().count {
        it.name.matches(HASH)
    }

    @Synchronized fun retryRejected() {
        rejected.listFiles().orEmpty().filter { it.name.matches(HASH) }
            .forEach { file ->
                val target = File(incoming, file.name)
                if (target.exists()) check(file.delete())
                else check(file.renameTo(target)) { "Could not retry rejected sync change" }
            }
        incomingIndex = null
        rejectedIndex = null
    }

    @Synchronized private fun quarantine(hash: String) {
        val file = File(incoming, hash)
        if (!file.exists()) return
        rejected.mkdirs()
        check(file.renameTo(File(rejected, hash))) { "Could not isolate rejected sync change" }
        incomingIndex = incomingIndex?.filterNot { it.link.hash == hash }
        rejectedIndex = null
    }

    /** Clears the group's transport state. Leave and removal notices survive on purpose. */
    @Synchronized fun clear() {
        listOf(outgoing, incoming, rejected).forEach { folder -> folder.listFiles().orEmpty().forEach { check(it.delete()) } }
        File(directory, "blobs").listFiles().orEmpty().forEach { check(it.delete()) }
        File(directory, "peers").let { if (it.exists()) check(it.delete()) }
        listOf("peers.bak", "peers.new").forEach { name ->
            File(directory, name).let { if (it.exists()) check(it.delete()) }
        }
        AtomicFile(relayIndexFile).delete()
        outgoingIndex = null
        incomingIndex = null
        rejectedIndex = null
        generation++
        fullScanFor = null
    }

    // Leave and removal notices.

    @Synchronized fun savePendingLeave(notice: PendingLeaveNotice) {
        notice.request().validate()
        writeProtected(leaveNoticeFile, gson.toJson(notice).toByteArray(Charsets.UTF_8))
    }

    /** The pending leave notice, dropped once it is older than the 30-day retry window. */
    @Synchronized fun pendingLeave(now: Long = System.currentTimeMillis()): PendingLeaveNotice? {
        if (!leaveNoticeFile.exists() && !File(leaveNoticeFile.path + ".bak").exists()) return null
        val notice = runCatching {
            val bytes = readProtected(leaveNoticeFile)
            try { gson.fromJson(bytes.toString(Charsets.UTF_8), PendingLeaveNotice::class.java) } finally { bytes.fill(0) }
        }.getOrNull()
        if (notice == null || now - notice.createdAt > LeaveRequestHandler.MAX_AGE_MILLIS) {
            clearPendingLeave()
            return null
        }
        @Suppress("USELESS_ELVIS")
        return notice.copy(addresses = notice.addresses ?: emptyList(), driveName = notice.driveName ?: "",
            driveFile = notice.driveFile ?: "")
    }

    @Synchronized fun recordLeaveAttempt(now: Long = System.currentTimeMillis()) {
        pendingLeave(now)?.let { savePendingLeave(it.copy(lastAttemptAt = now)) }
    }

    @Synchronized fun clearPendingLeave() { AtomicFile(leaveNoticeFile).delete() }

    /**
     * After this device joined a group as [joinedDeviceId]: drops a pending leave request that this
     * same identity signed, so the manager can't remove it again when the request arrives. A request
     * of an earlier identity stays (see [MembershipChoices.dropLeaveAfterJoin]). True when it dropped one.
     */
    @Synchronized fun clearPendingLeaveOf(joinedDeviceId: String): Boolean {
        val notice = pendingLeave() ?: return false
        if (!MembershipChoices.dropLeaveAfterJoin(notice.deviceId, joinedDeviceId)) return false
        clearPendingLeave()
        return true
    }

    @Synchronized fun recordRemoval(notice: SyncRemovalNotice) {
        writeProtected(removalNoticeFile, gson.toJson(notice).toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun removalNotice(): SyncRemovalNotice? {
        if (!removalNoticeFile.exists() && !File(removalNoticeFile.path + ".bak").exists()) return null
        return runCatching {
            val bytes = readProtected(removalNoticeFile)
            try { gson.fromJson(bytes.toString(Charsets.UTF_8), SyncRemovalNotice::class.java) } finally { bytes.fill(0) }
        }.getOrNull()
    }

    @Synchronized fun dismissRemovalNotice() { AtomicFile(removalNoticeFile).delete() }

    /**
     * True when this device's own signed history shows a REMOVE of this device for [vaultId], so the
     * local leave runs only on a verified chain, never on the notice file alone.
     */
    @Synchronized fun verifiedRemovalOf(vaultId: String): Boolean {
        val mirror = snapshot() ?: return false
        if (mirror.vaultId != vaultId) return false
        val events = runCatching { mirror.membershipEvents.map { it.toEvent() } }.getOrNull() ?: return false
        if (runCatching { SyncMembershipManager.verify(events) }.isFailure) return false
        return events.any { it.action == MembershipAction.REMOVE && it.subjectDeviceId == mirror.localDeviceId }
    }

    /** The service keeps running to deliver removal notices or to retry a leave request. */
    @Synchronized fun hasMembershipWork(): Boolean {
        if (pendingLeave() != null) return true
        val mirror = snapshot() ?: return false
        val localActive = mirror.members.any { it.deviceId == mirror.localDeviceId && it.status == MemberStatus.ACTIVE.name }
        return localActive && mirror.members.any { it.status == MemberStatus.REVOKED.name } &&
            TransportEpochs.keepServiceFor(mirror.keyEpoch, retiredAt(mirror), System.currentTimeMillis()).isNotEmpty()
    }

    /**
     * The relay folder's chain coordinates. A cold start reads the sealed index file (one file)
     * instead of decrypting every relay file. The index is rebuilt from the files when it is
     * missing or names other files than the folder holds (for example after a crash).
     */
    private fun outgoingPointers(): List<OperationPointer> {
        outgoingIndex?.let { return it }
        val names = outgoing.listFiles().orEmpty().filter { it.name.matches(HASH) }.mapTo(hashSetOf()) { it.name }
        val stored = readRelayIndex()?.takeIf { index -> index.size == names.size && index.all { it.hash in names } }
        if (stored != null) return stored.sortedWith(compareBy({ it.deviceId }, { it.sequence })).also { outgoingIndex = it }
        return readOperations(outgoing)
            .map { OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash, it.previousHash) }
            .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
            .also { outgoingIndex = it; writeRelayIndex() }
    }

    private fun readRelayIndex(): List<OperationPointer>? {
        if (!relayIndexFile.exists() && !File(relayIndexFile.path + ".bak").exists()) return null
        return runCatching {
            val bytes = readProtected(relayIndexFile, RELAY_INDEX_LIMIT)
            try {
                @Suppress("SENSELESS_COMPARISON")
                gson.fromJson(bytes.toString(Charsets.UTF_8), Array<OperationPointer>::class.java).toList().also { list ->
                    require(list.all { it != null && it.vaultId != null && it.deviceId != null && it.hash != null &&
                        it.previousHash != null && it.sequence > 0 && it.hash.matches(HASH) })
                }
            } finally { bytes.fill(0) }
        }.getOrNull()
    }

    /** Best effort: without the index, the next cold start reads the relay files. */
    private fun writeRelayIndex() {
        val index = outgoingIndex ?: return
        val bytes = gson.toJson(index).toByteArray(Charsets.UTF_8)
        if (bytes.size <= RELAY_INDEX_LIMIT) runCatching { writeProtected(relayIndexFile, bytes, RELAY_INDEX_LIMIT) }
        else { bytes.fill(0); AtomicFile(relayIndexFile).delete() }
    }

    private fun incomingPointers(): List<QueuedPointer> = incomingIndex ?: readOperations(incoming)
        .map { QueuedPointer(it.vaultId, ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash), meta(it)) }
        .also { incomingIndex = it }

    private fun rejectedPointers(vaultId: String): List<ChainLink> = (rejectedIndex ?: readOperations(rejected)
        .map { it.vaultId to ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash) }
        .also { rejectedIndex = it }).filter { it.first == vaultId }.map { it.second }

    private fun meta(operation: SyncOperationEntity) = QueuedOperationMeta(operation.deviceId, operation.sequence,
        operation.entityType, operation.entityId, operation.kind)

    private fun readOperations(folder: File): List<SyncOperationEntity> = folder.listFiles().orEmpty()
        .filter { it.name.matches(HASH) }.map { file ->
            val bytes = readProtected(file)
            try { gson.fromJson(bytes.toString(Charsets.UTF_8), SyncOperationEntity::class.java) }
            finally { bytes.fill(0) }
        }

    private fun writeProtected(file: File, plaintext: ByteArray, limit: Int = SyncFrames.MAX_FRAME) {
        try {
            require(plaintext.size <= limit)
            file.parentFile!!.mkdirs()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey())
                updateAAD(file.name.toByteArray(Charsets.UTF_8))
            }
            val encrypted = cipher.doFinal(plaintext)
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(cipher.iv); output.write(encrypted); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        } finally { plaintext.fill(0) }
    }

    private fun readProtected(file: File, limit: Int = SyncFrames.MAX_FRAME): ByteArray {
        val bytes = AtomicFile(file).openRead().use { input ->
            require(input.channel.size() in 28..(limit + 28L)) { "Invalid protected sync file" }
            input.readBytes()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes, 0, 12))
            updateAAD(file.name.toByteArray(Charsets.UTF_8))
            doFinal(bytes, 12, bytes.size - 12)
        }
    }

    private fun wrappingKey(): SecretKey {
        cachedWrappingKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        cachedWrappingKey = key
        return key
    }

    companion object {
        private val mutableQueueEvents = kotlinx.coroutines.flow.MutableStateFlow(0L)
        /** H5: ticks when a change arrives in the queue, so an unlocked app applies it at once instead of polling. */
        val queueEvents: kotlinx.coroutines.flow.StateFlow<Long> = mutableQueueEvents
        /** Serializes [applyQueued] across every store instance in the process. */
        private val APPLY_LOCK = Mutex()
        private const val KEY_ALIAS = "nuvori_sync_transport_v1"
        /** The cached Drive file list (about 150 bytes a file); Windows stops listing at 200,000 files. */
        private const val CLOUD_FILES_LIMIT = 32 * 1024 * 1024
        /** The relay folder's index, about 250 bytes a change. */
        private const val RELAY_INDEX_LIMIT = 32 * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
