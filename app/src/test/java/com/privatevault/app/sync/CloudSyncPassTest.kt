package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private companion object { const val HOUR = 60 * 60 * 1000L }
    @get:Rule val folder = TemporaryFolder()
    private val keys = requireNotNull(CloudKeys.derive(Base64.getUrlDecoder().decode(V.rootSecret), V.VAULT_ID))

    @Test fun driveCleanupWaitsForEveryActiveMemberAndNothingIsUploadedAgain() {
        val drive = MemoryDrive()
        val history = chain(V.MANAGER_ID, V.managerSeed, 300)
        val a = device(V.MANAGER_ID).apply { own(history) }
        assertEquals(300, a.pass(drive).sent)
        fun operationFiles() = drive.files.map { it.first.name }.filter { keys.parse(it) is CloudName.Operations }
        assertEquals(2, operationFiles().size)
        val all = SyncProgress(mapOf(V.MANAGER_ID to SyncChainHead(300, history.last().hash)),
            mapOf(V.MANAGER_ID to SyncChainHead(300, history.last().hash)))
        val both = listOf(DriveCompaction.FEATURE)
        // Every member announced the feature; the client applied everything, the third device nothing (offline): nothing goes.
        a.mirror = a.mirror.copy(peerFeatures = mapOf(V.CLIENT_ID to both, V.OTHER_ID to both), peerProgress = mapOf(V.CLIENT_ID to all))
        a.pass(drive)
        assertEquals(2, operationFiles().size)
        // An older build anywhere (it would count coverage from 1): still nothing.
        a.state = a.state.copy(driveCompactedAt = 0)
        a.mirror = a.mirror.copy(peerFeatures = mapOf(V.CLIENT_ID to both),
            peerProgress = mapOf(V.CLIENT_ID to all, V.OTHER_ID to all))
        a.pass(drive)
        assertEquals(2, operationFiles().size)
        // The offline device is removed: a removed member pins nothing, so the files go, after the floor marker.
        a.state = a.state.copy(driveCompactedAt = 0)
        fun withoutOther(mirror: TransportMirror) = mirror.copy(members = mirror.members.map {
            if (it.deviceId == V.OTHER_ID) it.copy(status = MemberStatus.REVOKED.name) else it })
        a.mirror = withoutOther(a.mirror).copy(peerFeatures = mapOf(V.CLIENT_ID to both), peerProgress = mapOf(V.CLIENT_ID to all))
        a.pass(drive)
        assertEquals(emptyList<String>(), operationFiles())
        val markerName = keys.floorName(V.MANAGER_ID)
        val marker = com.google.gson.Gson().fromJson(String(requireNotNull(keys.open(markerName,
            drive.files.single { it.first.name == markerName }.second))), DriveCompaction.Marker::class.java)
        assertEquals(listOf(300L, V.MANAGER_ID), listOf(marker.floor, marker.author))
        // A member that counts the marker does not upload the deleted range again.
        val b = device(V.CLIENT_ID).apply { own(history) }
        b.mirror = withoutOther(b.mirror).copy(peerProgress = mapOf(V.MANAGER_ID to all))
        assertEquals(0, b.pass(drive).sent)
        assertEquals(emptyList<String>(), operationFiles())
        // A member whose own floor is lower ignores the marker and uploads again: never a gap.
        val c = device(V.CLIENT_ID).apply { own(history) }
        assertEquals(300, c.pass(drive).sent)
    }

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

    @Test fun anIdleDeviceRewritesItsProgressAsAHeartbeat() {
        val drive = MemoryDrive()
        var clock = 1_790_000_000_000L
        val a = device(V.MANAGER_ID).apply { now = { clock } }
        val b = device(V.CLIENT_ID).apply { now = { clock } }
        val progressOfB = keys.progressName(V.CLIENT_ID)
        fun modifiedOfB() = drive.files.single { it.first.name == progressOfB }.first.modified
        b.pass(drive); a.pass(drive)
        val first = modifiedOfB()
        assertEquals(clock, a.seen[V.CLIENT_ID])
        assertEquals(clock, b.state.progressWrittenAt)

        // Within the heartbeat an unchanged report is not uploaded again.
        val start = clock
        clock += CloudSyncPass.PROGRESS_HEARTBEAT_MS - 1
        b.pass(drive); a.pass(drive)
        assertEquals(first, modifiedOfB())
        assertEquals(start, a.seen[V.CLIENT_ID])

        // After it, the same report is rewritten and the other device sees a new contact time.
        clock += 1
        b.pass(drive); a.pass(drive)
        assertNotEquals(first, modifiedOfB())
        assertEquals(clock, a.seen[V.CLIENT_ID])
        assertEquals(clock, b.state.progressWrittenAt)

        // A clock that moved backward also rewrites, so a wrong future time cannot stop the heartbeat.
        val beforeRollback = modifiedOfB()
        clock -= 10_000
        b.pass(drive)
        assertNotEquals(beforeRollback, modifiedOfB())
        assertEquals(clock, b.state.progressWrittenAt)
    }

    @Test fun aDriveOnlyPeerLearnsTheOtherDevicesFeatures() {
        val drive = MemoryDrive()
        val a = device(V.MANAGER_ID)
        val b = device(V.CLIENT_ID)
        b.pass(drive); a.pass(drive)
        assertEquals(SyncFeatures.ANNOUNCED.toSet(), a.features[V.CLIENT_ID])
        assertTrue(SyncFeatures.SITE_ICONS in a.features.getValue(V.CLIENT_ID))
        // The sealed report lists them sorted, next to the fields older builds read.
        val name = keys.progressName(V.CLIENT_ID)
        val body = com.google.gson.JsonParser.parseString(requireNotNull(keys.open(name,
            drive.files.single { it.first.name == name }.second)).toString(Charsets.UTF_8)).asJsonObject
        // keyEpoch (epoch-keys-v1): the newest epoch whose keys this device holds; 1 before any rotation.
        // The report is signed by the device ([DriveProgress]); older builds read deviceId, updatedAt and progress.
        assertEquals(setOf("deviceId", "updatedAt", "keyEpoch", "prefixEpoch", "progress", "progressText", "features", "signature"),
            body.keySet())
        assertEquals(1L, body.get("keyEpoch").asLong)
        assertEquals(1L, body.get("prefixEpoch").asLong)
        assertEquals(SyncFeatures.ANNOUNCED.sorted(), body.getAsJsonArray("features").map { it.asString })
    }

    @Test fun aRefusedProgressReportStillRecordsTheContactTime() {
        val drive = MemoryDrive()
        val clock = 1_790_000_000_000L
        val a = device(V.MANAGER_ID).apply { now = { clock }; refuseProgress = true }
        val b = device(V.CLIENT_ID).apply { now = { clock } }
        b.pass(drive); a.pass(drive)
        assertNull(a.peerProgress[V.CLIENT_ID])
        assertEquals(clock, a.seen[V.CLIENT_ID])
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
        val remove = manager.mirror.membershipEvents.last().toEvent()
        assertEquals(MembershipAction.REMOVE, remove.action)
        // M2: a REMOVE answered from a leave request (signed while locked) gets keys when the managing
        // device unlocks. Until then the manager holds what it would send, the new history included.
        assertTrue(manager.mirror.stranded())
        assertNull(other.pass(drive, full = true).removed)
        assertEquals(3, other.mirror.membershipEvents.size)
        // Unlocked: EpochKeyRotation.coverRemovals; here the gate is closed (no statements), so a signed marker.
        manager.mirror = manager.mirror.copy(keyPackages = mapOf("2" to PackageSet.sign(V.VAULT_ID, 2, remove.hash, emptyMap()) {
            DeviceIdentityCrypto.sign(V.managerSeed, it) }))
        assertFalse(manager.mirror.stranded())
        manager.pass(drive)
        // The new history is on the drive now; the other device adopts it and, with the marker, waits for nothing.
        assertNull(other.pass(drive, full = true).removed)
        assertEquals(4, other.mirror.membershipEvents.size)
        assertFalse(other.mirror.stranded())
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

    @Test fun driveProgressMatchesTheSharedVector() {
        val vectors = com.google.gson.JsonParser.parseString(requireNotNull(javaClass.classLoader?.getResource(
            "sync-vectors/epoch-keys-v1.json")).readText()).asJsonObject
        val file = vectors.getAsJsonObject("progressFile")
        val vault = vectors.get("vaultId").asString
        val text = file.get("progressText").asString
        val features = file.getAsJsonArray("features").map { it.asString }
        val bytes = DriveProgress.signingBytes(vault, "dev-pc", 2, file.get("updatedAt").asLong, 2, text, features)
        assertEquals(file.get("signingHex").asString, bytes.joinToString("") { "%02x".format(it) })
        val seed = vectors.getAsJsonObject("devices").getAsJsonObject("dev-pc").get("ed25519Seed").asString
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val body = DriveProgress.body(vault, "dev-pc", 2, file.get("updatedAt").asLong, 2, text, features) { DeviceIdentityCrypto.sign(seed, it) }
        assertEquals(file.get("signature").asString, body.get("signature").asString)
        val public = file.get("devicePublic").asString
        assertEquals(3L, DriveProgress.verify(body, vault, "dev-pc", public, 2)?.progress?.applied?.get("dev-pc")?.sequence)
        // Another prefix, device, vault or a changed report: not counted.
        assertNull(DriveProgress.verify(body, vault, "dev-pc", public, 1))
        assertNull(DriveProgress.verify(body, vault, "dev-tablet", public, 2))
        assertNull(DriveProgress.verify(body, "other", "dev-pc", public, 2))
        assertNull(DriveProgress.verify(body.deepCopy().apply { addProperty("progressText", text.replace("[1,", "[3,")) },
            vault, "dev-pc", public, 2))
        assertNull(DriveProgress.verify(body.deepCopy().apply { remove("signature") }, vault, "dev-pc", public, 2))
    }

    /**
     * H2: a removed device holds the root cloud key, so it can write a progress file for an active
     * member under the root prefix. Unsigned or forged, it is unknown progress: it never raises a
     * floor (which would let compaction delete history the member still needs) nor opens a gate.
     */
    @Test fun forgedAndUnsignedProgressNeverCountsNorTheContactTime() {
        val drive = MemoryDrive()
        val a = device(V.MANAGER_ID)
        val inflated = SyncProgress(mapOf(V.CLIENT_ID to SyncChainHead(900, "ab".repeat(32))),
            mapOf(V.CLIENT_ID to SyncChainHead(900, "ab".repeat(32))))
        val name = keys.progressName(V.CLIENT_ID)
        val at = System.currentTimeMillis() - 1000
        // Unsigned, as an older build (or the removed device) writes it.
        val unsigned = com.google.gson.JsonObject().apply {
            addProperty("deviceId", V.CLIENT_ID); addProperty("updatedAt", at)
            add("progress", com.google.gson.JsonParser.parseString(inflated.encode().toString(Charsets.UTF_8)))
            add("features", com.google.gson.JsonArray().apply { add(DriveCompaction.FEATURE) })
        }
        drive.put(name, keys.seal(name, unsigned.toString().toByteArray()))
        a.pass(drive)
        assertNull(a.peerProgress[V.CLIENT_ID])
        assertNull(a.features[V.CLIENT_ID])
        // It does not make the member look seen either (Devices "Last seen", "Up to date").
        assertNull(a.seen[V.CLIENT_ID])
        // Signed by another member's key: refused too.
        val forged = DriveProgress.body(V.VAULT_ID, V.CLIENT_ID, 1, at + 1, 1, inflated.encode().toString(Charsets.UTF_8),
            listOf(DriveCompaction.FEATURE)) { DeviceIdentityCrypto.sign(V.otherSeed, it) }
        drive.put(name, keys.seal(name, forged.toString().toByteArray()))
        a.pass(drive)
        assertNull(a.peerProgress[V.CLIENT_ID])
        // The member's own signed report counts.
        val b = device(V.CLIENT_ID)
        b.pass(drive); a.pass(drive)
        assertEquals(0L, a.peerProgress[V.CLIENT_ID]?.applied?.get(V.CLIENT_ID)?.sequence ?: 0L)
        assertEquals(SyncFeatures.ANNOUNCED.toSet(), a.features[V.CLIENT_ID])
    }

    /**
     * End to end on a drive in memory: the manager removes OTHER with new keys; OTHER (which keeps the
     * root key and a Google sign-in) deletes and tampers with the survivor's `e.` file and forges a
     * progress file. The survivor learns the REMOVE, holds everything it would send (H1), the manager
     * finds its package missing on the drive and uploads it again, and from then on the survivor writes
     * only under the new prefix (M1). OTHER can open nothing the survivor wrote after the REMOVE.
     */
    @Test fun aRemovedDeviceThatTampersWithPackagesAndProgressCantKeepASurvivorStrandedOrReadIt() {
        val drive = MemoryDrive()
        val manager = device(V.MANAGER_ID)
        val survivor = device(V.CLIENT_ID).apply { own(chain(V.CLIENT_ID, V.clientSeed, 2)) }
        // Statements travel first: both publish theirs, and read the other's.
        survivor.pass(drive); manager.pass(drive); survivor.pass(drive)
        assertTrue(manager.mirror.keyStatements.containsKey(V.CLIENT_ID))
        assertTrue(survivor.mirror.keyStatements.containsKey(V.MANAGER_ID))
        val beforeRemoval = drive.files.size
        val rotation = rotate(manager)
        val newKeys = requireNotNull(CloudKeys.derive(rotation.secret, V.VAULT_ID))
        manager.pass(drive)
        val packageName = keys.packageName(2, V.CLIENT_ID)
        assertTrue(drive.files.any { it.first.name == packageName })
        // Only the REMOVE goes under the old prefix: the history there ends with it.
        val oldHistory = drive.files.filter { (file, _) -> (keys.parse(file.name) as? CloudName.Membership)?.count == 4L }
        assertEquals(1, oldHistory.size)
        assertTrue(drive.files.any { newKeys.parse(it.first.name) is CloudName.Membership })

        // OTHER, removed: deletes the package, plants a changed copy (non-canonical base64url, the
        // same bytes) and a forged progress report for the survivor under the old prefix.
        drive.files.removeAll { it.first.name == packageName }
        val lenient = rotation.set.copy(packages = rotation.set.packages.mapValues { (_, text) ->
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            text.dropLast(1) + alphabet[alphabet.indexOf(text.last()) or 1] })
        assertNotEquals(rotation.set.packages, lenient.packages)
        drive.put(packageName, keys.seal(packageName, com.google.gson.Gson().toJson(lenient).toByteArray()))

        // The survivor learns the REMOVE but has no valid package: it reads, and sends nothing.
        val stranded = survivor.pass(drive, full = true)
        assertTrue(stranded.stranded)
        assertEquals(4, survivor.mirror.membershipEvents.size)
        assertEquals(0, stranded.sent)
        survivor.own(chain(V.CLIENT_ID, V.clientSeed, 3).drop(2))
        assertEquals(0, survivor.pass(drive).sent)
        assertFalse(survivor.mirror.transportSecrets.containsKey("2"))

        // The manager checks the drive itself (its cached list still names the deleted file) and uploads it again.
        manager.pass(drive)
        assertEquals(1, drive.files.count { (file, bytes) -> file.name == packageName &&
            keys.open(packageName, bytes)?.let { com.google.gson.Gson().fromJson(String(it), PackageSet::class.java) } == rotation.set })
        // The pass that opens the package already uploads under the new prefix (M1).
        val learned = survivor.pass(drive)
        assertFalse(learned.complete)
        assertFalse(learned.stranded)
        assertTrue(survivor.mirror.transportSecrets.containsKey("2"))
        assertEquals(1, learned.sent)
        assertEquals(0, survivor.pass(drive).sent)

        // Nothing the survivor wrote after it learned of the REMOVE is under a prefix OTHER can open.
        val survivorTag = keys.deviceTag(V.CLIENT_ID)
        val oldPrefixAfter = drive.files.drop(beforeRemoval).map { it.first.name }.filter { name ->
            when (val parsed = keys.parse(name)) {
                is CloudName.Operations -> parsed.device == survivorTag && parsed.last >= 3
                else -> false
            }
        }
        assertEquals(emptyList<String>(), oldPrefixAfter)
        assertTrue(drive.files.any { (file, _) -> (newKeys.parse(file.name) as? CloudName.Operations)?.let {
            it.device == newKeys.deviceTag(V.CLIENT_ID) && it.last == 3L } == true })
        // OTHER's forged report under the old prefix never counts; the survivor's signed one under the new prefix does.
        val forged = DriveProgress.body(V.VAULT_ID, V.CLIENT_ID, 1, System.currentTimeMillis(), 2,
            SyncProgress(mapOf(V.CLIENT_ID to SyncChainHead(99, "ab".repeat(32))), mapOf(V.CLIENT_ID to SyncChainHead(99, "ab".repeat(32))))
                .encode().toString(Charsets.UTF_8), emptyList()) { DeviceIdentityCrypto.sign(V.otherSeed, it) }
        val oldProgress = keys.progressName(V.CLIENT_ID)
        drive.put(oldProgress, keys.seal(oldProgress, forged.toString().toByteArray()))
        manager.pass(drive)
        assertEquals(3L, manager.peerProgress[V.CLIENT_ID]?.applied?.get(V.CLIENT_ID)?.sequence)
    }

    /** A photo blob under a rotated prefix is sealed: a blob of an older epoch's key is not readable from the drive. */
    @Test fun blobsUnderARotatedPrefixAreSealedAndReadBack() {
        val vectors = com.google.gson.JsonParser.parseString(requireNotNull(javaClass.classLoader?.getResource(
            "sync-vectors/epoch-keys-v1.json")).readText()).asJsonObject
        val blob = vectors.getAsJsonObject("blobFile")
        fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val epoch2 = requireNotNull(CloudKeys.derive(hex(vectors.get("transportSecretHex").asString), vectors.get("vaultId").asString))
        assertEquals(blob.get("sealedHex").asString, epoch2.sealWithNonce(blob.get("name").asString, hex(blob.get("blobHex").asString),
            hex(blob.get("envelopeNonceHex").asString)).joinToString("") { "%02x".format(it) })

        val drive = MemoryDrive()
        val manager = device(V.MANAGER_ID)
        val survivor = device(V.CLIENT_ID)
        survivor.pass(drive); manager.pass(drive); survivor.pass(drive)
        val rotation = rotate(manager)
        val image = ByteArray(50_000) { (it * 3).toByte() }
        val hash = sha256(image)
        File(manager.blobs, hash).apply { parentFile!!.mkdirs(); writeBytes(image) }
        manager.mirror = manager.mirror.copy(attachmentHashes = setOf(hash))
        assertEquals(1, manager.pass(drive).photosSent)
        val newKeys = requireNotNull(CloudKeys.derive(rotation.secret, V.VAULT_ID))
        val (file, bytes) = drive.files.single { (file, _) -> newKeys.parse(file.name) is CloudName.Blob }
        assertFalse(bytes.contentEquals(image))
        assertTrue(newKeys.open(file.name, bytes)!!.contentEquals(image))
        survivor.wanted += hash
        survivor.pass(drive, full = true); survivor.pass(drive)
        assertTrue(survivor.photoBlobs.file(hash)!!.readBytes().contentEquals(image))
    }

    /** L1: after "Mark as lost", a REMOVE the lost manager signs later (found on the drive) is not adopted. */
    @Test fun aFrozenSurvivorIgnoresARemovalSignedByTheLostManager() {
        val drive = MemoryDrive()
        val b = device(V.CLIENT_ID)
        b.mirror = b.mirror.copy(lostManager = LostManagerFreeze.Freeze(V.MANAGER_ID, 3, 0, GENESIS_HASH))
        val removed = V.removedHistory()
        val name = keys.membershipName(removed.size.toLong(), removed.last().hash)
        drive.put(name, keys.seal(name, membershipJson(removed)))
        assertNull(b.pass(drive, full = true).removed)
        assertEquals(3, b.mirror.membershipEvents.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Sync audit fixes (C1, M6, L3): the drive's copy of every author stays readable.
    // ---------------------------------------------------------------------------------------------

    private fun applied(sequence: Long, history: List<SyncOperationEntity>) = SyncChainHead(sequence, history[(sequence - 1).toInt()].hash)

    private fun marker(drive: MemoryDrive, author: String): DriveCompaction.Marker? {
        val name = keys.floorName(author)
        val bytes = drive.files.firstOrNull { it.first.name == name }?.second ?: return null
        return com.google.gson.Gson().fromJson(String(requireNotNull(keys.open(name, bytes))), DriveCompaction.Marker::class.java)
    }

    @Test fun historyTrimmedWhileDriveWasOffGoesOnFromAFloorMarker() {
        // Wi-Fi only for a while: every device applied A's 20 changes and trimmed 1..10. Then Drive is turned on.
        val history = chain(V.MANAGER_ID, V.managerSeed, 21)
        val all = SyncProgress(mapOf(V.MANAGER_ID to applied(20, history)), mapOf(V.MANAGER_ID to applied(20, history)))
        fun trimmed(id: String) = device(id).apply {
            own(history.subList(10, 20)); base = mapOf(V.MANAGER_ID to applied(10, history))
            mirror = mirror.copy(peerProgress = (listOf(V.MANAGER_ID, V.CLIENT_ID, V.OTHER_ID) - id).associateWith { all })
        }
        val a = trimmed(V.MANAGER_ID)
        val c = trimmed(V.OTHER_ID)
        val drive = MemoryDrive()
        val first = a.pass(drive)
        assertEquals(10, first.sent)
        assertTrue(first.complete)
        assertEquals(listOf(10L, V.MANAGER_ID), marker(drive, V.MANAGER_ID)!!.let { listOf(it.floor, it.author) })
        // A's next change reaches the device that only meets A through the drive.
        a.own(listOf(history[20]))
        assertEquals(1, a.pass(drive).sent)
        val received = c.pass(drive)
        assertEquals(1, received.received)
        assertTrue(received.complete)
        assertEquals(21L, c.progress().received[V.MANAGER_ID]?.sequence)
        // A device whose own floor is lower doesn't count the marker but holds nothing below it either: no gap upload.
        assertEquals(0, a.pass(drive).sent)
    }

    @Test fun aMarkerWaitsUntilEveryActiveMemberAppliedBelowIt() {
        val history = chain(V.MANAGER_ID, V.managerSeed, 20)
        val a = device(V.MANAGER_ID).apply {
            own(history.subList(10, 20)); base = mapOf(V.MANAGER_ID to applied(10, history))
            // The third device applied only 5 of A's changes: 6..10 may still be needed from the drive.
            mirror = mirror.copy(peerProgress = mapOf(
                V.CLIENT_ID to SyncProgress(mapOf(V.MANAGER_ID to applied(20, history)), mapOf(V.MANAGER_ID to applied(20, history))),
                V.OTHER_ID to SyncProgress(mapOf(V.MANAGER_ID to applied(5, history)), mapOf(V.MANAGER_ID to applied(5, history)))))
        }
        val drive = MemoryDrive()
        val report = a.pass(drive)
        assertEquals(0, report.sent)
        assertNull(marker(drive, V.MANAGER_ID))
        // This device's own changes can't reach the drive: the pass is not complete, and it says which.
        assertFalse(report.complete)
        assertEquals(listOf(V.MANAGER_ID), report.stuckAuthors)
        assertEquals(0L, a.state.lastSyncAt)
    }

    @Test fun anEmptiedDriveFolderIsRefilledFromWhatTheDevicesHold() {
        val history = chain(V.MANAGER_ID, V.managerSeed, 21)
        val drive = MemoryDrive()
        val a = device(V.MANAGER_ID).apply { own(history.take(20)) }
        val c = device(V.OTHER_ID)
        assertEquals(20, a.pass(drive).sent)
        assertEquals(20, c.pass(drive).received)
        c.apply()
        // History trimmed below 11 on both, then the app folder is emptied ("Delete hidden app data", another account).
        val all = SyncProgress(mapOf(V.MANAGER_ID to applied(20, history)), mapOf(V.MANAGER_ID to applied(20, history)))
        listOf(a, c).forEach { device ->
            history.take(10).forEach { device.held.remove(it.hash) }
            device.base = mapOf(V.MANAGER_ID to applied(10, history))
            device.mirror = device.mirror.copy(peerProgress = (listOf(V.MANAGER_ID, V.CLIENT_ID, V.OTHER_ID) - device.mirror.localDeviceId)
                .associateWith { all })
        }
        drive.files.clear()
        a.own(listOf(history[20]))
        val refill = a.pass(drive, full = true)
        // The drive holds less than before: the unlocked app is asked to rebuild the relay folder.
        assertTrue(refill.relayRefill)
        assertTrue(a.state.relayRefill)
        assertEquals(11, refill.sent)
        assertEquals(10L, marker(drive, V.MANAGER_ID)!!.floor)
        val received = c.pass(drive, full = true)
        assertEquals(1, received.received)
        assertEquals(21L, c.progress().received[V.MANAGER_ID]?.sequence)
    }

    @Test fun anUnreadableFileIsUploadedAgainForTheDeviceStuckBelowIt() {
        val history = chain(V.MANAGER_ID, V.managerSeed, 15)
        val drive = MemoryDrive()
        // Sealed correctly, but 9 changes under a name for 10: no reader can use it, and writers count it as coverage.
        val bad = keys.operationsName(V.MANAGER_ID, 1, 10)
        drive.put(bad, keys.seal(bad, wire(history.take(9))))
        val start = 1_790_000_000_000L
        val a = device(V.MANAGER_ID).apply { own(history); now = { start + 30 * HOUR } }
        val c = device(V.OTHER_ID).apply { now = { start + 30 * HOUR } }
        assertEquals(5, a.pass(drive).sent)
        val stuck = c.pass(drive)
        assertEquals(0, stuck.received)
        assertFalse(stuck.complete)
        assertEquals(listOf(V.MANAGER_ID), stuck.stuckAuthors)
        // The writer sees in the reader's signed report that it stays below a file the drive got long ago.
        val again = a.pass(drive, full = true)
        assertEquals(10, again.sent)
        val recovered = c.pass(drive)
        assertEquals(15, recovered.received)
        assertTrue(recovered.complete)
        // At most once a day per author.
        assertEquals(0, a.pass(drive, full = true).sent)
    }

    @Test fun anOversizedFileIsSkippedAndThePassStillUploads() {
        val drive = MemoryDrive()
        val big = keys.operationsName(V.MANAGER_ID, 1, 1)
        drive.put(big, ByteArray((CloudSyncPass.DOWNLOAD_BYTES + 1).toInt()))
        val c = device(V.OTHER_ID).apply { own(chain(V.OTHER_ID, V.otherSeed, 3)) }
        val report = c.pass(drive)
        assertEquals(3, report.sent)
        assertFalse(report.complete)
        assertEquals(1, report.unusableFiles)
    }

    @Test fun aLateListedFileIsFoundByThePeriodicFullListing() {
        val history = chain(V.MANAGER_ID, V.managerSeed, 3)
        val drive = MemoryDrive()
        var clock = 1_790_000_000_000L
        val c = device(V.OTHER_ID).apply { own(chain(V.OTHER_ID, V.otherSeed, 1)); now = { clock } }
        c.pass(drive)
        val cursor = Instant.parse(c.state.cursor)
        // A file the listing shows later than its time says, beyond the 10-minute overlap.
        val late = keys.operationsName(V.MANAGER_ID, 1, 3)
        drive.putAt(late, keys.seal(late, wire(history)), cursor.minusSeconds(11 * 60).toString())
        assertEquals(0, c.pass(drive).received)
        clock += CloudSyncPass.FULL_LIST_INTERVAL_MS
        assertEquals(3, c.pass(drive).received)
        assertEquals(null, drive.listings.last())
    }

    @Test fun aPassThatStopsPartWayKeepsTheOldListingCursor() {
        val drive = MemoryDrive()
        val c = device(V.OTHER_ID).apply { own(chain(V.OTHER_ID, V.otherSeed, 1)) }
        c.pass(drive)
        val cursor = c.state.cursor
        c.own(chain(V.OTHER_ID, V.otherSeed, 2).drop(1))
        drive.put(keys.operationsName(V.MANAGER_ID, 5, 5), ByteArray(10))
        drive.failUploads = true
        assertEquals(CloudError.OFFLINE, (runCatching { c.pass(drive) }.exceptionOrNull() as CloudException).error)
        assertEquals(cursor, c.state.cursor)
        drive.failUploads = false
        assertEquals(1, c.pass(drive).sent)
        assertNotEquals(cursor, c.state.cursor)
    }

    @Test fun driveRefillPlansAndReuploadsByTheSharedRules() {
        assertEquals(DriveRefill.Step.NONE, DriveRefill.plan(10, 11, 20, 0))
        assertEquals(DriveRefill.Step.NONE, DriveRefill.plan(10, null, 20, 0))
        assertEquals(DriveRefill.Step.MARK, DriveRefill.plan(0, 11, 10, 0))
        assertEquals(DriveRefill.Step.STUCK, DriveRefill.plan(0, 11, 9, 0))
        // An existing marker is never lowered.
        assertEquals(DriveRefill.Step.STUCK, DriveRefill.plan(0, 11, 20, 10))
        assertEquals(DriveRefill.Step.MARK, DriveRefill.plan(5, 11, 20, 5))
        val file = DriveRefill.Segment(1, 10, 1_000L)
        val day = DriveRefill.REUPLOAD_INTERVAL_MS
        val stuck = DriveRefill.PeerReport(0, 1_000L + DriveRefill.STUCK_AFTER_MS)
        assertEquals(0L to 10L, DriveRefill.reupload(15, listOf(stuck), listOf(file), 0, 10 * day))
        // Not yet stuck long enough, already re-uploaded today, a gap rather than a file, or nobody behind.
        assertNull(DriveRefill.reupload(15, listOf(stuck.copy(reportedAt = 2_000L)), listOf(file), 0, 10 * day))
        assertNull(DriveRefill.reupload(15, listOf(stuck), listOf(file), 10 * day - 1, 10 * day))
        assertNull(DriveRefill.reupload(15, listOf(stuck), listOf(DriveRefill.Segment(2, 10, 1_000L)), 0, 10 * day))
        assertNull(DriveRefill.reupload(15, listOf(stuck.copy(received = 15)), listOf(file), 0, 10 * day))
    }

    private class Rotation(val set: PackageSet, val secret: ByteArray, val remove: SyncMembershipEvent)

    /** The manager removes OTHER at epoch 2 with a random S(2) and a package for the client, as [EpochKeyRotation.rotate]. */
    private fun rotate(manager: MemoryCloudStore): Rotation {
        val history = manager.mirror.membershipEvents.map { it.toEvent() }
        val remove = SyncMembershipEvent.sign(V.VAULT_ID, history.size + 1L, history.last().hash, MembershipAction.REMOVE,
            V.MANAGER_ID, V.OTHER_ID, "", 2) { DeviceIdentityCrypto.sign(V.managerSeed, it) }
        val secret = ByteArray(32) { 0x5C }
        val statement = manager.mirror.keyStatements.getValue(V.CLIENT_ID)
        val sealed = EpochKeys.seal(V.VAULT_ID, 2, V.CLIENT_ID, requireNotNull(statement.publicKey()), secret, ByteArray(32) { 0x6D })
        val set = PackageSet.sign(V.VAULT_ID, 2, remove.hash, mapOf(V.CLIENT_ID to sealed)) { DeviceIdentityCrypto.sign(V.managerSeed, it) }
        manager.acceptMembershipEvents(manager.mirror.membershipEvents + SyncMembershipEventEntity.from(remove))
        manager.mirror = manager.mirror.copy(transportSecrets = mapOf("2" to Base64.getUrlEncoder().withoutPadding().encodeToString(secret)),
            keyPackages = mapOf("2" to set))
        return Rotation(set, secret, remove)
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
            V.rootSecret, verified.keyEpoch, emptyList(), verified.members, events, emptyList())).apply {
            seed = when (deviceId) { V.MANAGER_ID -> V.managerSeed; V.CLIENT_ID -> V.clientSeed; else -> V.otherSeed }
        }
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
        /** Uploads fail as when the connection drops. */
        var failUploads = false

        private fun stamp(): String = Instant.ofEpochSecond(1_790_000_000L + 3600 * ++clock).toString()

        fun put(name: String, bytes: ByteArray): CloudFile =
            CloudFile(UUID.randomUUID().toString(), name, bytes.size.toLong(), stamp()).also { files += it to bytes }

        /** A file whose listing time is [modified]: one that shows up in the listing later than its time says. */
        fun putAt(name: String, bytes: ByteArray, modified: String): CloudFile =
            CloudFile(UUID.randomUUID().toString(), name, bytes.size.toLong(), modified).also { files += it to bytes }

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
            if (failUploads) throw CloudException(CloudError.OFFLINE)
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
        override fun delete(id: String) {
            if (!files.removeAll { it.first.id == id }) throw CloudException(CloudError.NOT_FOUND)
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
        val features = mutableMapOf<String, Set<String>>()
        var state = CloudSyncState(enabled = true, account = "person@example.com")
        private var cached = emptyList<CloudFile>()
        val blobs = File(directory, "blobs")
        override val photoBlobs = PhotoSyncBlobs(blobs)
        var now: () -> Long = System::currentTimeMillis
        /** Applied heads below what [held] starts at: history this device trimmed ([HistoryCompaction]). */
        var base: Map<String, SyncChainHead> = emptyMap()
        /** Refuses every peer progress report, as a report older than one received over Wi-Fi is. */
        var refuseProgress = false
        /** This device's Ed25519 seed: it signs progress reports and key statements. */
        var seed: ByteArray? = null

        fun own(operations: List<SyncOperationEntity>) = operations.forEach { held[it.hash] = it }
        /** What the unlocked app does with queued changes. */
        fun apply() { held.putAll(queued); queued.clear() }
        fun pass(drive: CloudDrive, full: Boolean = false) = CloudSyncPass(this, drive, now).run(full)

        private fun links(operations: Collection<SyncOperationEntity>) =
            operations.map { ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash) }

        override fun snapshot() = mirror
        override fun acceptMembershipEvents(events: List<SyncMembershipEventEntity>) {
            mirrorWithMembership(mirror, events)?.let { mirror = it }
        }
        override fun progress(): SyncProgress {
            val applied = IncomingChain.received(base, links(held.values), emptyList())
            return SyncProgress(IncomingChain.received(applied, emptyList(), links(queued.values)), applied)
        }
        override fun recordPeerProgress(deviceId: String, progress: SyncProgress) {
            check(!refuseProgress) { "Older than the Wi-Fi report" }
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
        override fun recordPeerFeatures(deviceId: String, features: Set<String>) { this.features[deviceId] = features }
        override fun cloudState() = state
        override fun updateCloudState(transform: (CloudSyncState) -> CloudSyncState) { state = transform(state) }
        override fun cloudFiles() = cached
        override fun saveCloudFiles(files: List<CloudFile>) { cached = files }
        override fun signProgress(bytes: ByteArray): ByteArray? = seed?.let { DeviceIdentityCrypto.sign(it, bytes) }
        override fun ownKeyStatement(): KeyStatement? = seed?.let { own ->
            KeyStatement.create(mirror.vaultId, mirror.localDeviceId, EpochKeys.x25519Public(EpochKeys.x25519Private(own))) {
                DeviceIdentityCrypto.sign(own, it) }
        }?.also { mirror = mirror.copy(keyStatements = mirror.keyStatements + (mirror.localDeviceId to it)) }
        override fun acceptKeyStatement(statement: KeyStatement): Boolean {
            val member = mirror.members.firstOrNull { it.deviceId == statement.deviceId && it.status == MemberStatus.ACTIVE.name } ?: return false
            if (!statement.valid(mirror.vaultId, member.identityPublicKey)) return false
            mirror = mirror.copy(keyStatements = mirror.keyStatements + (statement.deviceId to statement))
            return true
        }
        /** As [LockedSyncStore.acceptKeyPackages]: keeps valid sets, never replaces one, opens its own package. */
        override fun acceptKeyPackages(sets: List<PackageSet>): Boolean {
            val verified = SyncMembershipManager.verify(mirror.membershipEvents.map { it.toEvent() })
            var learned = false
            for (set in sets) {
                if (!EpochKeyRotation.validSet(set, mirror.vaultId, verified) || mirror.keyPackages.containsKey(set.epoch.toString())) continue
                mirror = mirror.copy(keyPackages = mirror.keyPackages + (set.epoch.toString() to set))
                val sealed = set.packages[mirror.localDeviceId]?.let(EpochKeys::strictDecode) ?: continue
                val opened = EpochKeys.open(mirror.vaultId, set.epoch, mirror.localDeviceId, EpochKeys.x25519Private(seed!!), sealed) ?: continue
                mirror = mirror.copy(transportSecrets = mirror.transportSecrets + (set.epoch.toString() to
                    Base64.getUrlEncoder().withoutPadding().encodeToString(opened.first)))
                learned = true
            }
            return learned
        }
        override fun keyPackages() = mirror.keyPackages.values.sortedBy { it.epoch }
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
