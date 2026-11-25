package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.privatevault.app.appearance.Appearance
import com.privatevault.app.data.*
import com.privatevault.app.passkeys.PasskeyCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes the cross-app vector `enrollment-realistic-v1.json`: the exact enrollment snapshot v4 this
 * phone sends to a Windows PC after a realistic history, made by the real writers, appliers, removal,
 * key rotation, compaction and export code. Windows `checkpoint.rs` imports it end to end; the JVM test
 * [RealisticEnrollmentVectorTest] checks it with this app's own validator.
 *
 * The history: a vault item from before the group (untracked, adopted at export); three other devices
 * (a tablet, the owner's old PC and a laptop) with entries, folders, a passkey, tombstones, an open and
 * a resolved conflict, website icons, sign-in methods (one deleted), appearance and device names; the
 * tablet removed with a cutoff (epoch 2, new keys); the old PC leaving by a signed request (epoch 3, new
 * keys); changes written under epochs 2 and 3; history compaction. The joiner is that PC after a reset:
 * a fresh identity. `next` is the phone's first change after the join.
 *
 * Regenerate: `scripts/sync-vectors/enrollment_realistic.ps1 <path to card_app_windows>` (emulator only).
 */
class RealisticEnrollmentFixtureTest {
    private class TestContext(base: Context, private val root: File, private val prefix: String) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "nobackup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$prefix-$name", mode)
    }

    private val vault = "realistic-vault"
    private val vaultKey = ByteArray(32) { 21 }
    private val pairingKey = ByteArray(32) { 7 }
    private val joinerSeed = ByteArray(32) { 0x42 }
    private val joinerId = "8d5e2f60-3c1a-4b7e-9f42-6a1d0c9b7e55"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    /** What Nuvori for Windows 2.3.1 announces in its pairing hello (`protocol::SYNC_FEATURES`). */
    private val windowsFeatures = setOf("removal-cutoff-v1", "appearance-v1", "site-icons-v1", "checkpoint-enroll-v1",
        "sign-in-methods-v1", "merge-join-v1", "epoch-keys-v1", "history-compaction-v1")

    /** Another member that signs its own changes, as Windows or another phone does. */
    private inner class Author(val identity: AndroidDeviceIdentityStore, private val phone: VaultDatabase) {
        val id: String get() = identity.getOrCreate().deviceId
        private var sequence = 0L
        private var head = GENESIS_HASH

        /** The next version of an entity after what the phone holds, or after [base] (a stale read: a conflict). */
        suspend fun version(type: String, entityId: String, base: RecordVersion? = null): RecordVersion {
            val known = base ?: phone.syncDao().recordState(type, entityId)?.let { RecordVersion.parse(it.recordVersionJson) }
                ?: RecordVersion(emptyMap())
            return RecordVersion(known.counters + (id to (known.counters[id] ?: 0L) + 1L))
        }

        fun sign(key: EpochKey, type: String, entityId: String, kind: ChangeKind, payload: String, version: RecordVersion,
            at: String): SyncOperationEntity {
            val mutation = UUID.randomUUID().toString()
            val sealed = SyncPayloadCrypto.encrypt(key, type, vault, mutation, entityId, payload.toByteArray(Charsets.UTF_8))
            val change = SyncChangeRecord.createSigned(vault, id, sequence + 1, head, mutation, type, entityId, kind, 0L,
                version, at, sealed.ciphertext, sealed.nonce, identity::sign)
            sequence = change.sequence
            head = change.hash
            return with(SyncPayloadCrypto) { change.toEntity() }
        }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Test
    fun aPcThatLeftAndWasResetGetsASnapshotWindowsCanImport() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "realistic-enrollment-${UUID.randomUUID()}").apply { mkdirs() }
        val contexts = listOf("phone", "tablet", "old-pc", "laptop").associateWith {
            TestContext(base, File(root, it), UUID.randomUUID().toString())
        }
        val phoneContext = contexts.getValue("phone")
        val phone = Room.inMemoryDatabaseBuilder(phoneContext, VaultDatabase::class.java).build()
        val identities = contexts.mapValues { AndroidDeviceIdentityStore(it.value) }
        val phoneIdentity = identities.getValue("phone")
        val store = LockedSyncStore(phoneContext, File(root, "phone-transport"))
        SyncedSiteIconCache.open("realistic-enrollment", emptyList())
        try {
            val me = phoneIdentity.getOrCreate()
            phone.dao().saveSettings(VaultSettings(vaultId = vault))
            phone.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership(vault, me.deviceId, "Owner's phone",
                me.publicKeyBase64Url, MemberStatus.ACTIVE, me.deviceId, 1, 1)))
            val entries = LocalEntryChangeWriter(phone, phoneIdentity)
            // An item from before the group: it has no version until the export adopts it.
            entries.save(VaultEntry(id = "before-group", type = EntryType.NOTE, title = "Written before sync"), emptySet(), vaultKey)
            phone.prepareSyncGroup(phoneIdentity)
            val contentKey = phone.syncContentKey(vaultKey)
            val epoch1 = EpochKey(1, contentKey)

            // Three other members, added by the phone.
            val tablet = Author(identities.getValue("tablet"), phone)
            val oldPc = Author(identities.getValue("old-pc"), phone)
            val laptop = Author(identities.getValue("laptop"), phone)
            var events = phone.syncDao().membershipEvents(vault).map { it.toEvent() }
            for ((author, name) in listOf(tablet to "Tablet", oldPc to "Personal PC", laptop to "Laptop")) {
                val identity = author.identity.getOrCreate()
                events = events + SyncMembershipEvent.sign(vault, events.size + 1L, events.last().hash, MembershipAction.ADD,
                    me.deviceId, identity.deviceId, identity.publicKeyBase64Url, 1, phoneIdentity::sign)
                phone.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(events.last()))
                SyncMembershipManager.verify(events).members.first { it.deviceId == identity.deviceId }
                    .let { phone.syncDao().upsertMembership(it.copy(displayName = name)) }
            }
            val applier = IncomingEntryChangeApplier(phone, null, phoneContext, announceRemoteChanges = false)
            suspend fun deliver(change: SyncOperationEntity) = applier.apply(change, vaultKey)

            // The phone's own items: a folder, logins, notes, a passkey and its name.
            val logins = VaultGroup(id = "g-logins", name = "Logins", folderType = EntryType.PASSWORD)
            LocalGroupChangeWriter(phone, phoneIdentity).save(logins, vaultKey)
            fun login(id: String, title: String) = VaultEntry(id = id, type = EntryType.PASSWORD, title = title,
                primaryValue = "me@example.com", secondaryValue = "secret-$id")
            for (id in 1..6) entries.save(login("e$id", "Login $id"), setOf(logins.id), vaultKey)
            repeat(8) { entries.save(login("e1", "Login 1 edit $it"), setOf(logins.id), vaultKey) }
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            LocalPasskeyChangeWriter(phone, phoneIdentity).save(VaultPasskey(PasskeyCrypto.encode(ByteArray(32) { 1 }),
                "example.com", PasskeyCrypto.encode(ByteArray(16) { 2 }), "me", "Me",
                PasskeyCrypto.encode(pair.private.encoded), PasskeyCrypto.encode(pair.public.encoded), 1_700_000_000_000), vaultKey)
            LocalDeviceNameChangeWriter(phone, phoneIdentity).save("Owner's phone", vaultKey)

            // The tablet: an edit, a new item, a delete (a tombstone) and its name.
            fun entryPayload(entry: VaultEntry, groups: Set<String> = setOf(logins.id)) = MergeJoin.entryPayload(entry, groups).toString()
            val deleted = """{"deleted":true}"""
            assertEquals(IncomingResult.APPLIED, deliver(tablet.sign(epoch1, "entry", "e1", ChangeKind.UPSERT,
                entryPayload(login("e1", "Login 1 from the tablet")), tablet.version("entry", "e1"), "2026-11-01T10:00:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(tablet.sign(epoch1, "entry", "e7", ChangeKind.UPSERT,
                entryPayload(login("e7", "Tablet login")), tablet.version("entry", "e7"), "2026-11-01T10:01:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(tablet.sign(epoch1, "entry", "e2", ChangeKind.DELETE, deleted,
                tablet.version("entry", "e2"), "2026-11-01T10:02:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(tablet.sign(epoch1, "device_name", tablet.id, ChangeKind.UPSERT,
                """{"name":"Tablet"}""", tablet.version("device_name", tablet.id), "2026-11-01T10:03:00Z")))

            // An open conflict on e4: the tablet edits from a version the phone has moved past.
            assertEquals(IncomingResult.APPLIED, deliver(tablet.sign(epoch1, "entry", "e4", ChangeKind.UPSERT,
                entryPayload(login("e4", "Login 4 tablet")), tablet.version("entry", "e4"), "2026-11-01T10:04:00Z")))
            val stale4 = RecordVersion.parse(requireNotNull(phone.syncDao().recordState("entry", "e4")).recordVersionJson)
            entries.save(login("e4", "Login 4 phone"), setOf(logins.id), vaultKey)
            assertEquals(IncomingResult.CONFLICT, deliver(tablet.sign(epoch1, "entry", "e4", ChangeKind.UPSERT,
                entryPayload(login("e4", "Login 4 tablet again")), tablet.version("entry", "e4", stale4), "2026-11-01T10:05:00Z")))

            // The old PC: edits, a new item, website icons, sign-in methods (one deleted later), appearance, its name.
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, "entry", "e3", ChangeKind.UPSERT,
                entryPayload(login("e3", "Login 3 from the PC")), oldPc.version("entry", "e3"), "2026-11-02T09:00:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, "entry", "e8", ChangeKind.UPSERT,
                entryPayload(login("e8", "PC login")), oldPc.version("entry", "e8"), "2026-11-02T09:01:00Z")))
            val icon = SiteIconSyncRules.entityId(contentKey, "github.com")
            fun iconPayload(color: Int) = """{"siteIcon":{"site":"github.com","png":"${Base64.getEncoder().encodeToString(png(color))}"}}"""
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, SiteIconSyncRules.ENTITY_TYPE, icon, ChangeKind.UPSERT,
                iconPayload(Color.BLUE), oldPc.version(SiteIconSyncRules.ENTITY_TYPE, icon), "2026-11-02T09:02:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, SiteIconSyncRules.ENTITY_TYPE, icon, ChangeKind.UPSERT,
                iconPayload(Color.RED), oldPc.version(SiteIconSyncRules.ENTITY_TYPE, icon), "2026-11-02T09:03:00Z")))
            fun method(site: String, provider: String, account: String) = SignInMethodSyncRules.entityId(contentKey, site, provider, account)
            fun methodPayload(site: String, provider: String, account: String, count: Int) =
                """{"signInMethod":{"site":"$site","provider":"$provider","account":"$account","firstUsedAt":1700000000000,"lastUsedAt":${1700000000000 + count},"useCount":$count}}"""
            val github = method("github.com", "google", "me@example.com")
            val example = method("example.org", "github", "")
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, SignInMethodSyncRules.ENTITY_TYPE, github, ChangeKind.UPSERT,
                methodPayload("github.com", "google", "me@example.com", 2), oldPc.version(SignInMethodSyncRules.ENTITY_TYPE, github),
                "2026-11-02T09:04:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, SignInMethodSyncRules.ENTITY_TYPE, example, ChangeKind.UPSERT,
                methodPayload("example.org", "github", "", 1), oldPc.version(SignInMethodSyncRules.ENTITY_TYPE, example),
                "2026-11-02T09:05:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, SignInMethodSyncRules.ENTITY_TYPE, example, ChangeKind.DELETE,
                deleted, oldPc.version(SignInMethodSyncRules.ENTITY_TYPE, example), "2026-11-02T09:06:00Z")))
            val appearance = String(AppearanceSyncRules.encodePayload(Appearance()), Charsets.UTF_8)
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID,
                ChangeKind.UPSERT, appearance, oldPc.version(AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID),
                "2026-11-02T09:07:00Z")))
            for (name in listOf("PC", "Personal PC")) deliver(oldPc.sign(epoch1, "device_name", oldPc.id, ChangeKind.UPSERT,
                """{"name":"$name"}""", oldPc.version("device_name", oldPc.id), "2026-11-02T09:08:00Z"))

            // A conflict on e5 that the phone resolves (it stays as a resolved conflict).
            assertEquals(IncomingResult.APPLIED, deliver(oldPc.sign(epoch1, "entry", "e5", ChangeKind.UPSERT,
                entryPayload(login("e5", "Login 5 PC")), oldPc.version("entry", "e5"), "2026-11-02T09:10:00Z")))
            val stale5 = RecordVersion.parse(requireNotNull(phone.syncDao().recordState("entry", "e5")).recordVersionJson)
            entries.save(login("e5", "Login 5 phone"), setOf(logins.id), vaultKey)
            assertEquals(IncomingResult.CONFLICT, deliver(oldPc.sign(epoch1, "entry", "e5", ChangeKind.UPSERT,
                entryPayload(login("e5", "Login 5 PC again")), oldPc.version("entry", "e5", stale5), "2026-11-02T09:11:00Z")))
            val toResolve = phone.syncDao().conflicts().single { it.entityId == "e5" }
            SyncConflictResolver(phone, phoneIdentity, null, phoneContext).resolve(toResolve.conflictId, true, vaultKey)

            // The laptop: concurrent icon, sign-in method and appearance changes that win by time.
            assertEquals(IncomingResult.APPLIED, deliver(laptop.sign(epoch1, SiteIconSyncRules.ENTITY_TYPE, icon, ChangeKind.UPSERT,
                iconPayload(Color.GREEN), laptop.version(SiteIconSyncRules.ENTITY_TYPE, icon, RecordVersion(emptyMap())),
                "2026-11-03T08:00:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(laptop.sign(epoch1, SignInMethodSyncRules.ENTITY_TYPE, github, ChangeKind.UPSERT,
                methodPayload("github.com", "google", "me@example.com", 5),
                laptop.version(SignInMethodSyncRules.ENTITY_TYPE, github, RecordVersion(emptyMap())), "2026-11-03T08:01:00Z")))
            assertEquals(IncomingResult.APPLIED, deliver(laptop.sign(epoch1, AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID,
                ChangeKind.UPSERT, appearance, laptop.version(AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID,
                    RecordVersion(emptyMap())), "2026-11-03T08:02:00Z")))
            assertEquals(1, phone.syncDao().conflicts().count { it.resolvedAtUtc.isEmpty() })
            assertEquals(1, phone.syncDao().conflicts().count { it.resolvedAtUtc.isNotEmpty() })

            // Every device announced its features and sent a signed key statement.
            store.publish(phone, full = true)
            for (author in listOf(tablet, oldPc, laptop)) {
                store.recordPeerFeatures(author.id, windowsFeatures)
                assertTrue(store.acceptKeyStatement(KeyStatement.of(author.identity, vault)))
            }

            // The phone removes the tablet: a REMOVE with its cutoff, and new keys (epoch 2).
            val cutoff = store.removalCutoff(tablet.id)
            assertTrue(cutoff, cutoff.startsWith("cutoff1:"))
            phone.removeSyncMember(phoneIdentity, tablet.id, cutoff, store.keyStatements())
            store.publish(phone, full = true)
            assertEquals(listOf(2L), phone.syncDao().epochKeys().map { it.epoch })
            entries.save(login("e1", "Login 1 after the tablet left"), setOf(logins.id), vaultKey)
            entries.save(login("e9", "Written in epoch 2"), setOf(logins.id), vaultKey)
            entries.delete(login("e6", "Login 6"), vaultKey)

            // The owner's PC leaves (a signed leave request); the phone rotates again (epoch 3) when unlocked.
            val verified = SyncMembershipManager.verify(phone.syncDao().membershipEvents(vault).map { it.toEvent() })
            val request = LeaveRequest(vault, oldPc.id, verified.head, verified.keyEpoch, System.currentTimeMillis())
            assertTrue(store.answerDriveLeave(request, oldPc.identity.sign(request.signingBytes())))
            store.applyQueued(phone, vaultKey)
            store.publish(phone, full = true)
            assertEquals(listOf(2L, 3L), phone.syncDao().epochKeys().map { it.epoch }.sorted())
            val members = phone.syncDao().memberships(vault).associateBy { it.deviceId }
            assertEquals(MemberStatus.REVOKED.name, members.getValue(oldPc.id).status)
            assertEquals(MemberStatus.REVOKED.name, members.getValue(tablet.id).status)
            entries.save(login("e1", "Login 1 after the PC left"), setOf(logins.id), vaultKey)
            entries.save(login("e10", "Written in epoch 3"), setOf(logins.id), vaultKey)

            // History compaction: the laptop applied everything, so only the keep set stays.
            val heads = phone.syncDao().deviceHeads(vault)
            val applied = heads.associate { it.deviceId to SyncChainHead(it.sequence, it.hash) }
            val mirror = requireNotNull(store.snapshot()).copy(peerProgress = mapOf(laptop.id to SyncProgress(applied, applied)))
            val before = phone.syncDao().operations().size
            assertTrue(HistoryCompaction.run(phone, mirror, CloudSyncState(), force = true) > 0)
            assertTrue(phone.syncDao().operations().size < before)

            // The PC after "Start fresh": a new identity pairs with the phone.
            val joinerKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(joinerSeed))
            val group = SyncMembershipManager.verify(phone.syncDao().membershipEvents(vault).map { it.toEvent() })
            val joining = SyncMembershipEntity.from(DeviceMembership(vault, joinerId, "Windows device", joinerKey,
                MemberStatus.ACTIVE, me.deviceId, group.events.size + 1L, group.keyEpoch))
            val output = ByteArrayOutputStream()
            val admission = SyncSnapshot(phoneContext, phone, com.privatevault.app.security.EncryptedPhotoStore(phoneContext))
                .export(output, vaultKey, pairingKey, joining, windowsPeer = true, joinerFeatures = windowsFeatures)
            val stream = output.toByteArray()
            assertEquals("NUVSYNC4", String(stream, 0, 8, Charsets.US_ASCII))
            // This app's own validator accepts it ([RealisticEnrollmentVectorTest] checks it again on the JVM).
            val state = readMetadata(stream, pairingKey)
            validateSnapshotState(state, vault, joinerId, me, allowLineage = true)
            assertTrue("rotated epochs are in the history", state.operations.any { EpochKeys.epochOf(it.payloadNonce) == 3L })

            // The phone stores the ADD after the PC's "ready" and writes its next change (epoch 3).
            phone.syncDao().insertMembershipEvent(requireNotNull(admission))
            phone.syncDao().upsertMembership(joining)
            entries.save(login("e1", "Login 1 after the PC joined again"), setOf(logins.id), vaultKey)
            val next = phone.syncDao().operations().filter { it.deviceId == me.deviceId }.maxBy { it.sequence }

            val gson = GsonBuilder().disableHtmlEscaping().create()
            val vector = JsonObject().apply {
                addProperty("format", "nuvori-sync-enrollment-vectors")
                addProperty("version", 1)
                addProperty("generatedBy", "app/src/androidTest/.../sync/RealisticEnrollmentFixtureTest.kt; do not edit")
                addProperty("vaultId", vault)
                addProperty("pairingKeyHex", pairingKey.joinToString("") { "%02x".format(it) })
                add("host", JsonObject().apply { addProperty("deviceId", me.deviceId); addProperty("publicKey", me.publicKeyBase64Url) })
                add("joiner", JsonObject().apply {
                    addProperty("deviceId", joinerId); addProperty("publicKey", joinerKey)
                    addProperty("seedHex", joinerSeed.joinToString("") { "%02x".format(it) })
                })
                add("hostFeatures", JsonArray().apply { SyncFeatures.ANNOUNCED.forEach(::add) })
                add("joinerFeatures", JsonArray().apply { windowsFeatures.sorted().forEach(::add) })
                add("removed", JsonArray().apply { add(tablet.id); add(oldPc.id) })
                add("expected", JsonObject().apply {
                    addProperty("entries", phone.dao().allEntries().size)
                    addProperty("groups", phone.dao().allGroupsWithEntries().size)
                    addProperty("passkeys", phone.dao().allPasskeys().size)
                    addProperty("membershipEvents", state.membershipEvents.size)
                    addProperty("operations", state.operations.size)
                    addProperty("heads", state.heads.size)
                    addProperty("records", state.records.size)
                    addProperty("tombstones", state.tombstones.size)
                    // Windows keeps a deleted sign-in method as its record, not as a tombstone row.
                    addProperty("signInMethodTombstones", state.tombstones.count { it.entityType == SignInMethodSyncRules.ENTITY_TYPE })
                    addProperty("openConflicts", state.conflicts.size)
                    add("epochKeys", JsonArray().apply { state.contentKeys.orEmpty().keys.sorted().forEach(::add) })
                    addProperty("keyEpoch", group.keyEpoch)
                })
                addProperty("stream", Base64.getEncoder().encodeToString(stream))
                add("next", gson.toJsonTree(next))
            }
            val file = File(base.getExternalFilesDir(null), "enrollment-realistic-v1.json")
            file.writeText(gson.newBuilder().setPrettyPrinting().create().toJson(vector) + "\n")
            android.util.Log.i("RealisticEnrollment", "Wrote ${file.absolutePath} (${file.length()} bytes)")
            contentKey.fill(0)
        } finally {
            SyncedSiteIconCache.close("realistic-enrollment")
            phone.close()
            identities.values.forEach { it.clear() }
            root.deleteRecursively()
        }
    }

    /** The snapshot metadata of an enrollment stream, as [SyncSnapshot.prepare] reads it. */
    private fun readMetadata(stream: ByteArray, key: ByteArray): SnapshotSyncState {
        val data = DataInputStream(stream.inputStream())
        val magic = ByteArray(8).also(data::readFully)
        val nonce = ByteArray(12).also(data::readFully)
        val sealed = ByteArray(data.readInt()).also(data::readFully)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(magic)
            doFinal(sealed)
        }
        return Gson().fromJson(plain.toString(Charsets.UTF_8), SnapshotSyncState::class.java)
    }
}
