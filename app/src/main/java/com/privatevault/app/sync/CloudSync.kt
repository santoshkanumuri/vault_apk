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
 *   nv1.<group>.p.<device>                 {deviceId, updatedAt, progress, ...} signed ([DriveProgress])
 *   nv1.<group>.b.<sha256>                 a photo blob as stored; sealed under a rotated prefix
 *   nv1.<group>.l.<device>                 {request, signature}: a signed leave request ([CloudLeave])
 *
 * cloud key = HMAC-SHA256(epoch-1 transport secret, "nuvori-cloud-v1:<vaultId>");
 * <group> and <device> = first 8 bytes, hex, of HMAC-SHA256(cloud key, "tag" / "device:<id>");
 * envelope = "NVC1" || nonce(12) || AES-256-GCM(cloud key, plaintext, AAD = file name UTF-8).
 */

/**
 * The `features` of a drive progress file: at most 32 strings of at most 64 characters. Null when the key is
 * missing (a build before 2.3.4); anything malformed throws, and the stored features stay as they are.
 */
internal fun peerFeatures(body: JsonObject): Set<String>? {
    val list = body.get("features") ?: return null
    require(list.isJsonArray && list.asJsonArray.size() <= 32)
    return list.asJsonArray.map { item ->
        require(item.isJsonPrimitive && item.asJsonPrimitive.isString)
        item.asString.also { require(it.isNotEmpty() && it.length <= 64) }
    }.toSet()
}

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
    /** Drive cleanup only ([DriveCompaction]). A file already gone is [CloudError.NOT_FOUND]. */
    fun delete(id: String) { throw CloudException(CloudError.OTHER, "delete unsupported") }
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
    /** `e.<epoch>.<recipient>`: the signed package set of a new epoch, under the previous prefix ([EpochKeys]). */
    data class Packages(val epoch: Long, val recipient: String) : CloudName
    /** `k.<device>`: a device's signed key statement ([KeyStatement]). */
    data class Statement(val device: String) : CloudName
    /** `c.<author>`: a floor marker of history compaction on the drive ([DriveCompaction]). */
    data class Floor(val author: String) : CloudName
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
    fun packageName(epoch: Long, recipientDeviceId: String) = "${prefix}e.$epoch.${deviceTag(recipientDeviceId)}"
    fun statementName(deviceId: String) = "${prefix}k.${deviceTag(deviceId)}"
    fun floorName(authorDeviceId: String) = "${prefix}c.${deviceTag(authorDeviceId)}"

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
            parts.size == 3 && parts[0] == "e" && hexField(parts[2], 16) && parts[1].length in 1..10 &&
                parts[1].all { it in '0'..'9' } && !parts[1].startsWith("0") ->
                parts[1].toLong().takeIf { it >= 2 }?.let { CloudName.Packages(it, parts[2]) }
            parts.size == 2 && parts[0] == "k" && hexField(parts[1], 16) -> CloudName.Statement(parts[1])
            parts.size == 2 && parts[0] == "c" && hexField(parts[1], 16) -> CloudName.Floor(parts[1])
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
    @SerializedName("lastSyncAt") val lastSyncAt: Long = 0L,
    /** When this device last wrote its progress report; 0 in files from before the heartbeat. */
    @SerializedName("progressWrittenAt") val progressWrittenAt: Long = 0L,
    /** When this device last cleaned up old drive files ([DriveCompaction]); 0 when never. */
    @SerializedName("driveCompactedAt") val driveCompactedAt: Long = 0L) {
    @Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
    fun normalized() = CloudSyncState(enabled, account ?: "", vaultId ?: "", covered ?: emptyMap(),
        cursor ?: "", progressDigest ?: "", lastSyncAt, progressWrittenAt, driveCompactedAt)

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
    /** A peer wrote its progress to the drive at [at], at the end of a finished drive pass. */
    fun recordPeerSeen(deviceId: String, at: Long)
    /** What a peer announced in its drive progress file, as in a Wi-Fi handshake. */
    fun recordPeerFeatures(deviceId: String, features: Set<String>) {}
    fun cloudState(): CloudSyncState
    fun updateCloudState(transform: (CloudSyncState) -> CloudSyncState)
    fun cloudFiles(): List<CloudFile>
    fun saveCloudFiles(files: List<CloudFile>)
    /** Managing device: answers a leave request found on the drive. True when it signed new events. */
    fun answerDriveLeave(request: LeaveRequest, signature: ByteArray): Boolean = false
    /** Key rotation ([EpochKeys]): this device's statement, and what the drive brings. */
    fun ownKeyStatement(): KeyStatement? = null
    fun acceptKeyStatement(statement: KeyStatement): Boolean = false
    fun acceptKeyPackages(sets: List<PackageSet>): Boolean = false
    fun keyPackages(): List<PackageSet> = emptyList()
    /** Signs this device's drive progress report with its identity ([DriveProgress]); null leaves it unsigned. */
    fun signProgress(bytes: ByteArray): ByteArray? = null
}

/**
 * A drive progress report signed by its device's identity (H2 of the key-rotation review). Its
 * progress decides what may be deleted (compaction, the relay folder, drive cleanup) and its features
 * open gates, so only a report the member signed for this group and for the newest prefix counts.
 * The body keeps `progress` as an object for older builds and adds `progressText` (the exact JSON
 * signed), `prefixEpoch` and `signature`. Signing bytes (vectors `progressFile` in epoch-keys-v1.json):
 * `nuvori-drive-progress-v1\n<vaultId>\n<deviceId>\n<prefixEpoch>\n<updatedAt>\n<keyEpoch>\n<sha256 hex of
 * progressText>\n<features sorted, joined by ','>`. Windows `cloud_sync.rs` `drive_progress`.
 */
internal object DriveProgress {
    data class Verified(val progress: SyncProgress, val features: Set<String>?, val keyEpoch: Long)

    fun signingBytes(vaultId: String, deviceId: String, prefixEpoch: Long, updatedAt: Long, keyEpoch: Long,
        progressText: String, features: Collection<String>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(progressText.toByteArray(Charsets.UTF_8)).hex()
        return ("nuvori-drive-progress-v1\n$vaultId\n$deviceId\n$prefixEpoch\n$updatedAt\n$keyEpoch\n$digest\n" +
            features.sorted().joinToString(",")).toByteArray(Charsets.UTF_8)
    }

    fun body(vaultId: String, deviceId: String, prefixEpoch: Long, updatedAt: Long, keyEpoch: Long, progressText: String,
        features: Collection<String>, signer: (ByteArray) -> ByteArray?): JsonObject = JsonObject().apply {
        addProperty("deviceId", deviceId)
        addProperty("updatedAt", updatedAt)
        addProperty("keyEpoch", keyEpoch)
        addProperty("prefixEpoch", prefixEpoch)
        add("progress", JsonParser.parseString(progressText))
        addProperty("progressText", progressText)
        // Older builds ignore these keys. A peer that syncs only through the drive learns the features here.
        add("features", com.google.gson.JsonArray().apply { features.sorted().forEach(::add) })
        signer(signingBytes(vaultId, deviceId, prefixEpoch, updatedAt, keyEpoch, progressText, features))?.let {
            addProperty("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(it))
        }
    }

    /** The report when [deviceId] (identity [identityPublicKey]) signed it for [vaultId] under the prefix of [prefixEpoch]; else null. */
    fun verify(body: JsonObject, vaultId: String, deviceId: String, identityPublicKey: String, prefixEpoch: Long): Verified? = runCatching {
        fun number(name: String) = body.getAsJsonPrimitive(name).also { require(it.isNumber) }.asBigDecimal.longValueExact()
        fun text(name: String) = body.getAsJsonPrimitive(name).also { require(it.isString) }.asString
        require(text("deviceId") == deviceId && number("prefixEpoch") == prefixEpoch)
        val progressText = text("progressText")
        val features = peerFeatures(body)
        val signature = requireNotNull(EpochKeys.strictDecode(text("signature")))
        val keyEpoch = number("keyEpoch")
        require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(identityPublicKey), signingBytes(vaultId, deviceId,
            prefixEpoch, number("updatedAt"), keyEpoch, progressText, features.orEmpty()), signature))
        Verified(SyncProgress.parse(progressText.toByteArray(Charsets.UTF_8)), features, keyEpoch)
    }.getOrNull()
}

internal data class CloudPassReport(val received: Int = 0, val sent: Int = 0, val photosReceived: Int = 0,
    val photosSent: Int = 0, val complete: Boolean = true, val queueFull: Boolean = false,
    val removed: RemovalNotice.Removed? = null,
    /** This device waits for its new keys and only read ([TransportMirror.stranded]). */
    val stranded: Boolean = false)

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
        // One prefix per rotated epoch ([EpochKeys.driveSecret]): new files go to the newest; files under
        // every prefix this device holds a secret for are read, so a device one epoch behind still meets us.
        val root = Base64.getUrlDecoder().decode(mirror.transportSecret)
        var random = mirror.randomSecrets().mapValues { Base64.getUrlDecoder().decode(it.value) }
        fun keysAt(epoch: Long): CloudKeys = requireNotNull(CloudKeys.derive(EpochKeys.driveSecret(root, epoch, random).second,
            mirror.vaultId)) { "Invalid sync keys" }
        // The epoch of each prefix: 1 for the root, else the rotated epoch of its random secret.
        var ring: List<Pair<Long, CloudKeys>> = emptyList()
        var keys = keysAt(mirror.keyEpoch)
        var keysEpoch = 1L
        fun rebuild(current: TransportMirror) {
            random = current.randomSecrets().mapValues { Base64.getUrlDecoder().decode(it.value) }
            keysEpoch = EpochKeys.driveSecret(root, current.keyEpoch, random).first
            keys = keysAt(current.keyEpoch)
            ring = (listOf(keysEpoch to keys) + (listOf(1L) + random.keys).sortedDescending().map { it to keysAt(it) })
                .distinctBy { it.second.prefix }
        }
        rebuild(mirror)
        fun keysFor(name: String): CloudKeys? = ring.firstOrNull { name.startsWith(it.second.prefix) }?.second
        fun epochOf(name: String): Long? = ring.firstOrNull { name.startsWith(it.second.prefix) }?.first
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
                cursor = saved.cursor, progressDigest = saved.progressDigest, progressWrittenAt = saved.progressWrittenAt,
                driveCompactedAt = saved.driveCompactedAt,
                lastSyncAt = if (finished) now() else if (current.vaultId == saved.vaultId) current.lastSyncAt else 0L) }
        }
        fun parsed(): List<Pair<CloudFile, CloudName>> = files.mapNotNull { file -> keysFor(file.name)?.parse(file.name)?.let { file to it } }
        fun open(file: CloudFile, maxBytes: Long): ByteArray? = keysFor(file.name)?.open(file.name, drive.downloadBytes(file.id, maxBytes))

        // 1. List.
        val since = if (!full && files.isNotEmpty()) relistFrom(state.cursor) else null
        var listed = drive.list(since).filter { keysFor(it.name)?.parse(it.name) != null }
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
            val tagged = ring.flatMap { (_, ringKeys) -> members.map { ringKeys.deviceTag(it.deviceId) to it } }.toMap()
            for (file in listed) {
                val name = keysFor(file.name)?.parse(file.name) as? CloudName.Leave ?: continue
                val member = tagged[name.device] ?: continue
                val leave = runCatching { CloudLeave.parse(requireNotNull(open(file, CloudLeave.MAX_BYTES))) }.getOrNull() ?: continue
                if (leave.first.deviceId != member.deviceId) continue
                if (runCatching { store.answerDriveLeave(leave.first, leave.second) }.getOrDefault(false))
                    events = requireNotNull(store.snapshot()).membershipEvents
            }
        }

        // 2. Membership: take a longer signed history. Publishing ours waits until after 2b, so the
        // packages of a new epoch are on the drive before the history that names it.
        val histories = parsed().mapNotNull { (file, name) -> (name as? CloudName.Membership)?.let { it.count to file } }
            .sortedByDescending { it.first }
        for ((_, file) in histories.filter { it.first > events.size }.take(3)) {
            val downloaded = runCatching { MembershipWire.parseHistory(requireNotNull(open(file, MEMBERSHIP_BYTES))) }.getOrNull() ?: continue
            // After "Mark as lost" the lost manager's later events count for nothing, a REMOVE included.
            val remote = LostManagerFreeze.keptEvents(mirror.lostManager, downloaded)
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
            break
        }

        // 2b. Key rotation: statements (k.) and sealed packages (e.) ([EpochKeys]). Packages to this
        // device are under any held prefix; the manager puts each under the prefix of the epoch before,
        // and checks on every pass that a valid copy is really there (a removed device can delete it).
        runCatching { store.ownKeyStatement() }.getOrNull()?.let { statement ->
            val name = keys.statementName(local)
            // The managing device's statement tells survivors that its REMOVEs make new keys, so it
            // checks that a copy is really there (not only in its cached list).
            val present = files.filter { it.name == name }.let { copies -> if (!managing) copies.isNotEmpty() else copies.any { file ->
                runCatching { keys.open(name, drive.downloadBytes(file.id, PROGRESS_BYTES)) != null }.getOrElse { failure ->
                    if ((failure as? CloudException)?.error == CloudError.NOT_FOUND) files.removeAll { it.id == file.id }
                    else if (failure is CloudException) throw failure
                    false
                }
            } }
            if (!present)
                remember(drive.uploadBytes(name, keys.seal(name, gson.toJson(statement).toByteArray(Charsets.UTF_8))))
        }
        var learnedKeys = false
        for ((file, name) in parsed()) {
            val own = keysFor(file.name)?.deviceTag(local)
            if (name is CloudName.Statement && name.device != own) {
                val statement = runCatching {
                    gson.fromJson(requireNotNull(open(file, PROGRESS_BYTES)).toString(Charsets.UTF_8), KeyStatement::class.java)
                }.getOrNull() ?: continue
                runCatching { store.acceptKeyStatement(statement) }
            } else if (name is CloudName.Packages && name.recipient == own &&
                store.keyPackages().none { it.epoch == name.epoch }) {
                val set = runCatching {
                    gson.fromJson(requireNotNull(open(file, PROGRESS_BYTES)).toString(Charsets.UTF_8), PackageSet::class.java)
                }.getOrNull() ?: continue
                if (runCatching { store.acceptKeyPackages(listOf(set)) }.getOrDefault(false)) learnedKeys = true
            }
        }
        val verifiedNow = runCatching { SyncMembershipManager.verify(events.map { it.toEvent() }) }.getOrNull()
        for (set in store.keyPackages()) {
            verifiedNow ?: break
            val removeIndex = verifiedNow.events.indexOfFirst { it.action == MembershipAction.REMOVE && it.keyEpoch == set.epoch }
            val remove = verifiedNow.events.getOrNull(removeIndex)
            if (remove?.issuerDeviceId != local || set.epoch <= verifiedNow.keyEpoch - TransportEpochs.RETAINED_PREVIOUS_EPOCHS) continue
            val previous = keysAt(set.epoch - 1)
            // A marker goes to every survivor of that REMOVE; a set to each device it holds a package for.
            val recipients = set.packages.keys.ifEmpty {
                SyncMembershipManager.verify(verifiedNow.events.take(removeIndex + 1)).members
                    .filter { it.status == MemberStatus.ACTIVE.name && it.deviceId != local }.map { it.deviceId }.toSet()
            }
            for (recipient in recipients.sorted()) {
                val name = previous.packageName(set.epoch, recipient)
                val present = files.filter { it.name == name }.any { file ->
                    val copy = try { previous.open(name, drive.downloadBytes(file.id, PROGRESS_BYTES)) }
                        catch (gone: CloudException) {
                            if (gone.error != CloudError.NOT_FOUND) throw gone
                            files.removeAll { it.id == file.id }
                            null
                        }
                    copy != null && runCatching { gson.fromJson(copy.toString(Charsets.UTF_8), PackageSet::class.java) == set }.getOrDefault(false)
                }
                if (!present) remember(drive.uploadBytes(name, previous.seal(name, gson.toJson(set).toByteArray(Charsets.UTF_8))))
            }
        }

        // Keys learned in 2b apply in this same pass: nothing below goes under an older prefix.
        val current = requireNotNull(store.snapshot())
        rebuild(current)
        // Files under the new prefix were not readable when the folder was listed: list it again whole.
        if (learnedKeys) {
            listed = drive.list(null).filter { keysFor(it.name)?.parse(it.name) != null }
            files.clear()
            listed.forEach(::remember)
        }
        // Waiting for a package ([TransportMirror.stranded]): this pass only reads. Nothing is uploaded
        // under the old prefix (which the removed device can read) until the new keys arrive.
        val stranded = current.stranded()

        // 2c. Publish the history: under the newest prefix, and once under the previous one up to the
        // REMOVE that started this epoch only, so the removed device learns of it and nothing after.
        if (!stranded && events.isNotEmpty()) {
            if (histories.none { it.first == events.size.toLong() && it.second.name.startsWith(keys.prefix) } &&
                files.none { it.name == keys.membershipName(events.size.toLong(), events.last().hash) }) {
                val name = keys.membershipName(events.size.toLong(), events.last().hash)
                remember(drive.uploadBytes(name, keys.seal(name, gson.toJson(events).toByteArray(Charsets.UTF_8))))
            }
            if (keysEpoch >= 2) {
                val previous = keysAt(keysEpoch - 1)
                val notice = MembershipWire.historyForEpoch(events, keysEpoch - 1)
                val name = previous.membershipName(notice.size.toLong(), notice.last().hash)
                if (previous.prefix != keys.prefix && files.none { it.name == name })
                    remember(drive.uploadBytes(name, previous.seal(name, gson.toJson(notice).toByteArray(Charsets.UTF_8))))
            }
        }

        // New keys arrived: the next pass lists again with them.
        var complete = !learnedKeys
        var received = 0
        var sent = 0
        val authors = ring.flatMap { (_, ringKeys) -> current.members.map { ringKeys.deviceTag(it.deviceId) to it.deviceId } }.toMap()
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
                    JsonParser.parseString(requireNotNull(keysFor(file.name)?.open(file.name, sealed)).toString(Charsets.UTF_8)).asJsonArray
                }.getOrNull()?.takeIf { it.size().toLong() == last - first + 1 && it.size() <= DOWNLOAD_OPERATIONS }
                if (operations == null) { unusable += file.id; continue }
                var storedCount = 0
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
                    catch (_: SyncQueueFullException) { queueFull = true; received += storedCount; break@downloads }
                    catch (_: Exception) {
                        // Changes before this one are verified and kept; the rest of the file is not used.
                        unusable += file.id
                        break
                    }
                    storedCount++
                }
                received += storedCount
                if (storedCount == 0 || (store.progress().received[author]?.sequence ?: 0L) <= head) unusable += file.id
            }
        }
        if (queueFull) complete = false

        // 4. Upload what the drive lacks, for every author this device holds changes of.
        val cutoffs = RemovalCutoff.cutoffsOf(current.membershipEvents)
        // Floor markers ([DriveCompaction]): coverage starts after a marker this device counts, so a
        // deleted range is never uploaded again. A marker above this device's own floor is not counted.
        val markers = mutableMapOf<String, Long>()
        parsed().forEach { (file, name) ->
            if (name !is CloudName.Floor) return@forEach
            val author = authors[name.author] ?: return@forEach
            val marker = runCatching { gson.fromJson(requireNotNull(open(file, DriveCompaction.MARKER_BYTES)).toString(Charsets.UTF_8),
                DriveCompaction.Marker::class.java) }.getOrNull() ?: return@forEach
            if (marker.author != author || marker.floor < 0 || current.members.none { it.deviceId == marker.writer }) return@forEach
            markers[author] = maxOf(markers[author] ?: 0L, marker.floor)
        }
        val ownFloors = DriveCompaction.floors(current, store.progress().applied.mapValues { it.value.sequence })
        val counted = markers.mapValues { (author, floor) -> DriveCompaction.countedFloor(floor, ownFloors[author] ?: 0L) }
        val covered = (segments.keys + counted.keys).associateWith { author ->
            DriveCompaction.coverage(segments[author].orEmpty().map { it.first to it.second }, counted[author] ?: 0L)
        }.toMutableMap()
        var uploadedFiles = 0
        uploads@ for (member in current.members.sortedBy { it.deviceId }) {
            if (stranded) break
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

        // 5. Photo blobs. Under the root prefix a blob goes as stored (it is already ciphertext; older
        // builds read it so). Under a rotated prefix it is sealed like every other file, so a blob of an
        // older epoch's key (which a removed device holds) is never readable from the drive.
        val cloudBlobs = parsed().mapNotNull { (file, name) -> (name as? CloudName.Blob)?.let { it.hash to file } }.toMap()
        var photosReceived = 0
        for ((hash, _) in store.missingPhotos().take(PHOTOS_PER_PASS)) {
            val file = cloudBlobs[hash] ?: continue
            val sealedBlob = (epochOf(file.name) ?: 1L) >= 2
            if (file.size !in 28..(if (sealedBlob) SEALED_BLOB_BYTES + BLOB_ENVELOPE else PhotoSyncBlobs.MAX_PHOTO_BYTES + 28)) continue
            try {
                if (sealedBlob) {
                    val plain = requireNotNull(keysFor(file.name)?.open(file.name, drive.downloadBytes(file.id, file.size)))
                    store.photoBlobs.receive(ByteArrayInputStream(plain), PhotoBlobRef(hash, plain.size.toLong()))
                } else drive.download(file.id, file.size) { input -> store.photoBlobs.receive(input, PhotoBlobRef(hash, file.size)) }
                store.receivedPhoto(hash)
                photosReceived++
            } catch (failure: CloudException) {
                if (failure.error != CloudError.OTHER && failure.error != CloudError.NOT_FOUND) throw failure
                runCatching { store.photoBlobs.discardPartial(hash) }
            } catch (full: PhotoStorageFullException) { throw full }
            catch (_: Exception) { runCatching { store.photoBlobs.discardPartial(hash) } }
        }
        val sealBlobs = keysEpoch >= 2
        val toUpload = if (stranded) emptyList() else current.attachmentHashes.filter { it.matches(HASH) && it !in cloudBlobs }.sorted()
            .asSequence().mapNotNull { hash -> store.photoBlobs.file(hash)?.let { hash to it } }
            // A blob too large to seal in memory travels over Wi-Fi only.
            .filter { (_, blob) -> !sealBlobs || blob.length() <= SEALED_BLOB_BYTES }
            .take(PHOTOS_PER_PASS + 1).toList()
        if (toUpload.size > PHOTOS_PER_PASS) complete = false
        var photosSent = 0
        for ((hash, blob) in toUpload.take(PHOTOS_PER_PASS)) {
            val name = keys.blobName(hash)
            remember(if (sealBlobs) drive.uploadBytes(name, keys.seal(name, blob.readBytes()))
                else blob.inputStream().use { drive.upload(name, blob.length(), it) })
            photosSent++
        }

        // 6. Progress: tell the other devices what this one holds, and read what they hold. The
        // report is also rewritten after [PROGRESS_HEARTBEAT_MS] unchanged, so its `updatedAt`
        // tells the other devices that this one still syncs (Home warns after 3 days of silence).
        // It is signed ([DriveProgress]); a device waiting for keys writes none under the old prefix.
        val ownProgress = store.progress().encode()
        // The features are part of the digest, so an app update that adds one rewrites the file at once.
        val digest = MessageDigest.getInstance("SHA-256").digest(ownProgress + "|$keysEpoch|".toByteArray(Charsets.UTF_8) +
            SyncFeatures.ANNOUNCED.joinToString(",", prefix = "|").toByteArray(Charsets.UTF_8)).hex()
        val ownName = keys.progressName(local)
        val ownFile = files.firstOrNull { it.name == ownName }
        val writtenAgo = now() - state.progressWrittenAt
        if (!stranded && (digest != state.progressDigest || ownFile == null || writtenAgo !in 0 until PROGRESS_HEARTBEAT_MS)) {
            val writtenAt = now()
            // The newest epoch whose keys this device holds: a manager keeps its packages until then.
            val keyEpoch = (listOf(1L) + current.randomSecrets().keys).max()
            val body = DriveProgress.body(current.vaultId, local, keysEpoch, writtenAt, keyEpoch,
                ownProgress.toString(Charsets.UTF_8), SyncFeatures.ANNOUNCED.toList(), store::signProgress)
            val sealed = keys.seal(ownName, body.toString().toByteArray(Charsets.UTF_8))
            val file = if (ownFile == null) drive.uploadBytes(ownName, sealed) else try { drive.replace(ownFile.id, sealed) }
                catch (gone: CloudException) {
                    if (gone.error != CloudError.NOT_FOUND) throw gone
                    files.removeAll { it.id == ownFile.id }
                    drive.uploadBytes(ownName, sealed)
                }
            remember(file)
            state = state.copy(progressDigest = digest, progressWrittenAt = writtenAt)
        }
        for (file in listed) {
            val device = (keysFor(file.name)?.parse(file.name) as? CloudName.Progress)?.device ?: continue
            val peer = authors[device]?.takeIf { it != local } ?: continue
            val member = current.members.firstOrNull { it.deviceId == peer && it.status == MemberStatus.ACTIVE.name } ?: continue
            val body = runCatching {
                JsonParser.parseString(requireNotNull(open(file, PROGRESS_BYTES))
                    .toString(Charsets.UTF_8)).asJsonObject.takeIf { it.get("deviceId")?.asString == peer }
            }.getOrNull() ?: continue
            // Progress counts only when the peer signed it for the newest prefix: it drives what may be
            // deleted (compaction, the relay folder, drive cleanup) and which features are announced. A
            // report of an older prefix, or unsigned (an older build), is unknown progress: nothing more
            // is deleted because of it. A report older than one received over Wi-Fi is refused there.
            val signed = if (epochOf(file.name) == keysEpoch)
                DriveProgress.verify(body, current.vaultId, peer, member.identityPublicKey, keysEpoch) else null
            if (signed != null) {
                runCatching { store.recordPeerProgress(peer, signed.progress) }
                runCatching { signed.features?.let { store.recordPeerFeatures(peer, it) } }
            }
            // A sealed report under the peer's own name proves it wrote to the drive, even when its
            // progress is refused. Contact times never move backward, so an old report changes nothing.
            runCatching {
                val seenAt = minOf(runCatching { body.get("updatedAt").asLong }.getOrDefault(0L), now())
                if (seenAt > 0) store.recordPeerSeen(peer, seenAt)
            }
        }

        // 7. Drive cleanup ([DriveCompaction]), at most once a day, after a complete pass, and only
        // when every active member reads floor markers.
        if (!stranded && complete && DriveCompaction.gateOpen(current) &&
            now() - state.driveCompactedAt !in 0 until DriveCompaction.INTERVAL_MS) {
            var deletes = 0
            for ((author, list) in segments.toSortedMap()) {
                if (deletes >= DriveCompaction.MAX_DELETES) break
                val floor = ownFloors[author] ?: continue
                val (newFloor, gone) = DriveCompaction.cleanup(list.map { it.first to it.second }, counted[author] ?: 0L, floor)
                if (gone.isEmpty()) continue
                // The marker first: a stop before the deletes leaves extra files, never a gap.
                val name = keys.floorName(author)
                val hash = store.heldOperations(author, newFloor - 1, 1).firstOrNull { it.sequence == newFloor }?.hash.orEmpty()
                val body = gson.toJson(DriveCompaction.Marker(author, newFloor, hash, local, now())).toByteArray(Charsets.UTF_8)
                val existing = files.firstOrNull { it.name == name }
                remember(if (existing == null) drive.uploadBytes(name, keys.seal(name, body))
                    else runCatching { drive.replace(existing.id, keys.seal(name, body)) }.getOrElse { drive.uploadBytes(name, keys.seal(name, body)) })
                for ((first, last) in gone) {
                    if (deletes >= DriveCompaction.MAX_DELETES) break
                    list.filter { it.first == first && it.second == last }.forEach { (_, _, file) ->
                        try { drive.delete(file.id) } catch (failure: CloudException) { if (failure.error != CloudError.NOT_FOUND) throw failure }
                        files.removeAll { it.id == file.id }
                        deletes++
                    }
                }
            }
            // Membership files: the newest two stay; the newest holds the whole history.
            parsed().mapNotNull { (file, name) -> (name as? CloudName.Membership)?.let { it.count to file } }
                .filter { it.second.name.startsWith(keys.prefix) }.sortedByDescending { it.first }.drop(2)
                .take((DriveCompaction.MAX_DELETES - deletes).coerceAtLeast(0)).forEach { (_, file) ->
                    try { drive.delete(file.id) } catch (failure: CloudException) { if (failure.error != CloudError.NOT_FOUND) throw failure }
                    files.removeAll { it.id == file.id }
                }
            state = state.copy(driveCompactedAt = now())
        }

        save(complete)
        root.fill(0)
        return CloudPassReport(received, sent, photosReceived, photosSent, complete, queueFull, stranded = stranded)
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
        /** An unchanged progress report is rewritten after this long, as a sign of life. */
        const val PROGRESS_HEARTBEAT_MS = 60 * 60 * 1000L
        const val UPLOAD_FILES_PER_PASS = 64
        const val PHOTOS_PER_PASS = 32
        /** Blobs under a rotated prefix are sealed in memory; larger ones travel over Wi-Fi only. */
        const val SEALED_BLOB_BYTES = 32L * 1024 * 1024
        /** "NVC1", nonce and tag around a sealed blob. */
        const val BLOB_ENVELOPE = 4L + 12 + 16
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
