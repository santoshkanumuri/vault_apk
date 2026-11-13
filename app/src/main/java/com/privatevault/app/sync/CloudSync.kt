package com.privatevault.app.sync

import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncOperationEntity
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Cloud sync: the user's own Google Drive carries the group's signed changes between devices that
 * are not on the same Wi-Fi. Format `nv1`, shared with Windows `cloud_sync.rs` (vectors in
 * `cloud-sync-v1.json`, the same file in both repositories).
 *
 * The drive is a mailbox, not an authority. Every change is signed by its author and hash-chained,
 * and membership is a signed chain, exactly as in Wi-Fi sync, so everything read from the drive
 * goes through the same checks as Wi-Fi data ([CloudSyncStore.queue],
 * [CloudSyncStore.acceptMembershipEvents]). A file that fails them is ignored. Payloads and photo
 * blobs are already encrypted under the group's content key, and each file is sealed again under a
 * key derived from the transport secret, so entry IDs, device IDs and times stay private.
 *
 * Files, all in the app-only Drive folder:
 *   nv1.<group>.m.<count>.<head16>         JSON array of membership events (as sent over Wi-Fi)
 *   nv1.<group>.o.<device>.<first>.<last>  JSON array of changes by one author (as sent over Wi-Fi)
 *   nv1.<group>.p.<device>                 {deviceId, updatedAt, progress}
 *   nv1.<group>.b.<sha256>                 a photo blob as stored (no envelope)
 *   nv1.<group>.l.<device>                 {request, signature}: a signed leave request ([CloudLeave])
 *
 * cloud key = HMAC-SHA256(epoch-1 transport secret, "nuvori-cloud-v1:<vaultId>");
 * <group> and <device> = first 8 bytes, hex, of HMAC-SHA256(cloud key, "tag" / "device:<id>");
 * envelope = "NVC1" || nonce(12) || AES-256-GCM(cloud key, plaintext, AAD = file name UTF-8).
 */

/** What the user can do about a failed drive call. */
internal enum class CloudError {
    /** The saved sign-in no longer works; the user must sign in again. */
    AUTH, OFFLINE,
    /** The drive is full. */
    QUOTA, NOT_FOUND,
    /** Google asked to slow down. */
    BUSY, OTHER;

    /** A fixed sentence for the UI (same text as Windows). It never contains file names or account data. */
    val message: String get() = when (this) {
        AUTH -> AUTH_NEEDED
        OFFLINE -> "Can't reach Google Drive. Check the internet connection."
        QUOTA -> "Your Google Drive is full. Free some space, then sync again."
        NOT_FOUND -> "A sync file is gone from Google Drive. Sync will check again."
        BUSY -> "Google Drive is busy. Sync will retry soon."
        OTHER -> "Google Drive sync failed. Sync will retry."
    }

    companion object { const val AUTH_NEEDED = "Google Drive needs you to sign in again." }
}

/** [detail] is for debugging only (an HTTP code); it is never shown and never holds file contents. */
internal class CloudException(val error: CloudError, detail: String = error.name) : Exception(detail)

/** One file in the app folder. [modified] is RFC 3339 UTC as the drive reports it. */
@Keep
internal data class CloudFile(@SerializedName("id") val id: String, @SerializedName("name") val name: String,
    @SerializedName("size") val size: Long, @SerializedName("modified") val modified: String)

/** The few drive operations cloud sync needs. [GoogleDrive] implements it; tests use one in memory. */
internal interface CloudDrive {
    /** Files in the app folder, all of them or only those changed after [modifiedAfter]. */
    fun list(modifiedAfter: String?): List<CloudFile>
    /** Hands the file's content to [read]; fails with [CloudError.OTHER] past [maxBytes]. */
    fun download(id: String, maxBytes: Long, read: (InputStream) -> Unit)
    fun upload(name: String, size: Long, content: InputStream): CloudFile
    fun replace(id: String, content: ByteArray): CloudFile
}

internal fun CloudDrive.downloadBytes(id: String, maxBytes: Long): ByteArray {
    var bytes = ByteArray(0)
    download(id, maxBytes) { bytes = it.readBytes() }
    return bytes
}

internal fun CloudDrive.uploadBytes(name: String, bytes: ByteArray): CloudFile =
    upload(name, bytes.size.toLong(), ByteArrayInputStream(bytes))

/** What a file name in the app folder means. */
internal sealed interface CloudName {
    data class Membership(val count: Long, val head: String) : CloudName
    data class Operations(val device: String, val first: Long, val last: Long) : CloudName
    data class Progress(val device: String) : CloudName
    data class Blob(val hash: String) : CloudName
    data class Leave(val device: String) : CloudName
}

/**
 * A signed leave request on Google Drive: the same request and Ed25519 signature as the Wi-Fi
 * `leave1` frame, as JSON `{"request": {vaultId, deviceId, membershipHead, keyEpoch, createdAt},
 * "signature": "<base64url, no padding>"}` in a sealed envelope. The managing device answers it
 * with [LeaveRequestHandler.handleFromDrive].
 */
internal object CloudLeave {
    const val MAX_BYTES = 4096L

    fun encode(request: LeaveRequest, signature: String): ByteArray = JsonObject().apply {
        add("request", JsonParser.parseString(request.toJson()))
        addProperty("signature", signature)
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    /** Null for anything but exactly that shape with a valid request. */
    fun parse(bytes: ByteArray): Pair<LeaveRequest, ByteArray>? = runCatching {
        require(bytes.size <= MAX_BYTES)
        val o = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(o.keySet() == setOf("request", "signature"))
        val request = LeaveRequest.parseJson(o.getAsJsonObject("request").toString())
        val signature = Base64.getUrlDecoder().decode(o.getAsJsonPrimitive("signature").also { require(it.isString) }.asString)
        require(signature.size == DeviceIdentityCrypto.SIGNATURE_BYTES)
        request to signature
    }.getOrNull()
}

/** Names and the envelope for one sync group. */
internal class CloudKeys private constructor(private val key: ByteArray) {
    private val tag = shortMac("tag")
    val prefix = "nv1.$tag."

    fun deviceTag(deviceId: String) = shortMac("device:$deviceId")
    fun membershipName(count: Long, head: String) = "${prefix}m.${count.toString().padStart(10, '0')}.${head.take(16)}"
    fun operationsName(deviceId: String, first: Long, last: Long) =
        "${prefix}o.${deviceTag(deviceId)}.${first.toString().padStart(12, '0')}.${last.toString().padStart(12, '0')}"
    fun progressName(deviceId: String) = "${prefix}p.${deviceTag(deviceId)}"
    fun blobName(hash: String) = "${prefix}b.$hash"
    fun leaveName(deviceId: String) = "${prefix}l.${deviceTag(deviceId)}"

    /** Null for a file of another group or with a name this version does not know. */
    fun parse(name: String): CloudName? {
        if (!name.startsWith(prefix)) return null
        val parts = name.substring(prefix.length).split('.')
        return when {
            parts.size == 3 && parts[0] == "m" && hexField(parts[2], 16) ->
                numberField(parts[1], 10)?.takeIf { it > 0 }?.let { CloudName.Membership(it, parts[2]) }
            parts.size == 4 && parts[0] == "o" && hexField(parts[1], 16) -> {
                val first = numberField(parts[2], 12)
                val last = numberField(parts[3], 12)
                if (first != null && last != null && first >= 1 && last >= first) CloudName.Operations(parts[1], first, last)
                else null
            }
            parts.size == 2 && parts[0] == "p" && hexField(parts[1], 16) -> CloudName.Progress(parts[1])
            parts.size == 2 && parts[0] == "b" && parts[1].matches(HASH) -> CloudName.Blob(parts[1])
            parts.size == 2 && parts[0] == "l" && hexField(parts[1], 16) -> CloudName.Leave(parts[1])
            else -> null
        }
    }

    fun seal(name: String, plaintext: ByteArray): ByteArray =
        sealWithNonce(name, plaintext, ByteArray(12).also(SecureRandom()::nextBytes))

    @VisibleForTesting
    internal fun sealWithNonce(name: String, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        require(nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(name.toByteArray(Charsets.UTF_8))
        }
        return MAGIC + nonce + cipher.doFinal(plaintext)
    }

    /** Null when the bytes were not sealed by this group under this file name. */
    fun open(name: String, sealed: ByteArray): ByteArray? {
        if (sealed.size < MAGIC.size + 12 + 16 || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, MAGIC.size, 12))
                updateAAD(name.toByteArray(Charsets.UTF_8))
                doFinal(sealed, MAGIC.size + 12, sealed.size - MAGIC.size - 12)
            }
        }.getOrNull()
    }

    @VisibleForTesting internal fun keyHex(): String = key.hex()

    private fun shortMac(label: String): String = hmac(key, label).copyOf(8).hex()

    companion object {
        private val MAGIC = "NVC1".toByteArray(Charsets.US_ASCII)
        private val HASH = Regex("[a-f0-9]{64}")

        fun derive(transportSecret: ByteArray, vaultId: String): CloudKeys? =
            if (transportSecret.size != 32) null else CloudKeys(hmac(transportSecret, "nuvori-cloud-v1:$vaultId"))

        private fun hmac(key: ByteArray, label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(label.toByteArray(Charsets.UTF_8))
        }

        private fun hexField(text: String, length: Int) =
            text.length == length && text.all { it in '0'..'9' || it in 'a'..'f' }

        private fun numberField(text: String, length: Int): Long? =
            if (text.length == length && text.all { it in '0'..'9' }) text.toLongOrNull() else null
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** How far the files for one author reach without a gap, starting at change 1. */
internal fun cloudCoverage(ranges: List<Pair<Long, Long>>): Long {
    var covered = 0L
    for ((first, last) in ranges.sortedWith(compareBy({ it.first }, { it.second }))) {
        if (first > covered + 1) break
        covered = maxOf(covered, last)
    }
    return covered
}

/**
 * Cloud sync settings and per-group pass state, kept in the transport store's Keystore-protected
 * files. [covered] maps an author to the last change the drive holds without a gap; this device
 * keeps its own changes past that point for the next upload (see `LockedSyncStore.publishState`).
 * Fields may be null when read from an older file; use [normalized].
 */
@Keep
internal data class CloudSyncState(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("account") val account: String = "",
    @SerializedName("vaultId") val vaultId: String = "",
    @SerializedName("covered") val covered: Map<String, Long> = emptyMap(),
    @SerializedName("cursor") val cursor: String = "",
    @SerializedName("progressDigest") val progressDigest: String = "",
    @SerializedName("lastSyncAt") val lastSyncAt: Long = 0L) {
    @Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
    fun normalized() = CloudSyncState(enabled, account ?: "", vaultId ?: "", covered ?: emptyMap(),
        cursor ?: "", progressDigest ?: "", lastSyncAt)

    /** The same connection with the pass state of another group cleared. */
    fun forGroup(groupVaultId: String) = if (vaultId == groupVaultId) this
        else CloudSyncState(enabled, account, groupVaultId)
}

/** The transport state a cloud pass reads and writes. [LockedSyncStore] is the production one. */
internal interface CloudSyncStore : SyncSessionStore {
    fun snapshot(): TransportMirror?
    fun acceptMembershipEvents(events: List<SyncMembershipEventEntity>)
    /** Changes by [author] this device can relay, after [after], in order, at most [limit]. */
    fun heldOperations(author: String, after: Long, limit: Int): List<SyncOperationEntity>
    /** A peer wrote its progress to the drive at [at]. */
    fun recordPeerSeen(deviceId: String, at: Long)
    fun cloudState(): CloudSyncState
    fun updateCloudState(transform: (CloudSyncState) -> CloudSyncState)
    fun cloudFiles(): List<CloudFile>
    fun saveCloudFiles(files: List<CloudFile>)
    /** Managing device: answers a leave request found on the drive. True when it signed new events. */
    fun answerDriveLeave(request: LeaveRequest, signature: ByteArray): Boolean = false
}

internal data class CloudPassReport(val received: Int = 0, val sent: Int = 0, val photosReceived: Int = 0,
    val photosSent: Int = 0, val complete: Boolean = true, val queueFull: Boolean = false,
    val removed: RemovalNotice.Removed? = null)

internal class NotCloudMemberException : Exception("This device is no longer in the sync group.")

/**
 * One cloud pass, the same steps as Windows `cloud_sync::run`: list, take a longer signed
 * membership, download changes this device lacks, upload changes the drive lacks, move photo
 * blobs, publish progress. It writes only to [store], never to the vault database; the unlocked
 * app applies what was queued. [full] lists the whole folder instead of only recent files.
 */
internal class CloudSyncPass(private val store: CloudSyncStore, private val drive: CloudDrive,
    private val now: () -> Long = System::currentTimeMillis) {
    private val gson = Gson()

    fun run(full: Boolean): CloudPassReport {
        val mirror = requireNotNull(store.snapshot()) { "Sync group is not ready" }
        val local = mirror.localDeviceId
        if (mirror.members.none { it.deviceId == local && it.status == MemberStatus.ACTIVE.name })
            throw NotCloudMemberException()
        val secret = Base64.getUrlDecoder().decode(mirror.transportSecret)
        val keys = try { requireNotNull(CloudKeys.derive(secret, mirror.vaultId)) { "Invalid sync keys" } }
            finally { secret.fill(0) }
        val stored = store.cloudState().normalized()
        var state = stored.forGroup(mirror.vaultId)
        val files = (if (stored.vaultId == mirror.vaultId) store.cloudFiles() else emptyList()).toMutableList()
        fun remember(file: CloudFile) {
            files.removeAll { it.id == file.id }
            if (newer(file.modified, state.cursor)) state = state.copy(cursor = file.modified)
            files += file
        }
        fun save(finished: Boolean) {
            store.saveCloudFiles(files.toList())
            val saved = state
            store.updateCloudState { current -> current.normalized().copy(vaultId = saved.vaultId, covered = saved.covered,
                cursor = saved.cursor, progressDigest = saved.progressDigest,
                lastSyncAt = if (finished) now() else if (current.vaultId == saved.vaultId) current.lastSyncAt else 0L) }
        }
        fun parsed(): List<Pair<CloudFile, CloudName>> = files.mapNotNull { file -> keys.parse(file.name)?.let { file to it } }

        // 1. List.
        val since = if (!full && files.isNotEmpty()) relistFrom(state.cursor) else null
        val listed = drive.list(since).filter { it.name.startsWith(keys.prefix) && keys.parse(it.name) != null }
        if (since == null) { files.clear(); state = state.copy(cursor = "") }
        listed.forEach(::remember)
        save(false)

        // 1b. Leave requests: the managing device answers a new `l.` file of an active member as it
        // answers a Wi-Fi leave request. Step 2 then publishes the history with the REMOVE.
        var events = mirror.membershipEvents
        val managing = runCatching {
            SyncMembershipManager.verify(events.map { it.toEvent() }).managerDeviceId == local
        }.getOrDefault(false)
        if (managing) {
            val members = mirror.members.filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != local }
                .associateBy { keys.deviceTag(it.deviceId) }
            for (file in listed) {
                val name = keys.parse(file.name) as? CloudName.Leave ?: continue
                val member = members[name.device] ?: continue
                val leave = runCatching {
                    CloudLeave.parse(requireNotNull(keys.open(file.name, drive.downloadBytes(file.id, CloudLeave.MAX_BYTES))))
                }.getOrNull() ?: continue
                if (leave.first.deviceId != member.deviceId) continue
                if (runCatching { store.answerDriveLeave(leave.first, leave.second) }.getOrDefault(false))
                    events = requireNotNull(store.snapshot()).membershipEvents
            }
        }

        // 2. Membership: take a longer signed history, or publish ours when the drive lacks it.
        val histories = parsed().mapNotNull { (file, name) -> (name as? CloudName.Membership)?.let { it.count to file } }
            .sortedByDescending { it.first }
        var published = histories.any { it.first == events.size.toLong() }
        for ((_, file) in histories.filter { it.first > events.size }.take(3)) {
            val remote = runCatching {
                MembershipWire.parseHistory(requireNotNull(keys.open(file.name, drive.downloadBytes(file.id, MEMBERSHIP_BYTES))))
            }.getOrNull() ?: continue
            if (remote.size <= events.size) continue
            // The same check as a Wi-Fi removal notice: it must verify and extend this device's history.
            val notice = runCatching { RemovalNoticeVerifier.verify(events, local, remote) }.getOrNull() ?: continue
            val history = when (notice) {
                is RemovalNotice.Removed -> notice.history
                is RemovalNotice.CatchUp -> notice.history
            }
            if (runCatching { store.acceptMembershipEvents(history) }.isFailure) continue
            if (notice is RemovalNotice.Removed) {
                save(false)
                return CloudPassReport(complete = false, removed = notice)
            }
            events = remote
            published = true
            break
        }
        if (!published) {
            val head = events.last().hash
            val name = keys.membershipName(events.size.toLong(), head)
            remember(drive.uploadBytes(name, keys.seal(name, gson.toJson(events).toByteArray(Charsets.UTF_8))))
        }

        val current = requireNotNull(store.snapshot())
        var complete = true
        var received = 0
        var sent = 0
        val authors = current.members.associate { keys.deviceTag(it.deviceId) to it.deviceId }
        // Per author: the files that hold its changes.
        val segments = mutableMapOf<String, MutableList<Triple<Long, Long, CloudFile>>>()
        parsed().forEach { (file, name) ->
            if (name is CloudName.Operations) authors[name.device]?.let { author ->
                segments.getOrPut(author) { mutableListOf() } += Triple(name.first, name.last, file)
            }
        }

        // 3. Download what this device lacks. Every change passes the same checks as over Wi-Fi.
        val unusable = mutableSetOf<String>()
        var queueFull = false
        downloads@ for ((author, candidates) in segments.toSortedMap()) {
            if (author == local) continue
            while (true) {
                val head = store.progress().received[author]?.sequence ?: 0L
                val (first, last, file) = candidates.filter { (first, last, file) ->
                    first <= head + 1 && last > head && file.id !in unusable
                }.maxByOrNull { it.second } ?: break
                val sealed = try { drive.downloadBytes(file.id, DOWNLOAD_BYTES) }
                catch (gone: CloudException) {
                    if (gone.error != CloudError.NOT_FOUND) throw gone
                    // The file list is stale: list everything on the next pass.
                    files.removeAll { it.id == file.id }
                    unusable += file.id
                    complete = false
                    continue
                }
                val operations = runCatching {
                    JsonParser.parseString(requireNotNull(keys.open(file.name, sealed)).toString(Charsets.UTF_8)).asJsonArray
                }.getOrNull()?.takeIf { it.size().toLong() == last - first + 1 && it.size() <= DOWNLOAD_OPERATIONS }
                if (operations == null) { unusable += file.id; continue }
                var stored = 0
                for (index in 0 until operations.size()) {
                    val sequence = first + index
                    if (sequence <= head) continue
                    val operation = runCatching {
                        gson.fromJson(operations[index], SyncOperationEntity::class.java)?.takeIf {
                            it.deviceId == author && it.sequence == sequence && it.hash.matches(HASH)
                        }
                    }.getOrNull()
                    if (operation == null) { unusable += file.id; break }
                    try { store.queue(operation) }
                    catch (_: SyncQueueFullException) { queueFull = true; received += stored; break@downloads }
                    catch (_: Exception) {
                        // Changes before this one are verified and kept; the rest of the file is not used.
                        unusable += file.id
                        break
                    }
                    stored++
                }
                received += stored
                if (stored == 0 || (store.progress().received[author]?.sequence ?: 0L) <= head) unusable += file.id
            }
        }
        if (queueFull) complete = false

        // 4. Upload what the drive lacks, for every author this device holds changes of.
        val cutoffs = RemovalCutoff.cutoffsOf(current.membershipEvents)
        val covered = segments.mapValues { (_, list) -> cloudCoverage(list.map { it.first to it.second }) }.toMutableMap()
        var uploadedFiles = 0
        uploads@ for (member in current.members.sortedBy { it.deviceId }) {
            val author = member.deviceId
            val cutoff = if (member.status == MemberStatus.ACTIVE.name) null else cutoffs[author] ?: continue
            var reach = covered[author] ?: 0L
            while (true) {
                val batch = uploadBatch(store.heldOperations(author, reach, UPLOAD_OPERATIONS), reach, cutoff)
                // A gap (an older change already pruned here): another device or a later publish fills it.
                if (batch.isEmpty()) break
                if (uploadedFiles >= UPLOAD_FILES_PER_PASS) { complete = false; break@uploads }
                val plain = batch.joinToString(",", "[", "]") { gson.toJson(it) }.toByteArray(Charsets.UTF_8)
                val name = keys.operationsName(author, batch.first().sequence, batch.last().sequence)
                remember(drive.uploadBytes(name, keys.seal(name, plain)))
                uploadedFiles++
                reach = batch.last().sequence
                sent += batch.size
            }
            if (reach > 0) covered[author] = reach
        }
        state = state.copy(covered = covered.toMap())

        // 5. Photo blobs. A blob is already ciphertext named by its hash, so it needs no envelope.
        val cloudBlobs = parsed().mapNotNull { (file, name) -> (name as? CloudName.Blob)?.let { it.hash to file } }.toMap()
        var photosReceived = 0
        for ((hash, _) in store.missingPhotos().take(PHOTOS_PER_PASS)) {
            val file = cloudBlobs[hash] ?: continue
            if (file.size !in 28..PhotoSyncBlobs.MAX_PHOTO_BYTES + 28) continue
            try {
                drive.download(file.id, file.size) { input -> store.photoBlobs.receive(input, PhotoBlobRef(hash, file.size)) }
                store.receivedPhoto(hash)
                photosReceived++
            } catch (failure: CloudException) {
                if (failure.error != CloudError.OTHER && failure.error != CloudError.NOT_FOUND) throw failure
                runCatching { store.photoBlobs.discardPartial(hash) }
            } catch (full: PhotoStorageFullException) { throw full }
            catch (_: Exception) { runCatching { store.photoBlobs.discardPartial(hash) } }
        }
        val toUpload = current.attachmentHashes.filter { it.matches(HASH) && it !in cloudBlobs }.sorted()
            .asSequence().mapNotNull { hash -> store.photoBlobs.file(hash)?.let { hash to it } }.take(PHOTOS_PER_PASS + 1).toList()
        if (toUpload.size > PHOTOS_PER_PASS) complete = false
        var photosSent = 0
        for ((hash, blob) in toUpload.take(PHOTOS_PER_PASS)) {
            remember(blob.inputStream().use { drive.upload(keys.blobName(hash), blob.length(), it) })
            photosSent++
        }

        // 6. Progress: tell the other devices what this one holds, and read what they hold.
        val ownProgress = store.progress().encode()
        val digest = MessageDigest.getInstance("SHA-256").digest(ownProgress).hex()
        val ownName = keys.progressName(local)
        val ownFile = files.firstOrNull { it.name == ownName }
        if (digest != state.progressDigest || ownFile == null) {
            val body = JsonObject().apply {
                addProperty("deviceId", local)
                addProperty("updatedAt", now())
                add("progress", JsonParser.parseString(ownProgress.toString(Charsets.UTF_8)))
            }
            val sealed = keys.seal(ownName, body.toString().toByteArray(Charsets.UTF_8))
            val file = if (ownFile == null) drive.uploadBytes(ownName, sealed) else try { drive.replace(ownFile.id, sealed) }
                catch (gone: CloudException) {
                    if (gone.error != CloudError.NOT_FOUND) throw gone
                    files.removeAll { it.id == ownFile.id }
                    drive.uploadBytes(ownName, sealed)
                }
            remember(file)
            state = state.copy(progressDigest = digest)
        }
        for (file in listed) {
            val device = (keys.parse(file.name) as? CloudName.Progress)?.device ?: continue
            val peer = authors[device]?.takeIf { it != local } ?: continue
            if (current.members.none { it.deviceId == peer && it.status == MemberStatus.ACTIVE.name }) continue
            val body = runCatching {
                JsonParser.parseString(requireNotNull(keys.open(file.name, drive.downloadBytes(file.id, PROGRESS_BYTES)))
                    .toString(Charsets.UTF_8)).asJsonObject.takeIf { it.get("deviceId")?.asString == peer }
            }.getOrNull() ?: continue
            // A report older than one received over Wi-Fi is refused there and simply skipped here.
            runCatching {
                val progress = SyncProgress.parse(body.get("progress").toString().toByteArray(Charsets.UTF_8))
                store.recordPeerProgress(peer, progress)
                val seenAt = minOf(runCatching { body.get("updatedAt").asLong }.getOrDefault(0L), now())
                if (seenAt > 0) store.recordPeerSeen(peer, seenAt)
            }
        }

        save(complete)
        return CloudPassReport(received, sent, photosReceived, photosSent, complete, queueFull)
    }

    /**
     * The next file of changes: it must start right after [after] (the drive's gap-free coverage),
     * continue the chain, stay within a removed author's [cutoff] and within the upload limits.
     */
    private fun uploadBatch(held: List<SyncOperationEntity>, after: Long, cutoff: SyncChainHead?): List<SyncOperationEntity> {
        val batch = mutableListOf<SyncOperationEntity>()
        var bytes = 2L
        for (operation in held) {
            val previous = batch.lastOrNull()
            if (operation.sequence != (previous?.sequence ?: after) + 1) break
            if (previous != null && operation.previousHash != previous.hash) break
            if (cutoff != null && !RemovalCutoff.allows(cutoff, operation.sequence, operation.hash)) break
            val size = gson.toJson(operation).toByteArray(Charsets.UTF_8).size + 1L
            if (batch.isNotEmpty() && bytes + size > UPLOAD_BYTES) break
            batch += operation
            bytes += size
            if (batch.size >= UPLOAD_OPERATIONS) break
        }
        return batch
    }

    companion object {
        const val UPLOAD_OPERATIONS = 256
        const val UPLOAD_BYTES = 2L * 1024 * 1024
        const val DOWNLOAD_OPERATIONS = 1024
        const val DOWNLOAD_BYTES = 16L * 1024 * 1024
        const val MEMBERSHIP_BYTES = 1_000_000L + 64
        const val PROGRESS_BYTES = 200_000L
        const val UPLOAD_FILES_PER_PASS = 64
        const val PHOTOS_PER_PASS = 32
        /** A relisting starts this long before the newest file seen, to cover drive clock skew. */
        private val LIST_OVERLAP: Duration = Duration.ofMinutes(10)
        private val HASH = Regex("[a-f0-9]{64}")

        /** The time to list from: a little before the newest file already seen, or null for a full listing. */
        fun relistFrom(cursor: String): String? = runCatching { Instant.parse(cursor).minus(LIST_OVERLAP).toString() }.getOrNull()

        private fun newer(candidate: String, cursor: String): Boolean {
            val left = runCatching { Instant.parse(candidate) }.getOrNull()
            val right = runCatching { Instant.parse(cursor) }.getOrNull()
            return if (left != null && right != null) left > right else candidate > cursor
        }
    }
}
