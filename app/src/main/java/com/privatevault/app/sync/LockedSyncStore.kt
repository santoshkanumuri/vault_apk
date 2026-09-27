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

@Keep
internal data class TransportMirror(val vaultId: String, val localDeviceId: String,
    val publicKey: String, val transportSecret: String, val keyEpoch: Long,
    val peers: List<SyncPeerEntity>, val members: List<SyncMembershipEntity>,
    val membershipEvents: List<SyncMembershipEventEntity>, val heads: List<SyncDeviceHeadEntity>,
    val peerProgress: Map<String, SyncProgress> = emptyMap(),
    val peerAddresses: Map<String, String> = emptyMap(),
    val lastContactAt: Map<String, Long> = emptyMap(),
    val lastExchangeAt: Map<String, Long> = emptyMap(),
    val pendingPhotoHashes: Set<String> = emptySet())

internal class SyncQueueFullException : Exception("Unlock this device to apply queued changes")

/** Contains transport secrets and encrypted operations only. It never stores a vault content key. */
internal class LockedSyncStore(private val context: Context,
    private val directory: File = File(context.noBackupFilesDir, "sync-transport")) {
    private data class OperationPointer(val vaultId: String, val deviceId: String,
        val sequence: Long, val hash: String)

    private val outgoing = File(directory, "outgoing")
    private val incoming = File(directory, "incoming")
    private val rejected = File(directory, "rejected")
    val photoBlobs = PhotoSyncBlobs(File(directory, "blobs"))
    private val gson = Gson()
    private var outgoingIndex: List<OperationPointer>? = null

    suspend fun publish(database: VaultDatabase) {
        val identity = AndroidDeviceIdentityStore(context).getOrCreate()
        val (mirror, operations, photoHashes, hasConflicts) = database.withTransaction {
            val vaultId = requireNotNull(database.dao().settings()).vaultId
            val group = requireNotNull(database.syncDao().vaultState()) { "Sync group is not ready" }
            require(group.vaultId == vaultId && Base64.getUrlDecoder().decode(group.transportSecret).size == 32)
            val mirror = TransportMirror(vaultId, identity.deviceId, identity.publicKeyBase64Url,
                group.transportSecret, group.keyEpoch,
                database.syncDao().peers(vaultId), database.syncDao().memberships(vaultId),
                database.syncDao().membershipEvents(vaultId),
                database.syncDao().deviceHeads(vaultId))
            PublishedState(mirror, database.syncDao().operations(),
                database.syncDao().attachments().mapTo(hashSetOf()) { it.ciphertextHash },
                database.syncDao().conflicts().any { it.resolvedAtUtc.isEmpty() })
        }
        publishState(mirror, operations, photoHashes, hasConflicts)
    }

    private data class PublishedState(val mirror: TransportMirror, val operations: List<SyncOperationEntity>,
        val photoHashes: Set<String>, val hasConflicts: Boolean)

    @Synchronized private fun publishState(mirror: TransportMirror, operations: List<SyncOperationEntity>,
        photoHashes: Set<String>, hasConflicts: Boolean) {
        val previous = snapshot()
        if (previous != null && previous.vaultId != mirror.vaultId) clear()
        val selected = if (previous != null && previous.vaultId == mirror.vaultId) {
            require(previous.transportSecret == mirror.transportSecret && previous.keyEpoch == mirror.keyEpoch)
            val databaseEvents = mirror.membershipEvents.map { it.toEvent() }
            val storedEvents = previous.membershipEvents.map { it.toEvent() }
            SyncMembershipManager.verify(databaseEvents)
            val stored = SyncMembershipManager.verify(storedEvents)
            require(databaseEvents.zip(storedEvents).all { (left, right) -> left.hash == right.hash }) {
                "Conflicting membership history"
            }
            if (storedEvents.size > databaseEvents.size) mirror.copy(
                membershipEvents = previous.membershipEvents, members = previous.members,
                peerProgress = previous.peerProgress, peerAddresses = previous.peerAddresses,
                lastContactAt = previous.lastContactAt, lastExchangeAt = previous.lastExchangeAt,
                pendingPhotoHashes = previous.pendingPhotoHashes)
            else mirror.copy(peerProgress = previous.peerProgress, peerAddresses = previous.peerAddresses,
                lastContactAt = previous.lastContactAt, lastExchangeAt = previous.lastExchangeAt,
                pendingPhotoHashes = previous.pendingPhotoHashes)
        } else mirror
        val activePeers = selected.members.filter {
            it.status == MemberStatus.ACTIVE.name && it.deviceId != selected.localDeviceId
        }
        val retained = operations.filter { operation ->
            operation.deviceId != selected.localDeviceId || activePeers.isEmpty() || activePeers.any { peer ->
                (selected.peerProgress[peer.deviceId]?.applied?.get(operation.deviceId)?.sequence ?: 0L) < operation.sequence
            }
        }
        outgoing.mkdirs(); incoming.mkdirs()
        val retainedHashes = retained.mapTo(hashSetOf()) { it.hash }
        outgoing.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) && it.name !in retainedHashes }
            .forEach { check(it.delete()) { "Could not prune acknowledged sync change" } }
        retained.forEach { operation ->
            val file = File(outgoing, operation.hash)
            if (!file.exists()) writeProtected(file, gson.toJson(operation).toByteArray(Charsets.UTF_8))
        }
        writeProtected(File(directory, "peers"), gson.toJson(selected).toByteArray(Charsets.UTF_8))
        photoBlobs.pruneAbandonedPartials(selected.pendingPhotoHashes)
        val allApplied = activePeers.isNotEmpty() && selected.heads.all { head ->
            activePeers.all { peer ->
                (selected.peerProgress[peer.deviceId]?.applied?.get(head.deviceId)?.sequence ?: 0L) >= head.sequence
            }
        }
        if (allApplied && !hasConflicts && selected.pendingPhotoHashes.isEmpty() &&
            incoming.listFiles().isNullOrEmpty() && rejected.listFiles().isNullOrEmpty())
            photoBlobs.pruneCompleted(photoHashes, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        outgoingIndex = retained.map { OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash) }
            .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
    }

    @Synchronized fun snapshot(): TransportMirror? {
        val file = File(directory, "peers")
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = readProtected(file)
        return try {
            val mirror = gson.fromJson(bytes.toString(Charsets.UTF_8), TransportMirror::class.java)
            mirror.copy(peerProgress = mirror.peerProgress ?: emptyMap(),
                peerAddresses = mirror.peerAddresses ?: emptyMap(),
                lastContactAt = mirror.lastContactAt ?: emptyMap(),
                lastExchangeAt = mirror.lastExchangeAt ?: emptyMap(),
                pendingPhotoHashes = mirror.pendingPhotoHashes ?: emptySet())
        }
        finally { bytes.fill(0) }
    }

    @Synchronized fun acceptMembershipEvents(events: List<SyncMembershipEventEntity>) {
        val mirror = requireNotNull(snapshot())
        val current = mirror.membershipEvents.map { it.toEvent() }
        val incoming = events.map { it.toEvent() }
        SyncMembershipManager.verify(current)
        val verified = SyncMembershipManager.verify(incoming)
        require(verified.events.first().vaultId == mirror.vaultId && verified.keyEpoch == mirror.keyEpoch)
        require(current.zip(incoming).all { (left, right) -> left.hash == right.hash }) {
            "Conflicting membership history"
        }
        if (incoming.size <= current.size) return
        val members = verified.members.map { member ->
            val label = mirror.members.firstOrNull { it.deviceId == member.deviceId }?.displayName
            member.copy(displayName = label ?: member.displayName)
        }
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            membershipEvents = events, members = members)).toByteArray(Charsets.UTF_8))
    }

    fun signIdentity(bytes: ByteArray): ByteArray = AndroidDeviceIdentityStore(context).sign(bytes)

    @Synchronized fun progress(): SyncProgress {
        val mirror = requireNotNull(snapshot())
        val applied = mirror.heads.associate { it.deviceId to SyncChainHead(it.sequence, it.hash) }
        val received = applied.toMutableMap()
        readOperations(incoming).sortedWith(compareBy({ it.deviceId }, { it.sequence })).forEach { operation ->
            val current = received[operation.deviceId] ?: SyncChainHead(0, GENESIS_HASH)
            if (operation.sequence == current.sequence + 1L && operation.previousHash == current.hash)
                received[operation.deviceId] = SyncChainHead(operation.sequence, operation.hash)
        }
        return SyncProgress(received, applied).also { it.validate() }
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
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val mirror = requireNotNull(snapshot())
        require(mirror.pendingPhotoHashes.size < 1024 || hash in mirror.pendingPhotoHashes)
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            pendingPhotoHashes = mirror.pendingPhotoHashes + hash)).toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun receivedPhoto(hash: String) {
        val mirror = requireNotNull(snapshot())
        writeProtected(File(directory, "peers"), gson.toJson(mirror.copy(
            pendingPhotoHashes = mirror.pendingPhotoHashes - hash)).toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun missingPhotos(): List<Pair<String, Long>> = requireNotNull(snapshot())
        .pendingPhotoHashes.sorted().filter { photoBlobs.file(it) == null }.take(32)
        .map { it to photoBlobs.partialSize(it) }

    @Synchronized fun recordPeerProgress(deviceId: String, progress: SyncProgress) {
        progress.validate()
        val mirror = requireNotNull(snapshot())
        require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
        val known = outgoingIndex ?: readOperations(outgoing)
            .map { OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash) }
            .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
            .also { outgoingIndex = it }
        val receivedLocally = progress().received
        val queuedLocally = readOperations(incoming)
        (progress.received.entries + progress.applied.entries).forEach { (author, head) ->
            val latest = mirror.heads.firstOrNull { it.deviceId == author }
            if (author == mirror.localDeviceId) require(head.sequence <= (receivedLocally[author]?.sequence ?: 0L)) {
                "Peer reported an unknown change head"
            }
            val localHash = known.firstOrNull { it.deviceId == author && it.sequence == head.sequence }?.hash
                ?: queuedLocally.firstOrNull { it.deviceId == author && it.sequence == head.sequence }?.hash
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

    @Synchronized fun pending(frontier: SyncFrontier): List<SyncOperationEntity> {
        frontier.validate()
        val vaultId = requireNotNull(snapshot()).vaultId
        val index = outgoingIndex ?: readOperations(outgoing)
            .map { OperationPointer(it.vaultId, it.deviceId, it.sequence, it.hash) }
            .sortedWith(compareBy({ it.deviceId }, { it.sequence }))
            .also { outgoingIndex = it }
        return index.asSequence().filter { it.vaultId == vaultId &&
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

    @Synchronized fun queue(operation: SyncOperationEntity) {
        val mirror = requireNotNull(snapshot())
        require(operation.vaultId == mirror.vaultId)
        val member = requireNotNull(mirror.members.firstOrNull {
            it.deviceId == operation.deviceId && it.status == MemberStatus.ACTIVE.name
        }) { "Unknown or revoked sender" }
        val change = operation.toSyncChange()
        change.validate()
        require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey),
            change.signingBytes(), Base64.getUrlDecoder().decode(change.deviceSignature))) { "Invalid sender signature" }
        val file = File(incoming, change.hash)
        if (file.exists() || File(outgoing, change.hash).exists()) return
        val existing = mirror.heads.firstOrNull { it.deviceId == change.deviceId }
        val pending = readOperations(incoming).filter { it.deviceId == change.deviceId }.maxByOrNull { it.sequence }
        val sequence = maxOf(existing?.sequence ?: 0L, pending?.sequence ?: 0L)
        val hash = if (pending != null && pending.sequence > (existing?.sequence ?: 0L)) pending.hash else existing?.hash ?: GENESIS_HASH
        require(change.sequence == sequence + 1L && change.previousHash == hash) { "Incomplete change chain" }
        val bytes = gson.toJson(operation).toByteArray(Charsets.UTF_8)
        try {
            val files = incoming.listFiles().orEmpty().filter { it.isFile }
            if (files.size >= 2048 || files.sumOf { it.length() } + bytes.size + 28 > 32L * 1024 * 1024)
                throw SyncQueueFullException()
            incoming.mkdirs()
            writeProtected(file, bytes)
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
                val result = try { IncomingEntryChangeApplier(database, photoBlobs, context).apply(operation, localKey) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (missing: MissingPhotoBlobException) { requestPhoto(missing.hash); continue }
                catch (_: MissingPhotoDependencyException) { continue }
                catch (_: MissingRecordDependencyException) { continue }
                catch (_: SQLiteConstraintException) { quarantine(operation.hash); continue }
                catch (_: IllegalArgumentException) { quarantine(operation.hash); continue }
                catch (_: JSONException) { quarantine(operation.hash); continue }
                catch (_: javax.crypto.AEADBadTagException) { quarantine(operation.hash); continue }
                synchronized(this) {
                    val queued = File(incoming, operation.hash)
                    if (queued.exists()) {
                        outgoing.mkdirs()
                        writeProtected(File(outgoing, operation.hash), gson.toJson(operation).toByteArray(Charsets.UTF_8))
                        outgoingIndex = null
                        check(queued.delete()) { "Could not acknowledge queued change" }
                    }
                }
                if (result != IncomingResult.REPLAY) applied++
                progress = true
            }
        } while (progress)
        publish(database)
        return applied
    }

    @Synchronized fun queuedCount(): Int = incoming.listFiles().orEmpty().count { it.name.matches(Regex("[a-f0-9]{64}")) }

    @Synchronized fun rejectedCount(): Int = rejected.listFiles().orEmpty().count {
        it.name.matches(Regex("[a-f0-9]{64}"))
    }

    @Synchronized fun retryRejected() {
        rejected.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) }
            .forEach { file ->
                val target = File(incoming, file.name)
                if (target.exists()) check(file.delete())
                else check(file.renameTo(target)) { "Could not retry rejected sync change" }
            }
    }

    @Synchronized private fun quarantine(hash: String) {
        val file = File(incoming, hash)
        if (!file.exists()) return
        rejected.mkdirs()
        check(file.renameTo(File(rejected, hash))) { "Could not isolate rejected sync change" }
    }

    @Synchronized fun clear() {
        listOf(outgoing, incoming, rejected).forEach { folder -> folder.listFiles().orEmpty().forEach { check(it.delete()) } }
        File(directory, "blobs").listFiles().orEmpty().forEach { check(it.delete()) }
        File(directory, "peers").let { if (it.exists()) check(it.delete()) }
        listOf("peers.bak", "peers.new").forEach { name ->
            File(directory, name).let { if (it.exists()) check(it.delete()) }
        }
        outgoingIndex = null
    }

    private fun readOperations(folder: File): List<SyncOperationEntity> = folder.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9]{64}")) }.map { file ->
            val bytes = readProtected(file)
            try { gson.fromJson(bytes.toString(Charsets.UTF_8), SyncOperationEntity::class.java) }
            finally { bytes.fill(0) }
        }

    private fun writeProtected(file: File, plaintext: ByteArray) {
        try {
            require(plaintext.size <= SyncFrames.MAX_FRAME)
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

    private fun readProtected(file: File): ByteArray {
        val bytes = AtomicFile(file).openRead().use { input ->
            require(input.channel.size() in 28..(SyncFrames.MAX_FRAME + 28L)) { "Invalid protected sync file" }
            input.readBytes()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes, 0, 12))
            updateAAD(file.name.toByteArray(Charsets.UTF_8))
            doFinal(bytes, 12, bytes.size - 12)
        }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private companion object { const val KEY_ALIAS = "nuvori_sync_transport_v1" }
}
