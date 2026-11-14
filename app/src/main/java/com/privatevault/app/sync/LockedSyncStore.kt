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
    val attachmentHashes: Set<String> = emptySet())

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
    val change = operation.toSyncChange()
    change.validate()
    require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey),
        change.signingBytes(), Base64.getUrlDecoder().decode(change.deviceSignature))) { "Invalid sender signature" }
}

/**
 * [mirror] with the signed history [events] adopted, or null when [events] adds nothing. Throws for
 * an invalid chain, another vault, an older key epoch or a history that conflicts with the stored one.
 */
internal fun mirrorWithMembership(mirror: TransportMirror, events: List<SyncMembershipEventEntity>): TransportMirror? {
    val current = mirror.membershipEvents.map { it.toEvent() }
    val incoming = events.map { it.toEvent() }
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
    return mirror.copy(membershipEvents = events, members = members, keyEpoch = verified.keyEpoch, epochRetiredAt = retiredAt)
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
    override val photoBlobs = PhotoSyncBlobs(File(directory, "blobs"))
    private val gson = Gson()
    private var outgoingIndex: List<OperationPointer>? = null
    /** Chain coordinates of the incoming folder, so each received change is not a full rescan. */
    private var incomingIndex: List<QueuedPointer>? = null
    @Volatile private var cachedWrappingKey: SecretKey? = null

    suspend fun publish(database: VaultDatabase) {
        val identity = AndroidDeviceIdentityStore(context).getOrCreate()
        val (mirror, operations, photoHashes, hasConflicts) = database.withTransaction {
            val vaultId = requireNotNull(database.dao().settings()).vaultId
            val group = requireNotNull(database.syncDao().vaultState()) { "Sync group is not ready" }
            require(group.vaultId == vaultId && Base64.getUrlDecoder().decode(group.transportSecret).size == 32)
            val events = database.syncDao().membershipEvents(vaultId)
            val epoch = events.takeIf { it.isNotEmpty() }?.let { list ->
                SyncMembershipManager.verify(list.map { it.toEvent() }).keyEpoch
            } ?: group.keyEpoch
            val mirror = TransportMirror(vaultId, identity.deviceId, identity.publicKeyBase64Url,
                group.transportSecret, epoch,
                database.syncDao().peers(vaultId), database.syncDao().memberships(vaultId),
                events, database.syncDao().deviceHeads(vaultId))
            PublishedState(mirror, database.syncDao().operations(),
                database.syncDao().attachments().mapTo(hashSetOf()) { it.ciphertextHash },
                database.syncDao().conflicts().any { it.resolvedAtUtc.isEmpty() })
        }
        publishState(mirror.copy(attachmentHashes = photoHashes), operations, photoHashes, hasConflicts)
    }

    private data class PublishedState(val mirror: TransportMirror, val operations: List<SyncOperationEntity>,
        val photoHashes: Set<String>, val hasConflicts: Boolean)

    @Synchronized private fun publishState(mirror: TransportMirror, operations: List<SyncOperationEntity>,
        photoHashes: Set<String>, hasConflicts: Boolean) {
        val previous = snapshot()
        if (previous != null && previous.vaultId != mirror.vaultId) clear()
        val selected = if (previous != null && previous.vaultId == mirror.vaultId) {
            require(previous.transportSecret == mirror.transportSecret)
            val databaseEvents = mirror.membershipEvents.map { it.toEvent() }
            val storedEvents = previous.membershipEvents.map { it.toEvent() }
            SyncMembershipManager.verify(databaseEvents)
            val stored = SyncMembershipManager.verify(storedEvents)
            require(databaseEvents.zip(storedEvents).all { (left, right) -> left.hash == right.hash }) {
                "Conflicting membership history"
            }
            val kept = if (storedEvents.size > databaseEvents.size) mirror.copy(
                membershipEvents = previous.membershipEvents, members = previous.members, keyEpoch = stored.keyEpoch)
                else mirror
            kept.copy(peerProgress = previous.peerProgress, peerAddresses = previous.peerAddresses,
                lastContactAt = previous.lastContactAt, lastExchangeAt = previous.lastExchangeAt,
                pendingPhotoHashes = previous.pendingPhotoHashes, clientOnlyPeers = previous.clientOnlyPeers,
                peerFeatures = previous.peerFeatures,
                epochRetiredAt = retire(previous.epochRetiredAt, previous.keyEpoch, kept.keyEpoch))
        } else mirror
        val activePeers = selected.members.filter {
            it.status == MemberStatus.ACTIVE.name && it.deviceId != selected.localDeviceId
        }
        // Cloud sync needs a gap-free history on the drive, so this device keeps its own changes
        // until the drive holds them, even after every peer applied them over Wi-Fi.
        val cloud = cloudState().normalized()
        val cloudCovered = if (!cloud.enabled) null
            else if (cloud.vaultId == selected.vaultId) cloud.covered[selected.localDeviceId] ?: 0L else 0L
        val retained = operations.filter { operation ->
            operation.deviceId != selected.localDeviceId || activePeers.isEmpty() ||
                (cloudCovered != null && operation.sequence > cloudCovered) || activePeers.any { peer ->
                (selected.peerProgress[peer.deviceId]?.applied?.get(operation.deviceId)?.sequence ?: 0L) < operation.sequence
            }
        }
        outgoing.mkdirs(); incoming.mkdirs()
        val retainedHashes = retained.mapTo(hashSetOf()) { it.hash }
        outgoing.listFiles().orEmpty().filter { it.name.matches(HASH) && it.name !in retainedHashes }
            .forEach { check(it.delete()) { "Could not prune acknowledged sync change" } }
        retained.forEach { operation ->
            val file = File(outgoing, operation.hash)
            if (!file.exists()) writeProtected(file, gson.toJson(operation).toByteArray(Charsets.UTF_8))
        }
        writeProtected(File(directory, "peers"), gson.toJson(selected).toByteArray(Charsets.UTF_8))
        photoBlobs.pruneAbandonedPartials(selected.pendingPhotoHashes)
        // A removed device's history is no longer relayed, so a peer may never reach its head.
        val authors = relayableAuthors(selected.members)
        val allApplied = activePeers.isNotEmpty() && selected.heads.filter { it.deviceId in authors }.all { head ->
            activePeers.all { peer ->
                (selected.peerProgress[peer.deviceId]?.applied?.get(head.deviceId)?.sequence ?: 0L) >= head.sequence
            }
        }
        // Blobs no attachment manifest references (deleted or replaced photos) go once every peer
        // applied everything; the short age only protects a blob whose manifest is being written.
        if (allApplied && !hasConflicts && selected.pendingPhotoHashes.isEmpty() &&
            incoming.listFiles().isNullOrEmpty() && rejected.listFiles().isNullOrEmpty())
            photoBlobs.pruneCompleted(photoHashes, System.currentTimeMillis() - 24L * 60 * 60 * 1000)
        outgoingIndex = retained.map { OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash, it.previousHash) }
            .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
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
                attachmentHashes = mirror.attachmentHashes ?: emptySet())
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

    /** The secret for [epoch] of the current group (epoch 1 is the stored group secret). */
    @Synchronized fun transportSecret(epoch: Long): String {
        val mirror = requireNotNull(snapshot())
        return TransportEpochs.secret(mirror.transportSecret, mirror.vaultId, epoch)
    }

    /** Previous epochs whose secrets this server still answers with a removal notice. */
    @Synchronized fun retainedEpochs(now: Long = System.currentTimeMillis()): List<Long> {
        val mirror = snapshot() ?: return emptyList()
        return TransportEpochs.retained(mirror.keyEpoch,
            mirror.epochRetiredAt.mapNotNull { (epoch, at) -> epoch.toLongOrNull()?.let { it to at } }.toMap(), now)
    }

    fun signIdentity(bytes: ByteArray): ByteArray = AndroidDeviceIdentityStore(context).sign(bytes)

    @Synchronized override fun progress(): SyncProgress {
        val mirror = requireNotNull(snapshot())
        val applied = mirror.heads.associate { it.deviceId to SyncChainHead(it.sequence, it.hash) }
        // Applied changes move from the incoming queue to the relay folder before the published
        // heads catch up, so both folders count; the reported frontier never moves backward.
        val relayed = outgoingPointers().filter { it.vaultId == mirror.vaultId }
            .map { ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash) }
        val queued = incomingPointers().filter { it.vaultId == mirror.vaultId }.map { it.link }
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

    /** A peer's progress report on the drive was written at [at]; contact times never move backward. */
    @Synchronized override fun recordPeerSeen(deviceId: String, at: Long) {
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        if (at <= (mirror.lastContactAt[deviceId] ?: 0L)) return
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            lastContactAt = mirror.lastContactAt + (deviceId to at))).toByteArray(Charsets.UTF_8))
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

    @Synchronized fun recordPeerFeatures(deviceId: String, features: Set<String>) {
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

    @Synchronized override fun missingPhotos(): List<Pair<String, Long>> = requireNotNull(snapshot())
        .pendingPhotoHashes.sorted().filter { photoBlobs.file(it) == null }.take(32)
        .map { it to photoBlobs.partialSize(it) }

    @Synchronized override fun recordPeerProgress(deviceId: String, progress: SyncProgress) {
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
            progress.received.forEach { (author, head) ->
                val old = previous.received[author]
                require(old == null || head.sequence >= old.sequence) { "Peer received progress moved backward" }
                require(old == null || head.sequence != old.sequence || head.hash == old.hash)
            }
            progress.applied.forEach { (author, head) ->
                val old = previous.applied[author]
                require(old == null || head.sequence >= old.sequence) { "Peer applied progress moved backward" }
                require(old == null || head.sequence != old.sequence || head.hash == old.hash)
            }
        }
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            peerProgress = mirror.peerProgress + (deviceId to progress))).toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun pending(frontier: SyncFrontier, relayRemoved: Boolean): List<SyncOperationEntity> {
        frontier.validate()
        val mirror = requireNotNull(snapshot())
        val vaultId = mirror.vaultId
        // A receiver rejects a change by a device that is not an active member, and that ends the
        // session. Changes of a removed device go only to a peer that accepts them up to the cutoff.
        // Single changes are never left out by type (for example an appearance change for a peer
        // without SyncFeatures.APPEARANCE): each author's changes form a hash chain, so a gap would
        // stop every later change of that author on the peer just as a rejected change does. The
        // writer's gate (AppearanceSyncRules.gateOpen) keeps such changes out of the chain instead.
        val authors = relayableAuthors(mirror.members)
        val cutoffs = if (relayRemoved) RemovalCutoff.cutoffsOf(mirror.membershipEvents) else emptyMap()
        return outgoingPointers().asSequence().filter { it.vaultId == vaultId &&
            (it.deviceId in authors || RemovalCutoff.allows(cutoffs[it.deviceId], it.sequence, it.hash)) &&
            it.sequence > (frontier.counters[it.deviceId] ?: 0L) }.take(100).map { pointer ->
            val bytes = readProtected(File(outgoing, pointer.hash))
            try {
                gson.fromJson(bytes.toString(Charsets.UTF_8), SyncOperationEntity::class.java).also {
                    require(it.vaultId == pointer.vaultId && it.deviceId == pointer.deviceId &&
                        it.sequence == pointer.sequence && it.hash == pointer.hash) { "Outgoing index changed" }
                }
            } finally { bytes.fill(0) }
        }.toList()
    }

    @Synchronized override fun queue(operation: SyncOperationEntity) {
        val mirror = requireNotNull(snapshot())
        verifyIncomingOperation(mirror, operation)
        val change = operation.toSyncChange()
        val file = File(incoming, change.hash)
        // A resend of something already held is acknowledged again without a second copy.
        if (file.exists() || File(outgoing, change.hash).exists()) return
        val link = ChainLink(change.deviceId, change.sequence, change.hash, change.previousHash)
        IncomingChain.admit(link, progress().received, emptySet())
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
    }

    suspend fun applyQueued(database: VaultDatabase, localKey: ByteArray): Int {
        val mirror = requireNotNull(snapshot())
        database.withTransaction {
            val dao = database.syncDao()
            val current = dao.membershipEvents(mirror.vaultId)
            val accepted = SyncMembershipManager.verify(mirror.membershipEvents.map { it.toEvent() })
            require(current.zip(mirror.membershipEvents).all { (left, right) -> left.hash == right.hash }) {
                "Conflicting membership history"
            }
            mirror.membershipEvents.drop(current.size).forEach { dao.insertMembershipEvent(it) }
            accepted.members.forEach { member ->
                val label = dao.membership(mirror.vaultId, member.deviceId)?.displayName
                dao.upsertMembership(member.copy(displayName = label ?: member.displayName))
            }
        }
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
                synchronized(this) {
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
                }
                if (result != IncomingResult.REPLAY) applied++
                progress = true
            }
        } while (progress)
        publish(database)
        return applied
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
    catch (_: MissingRecordDependencyException) { null }
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
    }

    @Synchronized private fun quarantine(hash: String) {
        val file = File(incoming, hash)
        if (!file.exists()) return
        rejected.mkdirs()
        check(file.renameTo(File(rejected, hash))) { "Could not isolate rejected sync change" }
        incomingIndex = incomingIndex?.filterNot { it.link.hash == hash }
    }

    /** Clears the group's transport state. Leave and removal notices survive on purpose. */
    @Synchronized fun clear() {
        listOf(outgoing, incoming, rejected).forEach { folder -> folder.listFiles().orEmpty().forEach { check(it.delete()) } }
        File(directory, "blobs").listFiles().orEmpty().forEach { check(it.delete()) }
        File(directory, "peers").let { if (it.exists()) check(it.delete()) }
        listOf("peers.bak", "peers.new").forEach { name ->
            File(directory, name).let { if (it.exists()) check(it.delete()) }
        }
        outgoingIndex = null
        incomingIndex = null
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
        return localActive && mirror.members.any { it.status == MemberStatus.REVOKED.name } && retainedEpochs().isNotEmpty()
    }

    private fun outgoingPointers(): List<OperationPointer> = outgoingIndex ?: readOperations(outgoing)
        .map { OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash, it.previousHash) }
        .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
        .also { outgoingIndex = it }

    private fun incomingPointers(): List<QueuedPointer> = incomingIndex ?: readOperations(incoming)
        .map { QueuedPointer(it.vaultId, ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash), meta(it)) }
        .also { incomingIndex = it }

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

    private companion object {
        const val KEY_ALIAS = "nuvori_sync_transport_v1"
        /** The cached Drive file list (about 150 bytes a file); Windows stops listing at 200,000 files. */
        const val CLOUD_FILES_LIMIT = 32 * 1024 * 1024
        val HASH = Regex("[a-f0-9]{64}")
    }
}
