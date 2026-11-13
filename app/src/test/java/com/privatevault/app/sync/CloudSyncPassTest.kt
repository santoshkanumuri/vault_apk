package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import com.privatevault.app.sync.MembershipVectors as V

/** Cloud passes between devices of one group through a drive in memory, as Windows `cloud_sync.rs` tests. */
class CloudSyncPassTest {
    @get:Rule val folder = TemporaryFolder()
    private val keys = requireNotNull(CloudKeys.derive(Base64.getUrlDecoder().decode(V.rootSecret), V.VAULT_ID))

    @Test fun twoDevicesExchangeChangesAndPhotosThroughTheDrive() {
        val drive = MemoryDrive()
        val a = device(V.MANAGER_ID).apply { own(chain(V.MANAGER_ID, V.managerSeed, 3)) }
        val b = device(V.CLIENT_ID).apply { own(chain(V.CLIENT_ID, V.clientSeed, 2)) }
        val image = ByteArray(100_000) { (it * 7).toByte() }
        val hash = sha256(image)
        File(a.blobs, hash).apply { parentFile!!.mkdirs(); writeBytes(image) }
        a.mirror = a.mirror.copy(attachmentHashes = setOf(hash))
        b.wanted += hash

        val first = a.pass(drive)
        assertEquals(3, first.sent); assertEquals(0, first.received); assertEquals(1, first.photosSent)
        val second = b.pass(drive)
        assertEquals(2, second.sent); assertEquals(3, second.received); assertEquals(1, second.photosReceived)
        assertNotNull(b.photoBlobs.file(hash))
        assertTrue(hash !in b.wanted)
        b.apply()
        val third = a.pass(drive)
        assertEquals(0, third.sent); assertEquals(2, third.received)
        a.apply()
        assertEquals(a.held.keys, b.held.keys)

        // Nothing new: nothing moves, nothing is uploaded again, and the listing is incremental.
        val count = drive.files.size
        for (device in listOf(a, b)) {
            val idle = device.pass(drive)
            assertEquals(listOf(0, 0, 0, 0), listOf(idle.sent, idle.received, idle.photosSent, idle.photosReceived))
            assertTrue(idle.complete)
        }
        assertEquals("a changed progress report replaces its file", count, drive.files.size)
        assertNotNull(drive.listings.last())
        assertTrue(a.state.lastSyncAt > 0)

        // Each device read the other's progress report and its contact time.
        assertEquals(3L, b.peerProgress[V.MANAGER_ID]?.received?.get(V.MANAGER_ID)?.sequence)
        assertEquals(2L, a.peerProgress[V.CLIENT_ID]?.received?.get(V.CLIENT_ID)?.sequence)
        assertTrue((a.seen[V.CLIENT_ID] ?: 0L) > 0)

        // The drive sees only opaque names and sealed bytes.
        for ((file, bytes) in drive.files) {
            assertNotNull(file.name, keys.parse(file.name))
            for (secret in listOf(V.VAULT_ID, V.MANAGER_ID, V.CLIENT_ID, "cipher-")) {
                assertFalse(file.name.contains(secret))
                assertFalse("$secret is readable in ${file.name}", String(bytes, Charsets.ISO_8859_1).contains(secret))
            }
        }

        // A later change on B reaches A.
        b.own(chain(V.CLIENT_ID, V.clientSeed, 3).drop(2))
        assertEquals(1, b.pass(drive).sent)
        assertEquals(1, a.pass(drive).received)
    }

    @Test fun forgedRenamedForeignAndGappedFilesChangeNothing() {
        val drive = MemoryDrive()
        val b = device(V.CLIENT_ID)
        val real = chain(V.OTHER_ID, V.otherSeed, 1)
        val name = keys.operationsName(V.OTHER_ID, 1, 1)
        // Signed by someone who is not that device.
        drive.put(name, keys.seal(name, wire(chain(V.OTHER_ID, V.clientSeed, 1))))
        // A real file under the wrong name, a file of another group, garbage, another author's change, a gap.
        val renamed = keys.operationsName(V.OTHER_ID, 1, 2)
        drive.put(renamed, keys.seal(name, wire(real)))
        val other = requireNotNull(CloudKeys.derive(ByteArray(32) { 6 }, V.VAULT_ID))
        val foreign = other.operationsName(V.OTHER_ID, 1, 1)
        drive.put(foreign, other.seal(foreign, wire(real)))
        drive.put(name, "NVC1 garbage that is long enough to look sealed".toByteArray())
        drive.put(name, keys.seal(name, wire(chain(V.MANAGER_ID, V.managerSeed, 1))))
        val later = keys.operationsName(V.OTHER_ID, 5, 5)
        drive.put(later, keys.seal(later, wire(real)))
        assertEquals(0, b.pass(drive).received)
        assertTrue(b.queued.isEmpty())

        // The real file then arrives and is accepted next to the bad ones.
        drive.put(name, keys.seal(name, wire(real)))
        assertEquals(1, b.pass(drive, full = true).received)
        assertEquals(real.single().hash, b.queued.keys.single())
    }

    @Test fun aLongHistoryUploadsInBoundedFilesAndDownloadsInOrder() {
        val drive = MemoryDrive()
        val a = device(V.MANAGER_ID).apply { own(chain(V.MANAGER_ID, V.managerSeed, 300)) }
        assertEquals(300, a.pass(drive).sent)
        val ranges = drive.files.mapNotNull { (file, _) ->
            (keys.parse(file.name) as? CloudName.Operations)?.takeIf { it.device == keys.deviceTag(V.MANAGER_ID) }
                ?.let { it.first to it.last }
        }.sortedBy { it.first }
        assertEquals(listOf(1L to 256L, 257L to 300L), ranges)
        assertEquals(300L, a.state.covered[V.MANAGER_ID])
        val b = device(V.CLIENT_ID)
        assertEquals(300, b.pass(drive).received)
        assertEquals((1L..300L).toList(), b.queued.values.map { it.sequence }.sorted())
    }

    @Test fun anUploadStartsOnlyRightAfterWhatTheDriveHolds() {
        val drive = MemoryDrive()
        // This device no longer holds changes 1-4 (pruned); the drive has none of them.
        val a = device(V.MANAGER_ID).apply { own(chain(V.MANAGER_ID, V.managerSeed, 8).drop(4)) }
        assertEquals(0, a.pass(drive).sent)
        assertTrue(drive.files.none { (file, _) -> keys.parse(file.name) is CloudName.Operations })
    }

    @Test fun aSignedRemovalOnTheDriveIsAdoptedAndAForgedOneIsNot() {
        val drive = MemoryDrive()
        val a = device(V.MANAGER_ID)
        a.pass(drive)
        val b = device(V.CLIENT_ID)
        val history = V.clientHistory()
        val forged = history + SyncMembershipEvent.sign(V.VAULT_ID, 4, history.last().hash, MembershipAction.REMOVE,
            V.MANAGER_ID, V.CLIENT_ID, "", 2) { DeviceIdentityCrypto.sign(V.clientSeed, it) }
        val forgedName = keys.membershipName(4, forged.last().hash)
        drive.put(forgedName, keys.seal(forgedName, membershipJson(forged)))
        assertNull(b.pass(drive).removed)
        assertEquals(3, b.mirror.membershipEvents.size)

        val removed = V.removedHistory()
        val removedName = keys.membershipName(removed.size.toLong(), removed.last().hash)
        drive.put(removedName, keys.seal(removedName, membershipJson(removed)))
        val report = b.pass(drive, full = true)
        assertEquals(V.MANAGER_ID, report.removed?.issuerDeviceId)
        assertEquals(removed.size, b.mirror.membershipEvents.size)
        // The managing device adopts the same history and goes on.
        assertNull(a.pass(drive).removed)
        assertEquals(removed.size, a.mirror.membershipEvents.size)
    }

    @Test fun theManagerAnswersASignedLeaveFileAndOtherDevicesLearnTheRemoval() {
        val drive = MemoryDrive()
        val manager = device(V.MANAGER_ID)
        val other = device(V.OTHER_ID)
        manager.pass(drive)
        val request = V.leaveRequest()
        val signature = DeviceIdentityCrypto.sign(V.clientSeed, request.signingBytes())
        val b64 = Base64.getUrlEncoder().withoutPadding()
        // The client's request under another device's name, and a forged signature, change nothing.
        val wrongName = keys.leaveName(V.OTHER_ID)
        drive.put(wrongName, keys.seal(wrongName, CloudLeave.encode(request, b64.encodeToString(signature))))
        val name = keys.leaveName(V.CLIENT_ID)
        drive.put(name, keys.seal(name, CloudLeave.encode(request,
            b64.encodeToString(DeviceIdentityCrypto.sign(V.otherSeed, request.signingBytes())))))
        manager.pass(drive)
        assertEquals(3, manager.mirror.membershipEvents.size)
        // A non-managing device ignores the real request; the managing device removes the client.
        drive.put(name, keys.seal(name, CloudLeave.encode(request, b64.encodeToString(signature))))
        other.pass(drive)
        assertEquals(3, other.mirror.membershipEvents.size)
        manager.pass(drive)
        assertEquals(4, manager.mirror.membershipEvents.size)
        assertEquals(MembershipAction.REMOVE, manager.mirror.membershipEvents.last().toEvent().action)
        // The new history went to the drive in the same pass; the other device adopts it.
        assertNull(other.pass(drive, full = true).removed)
        assertEquals(4, other.mirror.membershipEvents.size)
    }

    @Test fun leaveFilesHaveOneExactShape() {
        val request = V.leaveRequest()
        val signature = DeviceIdentityCrypto.sign(V.clientSeed, request.signingBytes())
        val encoded = CloudLeave.encode(request, Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
        val (parsed, parsedSignature) = requireNotNull(CloudLeave.parse(encoded))
        assertEquals(request, parsed)
        assertTrue(signature.contentEquals(parsedSignature))
        val withExtraField = encoded.toString(Charsets.UTF_8).dropLast(1) + ",\"extra\":1}"
        assertNull(CloudLeave.parse(withExtraField.toByteArray()))
        assertNull(CloudLeave.parse("not json".toByteArray()))
        assertEquals(CloudName.Leave(keys.deviceTag(V.CLIENT_ID)), keys.parse(keys.leaveName(V.CLIENT_ID)))
    }

    private fun membershipJson(events: List<SyncMembershipEvent>) =
        com.google.gson.Gson().toJson(events.map { SyncMembershipEventEntity.from(it) }).toByteArray(Charsets.UTF_8)

    private fun wire(operations: List<SyncOperationEntity>) =
        operations.joinToString(",", "[", "]") { com.google.gson.Gson().toJson(it) }.toByteArray(Charsets.UTF_8)

    private fun device(deviceId: String): MemoryCloudStore {
        val events = V.clientHistory().map { SyncMembershipEventEntity.from(it) }
        val verified = SyncMembershipManager.verify(events.map { it.toEvent() })
        val publicKey = verified.members.single { it.deviceId == deviceId }.identityPublicKey
        return MemoryCloudStore(File(folder.root, deviceId), TransportMirror(V.VAULT_ID, deviceId, publicKey,
            V.rootSecret, verified.keyEpoch, emptyList(), verified.members, events, emptyList()))
    }

    private fun chain(device: String, seed: ByteArray, count: Int): List<SyncOperationEntity> {
        var previous = GENESIS_HASH
        return (1..count.toLong()).map { sequence ->
            val change = SyncChangeRecord.createSigned(V.VAULT_ID, device, sequence, previous, "$device-m$sequence",
                "entry", "entry-$device-$sequence", ChangeKind.UPSERT, 0, RecordVersion(mapOf(device to sequence)),
                "2026-11-12T10:00:00Z", "cipher-$sequence", "nonce") { DeviceIdentityCrypto.sign(seed, it) }
            previous = change.hash
            SyncOperationEntity(change.mutationId, change.formatVersion, change.vaultId, change.deviceId, change.sequence,
                change.previousHash, change.entityType, change.entityId, "upsert", change.baseRevision,
                change.recordVersion.toJson(), change.occurredAtUtc, change.payloadCiphertext, change.payloadNonce,
                change.deviceSignature, change.hash)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A drive in memory, shared by the devices of a test. */
    private class MemoryDrive : CloudDrive {
        val files = mutableListOf<Pair<CloudFile, ByteArray>>()
        val listings = mutableListOf<String?>()
        private var clock = 0L

        private fun stamp(): String = Instant.ofEpochSecond(1_790_000_000L + 3600 * ++clock).toString()

        fun put(name: String, bytes: ByteArray): CloudFile =
            CloudFile(UUID.randomUUID().toString(), name, bytes.size.toLong(), stamp()).also { files += it to bytes }

        override fun list(modifiedAfter: String?): List<CloudFile> {
            listings += modifiedAfter
            return files.map { it.first }.filter { modifiedAfter == null || Instant.parse(it.modified) > Instant.parse(modifiedAfter) }
        }

        override fun download(id: String, maxBytes: Long, read: (InputStream) -> Unit) {
            val bytes = files.firstOrNull { it.first.id == id }?.second ?: throw CloudException(CloudError.NOT_FOUND)
            if (bytes.size > maxBytes) throw CloudException(CloudError.OTHER)
            read(ByteArrayInputStream(bytes))
        }

        override fun upload(name: String, size: Long, content: InputStream): CloudFile {
            val bytes = content.readBytes()
            assertEquals(size, bytes.size.toLong())
            return put(name, bytes)
        }

        override fun replace(id: String, content: ByteArray): CloudFile {
            val index = files.indexOfFirst { it.first.id == id }
            if (index < 0) throw CloudException(CloudError.NOT_FOUND)
            val file = files[index].first.copy(size = content.size.toLong(), modified = stamp())
            files[index] = file to content
            return file
        }
    }

    /**
     * An in-memory [CloudSyncStore] with the production checks: [verifyIncomingOperation] and the
     * chain rule for received changes, [mirrorWithMembership] for signed membership.
     */
    private class MemoryCloudStore(directory: File, var mirror: TransportMirror) : CloudSyncStore {
        val held = LinkedHashMap<String, SyncOperationEntity>()
        val queued = LinkedHashMap<String, SyncOperationEntity>()
        val wanted = sortedSetOf<String>()
        val peerProgress = mutableMapOf<String, SyncProgress>()
        val seen = mutableMapOf<String, Long>()
        var state = CloudSyncState(enabled = true, account = "person@example.com")
        private var cached = emptyList<CloudFile>()
        val blobs = File(directory, "blobs")
        override val photoBlobs = PhotoSyncBlobs(blobs)

        fun own(operations: List<SyncOperationEntity>) = operations.forEach { held[it.hash] = it }
        /** What the unlocked app does with queued changes. */
        fun apply() { held.putAll(queued); queued.clear() }
        fun pass(drive: CloudDrive, full: Boolean = false) = CloudSyncPass(this, drive).run(full)

        private fun links(operations: Collection<SyncOperationEntity>) =
            operations.map { ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash) }

        override fun snapshot() = mirror
        override fun acceptMembershipEvents(events: List<SyncMembershipEventEntity>) {
            mirrorWithMembership(mirror, events)?.let { mirror = it }
        }
        override fun progress(): SyncProgress {
            val applied = IncomingChain.received(emptyMap(), links(held.values), emptyList())
            return SyncProgress(IncomingChain.received(applied, emptyList(), links(queued.values)), applied)
        }
        override fun recordPeerProgress(deviceId: String, progress: SyncProgress) {
            progress.validate()
            require(mirror.members.any { it.deviceId == deviceId && it.status == MemberStatus.ACTIVE.name })
            peerProgress[deviceId] = progress
        }
        override fun pending(frontier: SyncFrontier, relayRemoved: Boolean) = emptyList<SyncOperationEntity>()
        override fun queue(operation: SyncOperationEntity) {
            if (operation.hash in held || operation.hash in queued) return
            verifyIncomingOperation(mirror, operation)
            IncomingChain.admit(ChainLink(operation.deviceId, operation.sequence, operation.hash, operation.previousHash),
                progress().received, emptySet())
            queued[operation.hash] = operation
        }
        override fun missingPhotos() = wanted.filter { photoBlobs.file(it) == null }.take(32).map { it to photoBlobs.partialSize(it) }
        override fun receivedPhoto(hash: String) { wanted -= hash }
        override fun heldOperations(author: String, after: Long, limit: Int) =
            held.values.filter { it.deviceId == author && it.sequence > after }.sortedBy { it.sequence }.take(limit)
        override fun recordPeerSeen(deviceId: String, at: Long) { seen[deviceId] = maxOf(seen[deviceId] ?: 0L, at) }
        override fun cloudState() = state
        override fun updateCloudState(transform: (CloudSyncState) -> CloudSyncState) { state = transform(state) }
        override fun cloudFiles() = cached
        override fun saveCloudFiles(files: List<CloudFile>) { cached = files }
        override fun answerDriveLeave(request: LeaveRequest, signature: ByteArray): Boolean {
            val decision = LeaveRequestHandler.handleFromDrive(request, signature, mirror.membershipEvents.map { it.toEvent() },
                setOf(mirror.keyEpoch), mirror.localDeviceId, V.CREATED_AT + 60_000,
                { DeviceIdentityCrypto.sign(V.managerSeed, it) })
            if (decision !is LeaveDecision.Left || decision.append.isEmpty()) return false
            acceptMembershipEvents(mirror.membershipEvents + decision.append.map { SyncMembershipEventEntity.from(it) })
            return true
        }
    }
}
