package com.privatevault.app.sync

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SyncAttachmentManifestEntity
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {
    // Windows peer payloads.

    private fun windowsEntry(type: String = "PASSWORD", title: String = "github.com", username: String = "",
        extra: String = "") = """{"id":"e1","type":"$type","title":"$title","primaryValue":"$username",
        "secondaryValue":"pw","tertiaryValue":"https://github.com","fourthValue":"","cardKind":"CREDIT",
        "network":"","totpAlgorithm":"SHA1","totpDigits":6,"totpPeriod":30,"linkedApps":"","autofillSignatures":"",
        "autofillOrigins":"","linkedAuthenticatorId":"","notes":"","color":4278743114,"tags":"","favorite":false,
        "lastOpenedAt":0,"sortOrder":10,"createdAt":10,"updatedAt":20$extra}"""

    @Test fun windowsPasswordOnlyLoginWithEmptyUsernameIsAccepted() {
        val entry = SyncEntryCodec.parseEntry(windowsEntry(), "e1")
        assertEquals(EntryType.PASSWORD, entry.type)
        assertEquals("", entry.primaryValue)
        assertEquals("pw", entry.secondaryValue)
        assertEquals(4278743114L, entry.color)
    }

    @Test fun windowsCustomAndOriginFallbackTitlesAreKept() {
        assertEquals("My GitHub", SyncEntryCodec.parseEntry(windowsEntry(title = "My GitHub"), "e1").title)
        assertEquals("localhost:8443", SyncEntryCodec.parseEntry(windowsEntry(title = "localhost:8443"), "e1").title)
    }

    @Test fun blankTitleGetsADeterministicLabelInsteadOfBlockingTheChain() {
        assertEquals("github.com", SyncEntryCodec.parseEntry(windowsEntry(title = ""), "e1").title)
        assertEquals("Untitled", SyncEntryCodec.parseEntry(windowsEntry(type = "NOTE", title = " "), "e1").title)
    }

    @Test fun missingOrNullOptionalFieldsTakeDefaultsAndUnknownFieldsAreIgnored() {
        val entry = SyncEntryCodec.parseEntry("""{"id":"e1","type":"NOTE","title":"Note","notes":null,
            "createdAt":5,"futureField":{"x":1}}""", "e1")
        assertEquals("", entry.notes)
        assertEquals(5L, entry.updatedAt)
        assertEquals(5L, entry.sortOrder)
        assertEquals(30, entry.totpPeriod)
    }

    @Test fun malformedEntriesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SyncEntryCodec.parseEntry(windowsEntry(), "other") }
        assertThrows(IllegalArgumentException::class.java) { SyncEntryCodec.parseEntry(windowsEntry(type = "SECRET"), "e1") }
        assertThrows(IllegalArgumentException::class.java) {
            SyncEntryCodec.parseEntry("""{"id":"e1","type":"AUTHENTICATOR","title":"Code","secondaryValue":"JBSWY3DPEHPK3PXP",
                "totpDigits":9}""", "e1")
        }
        assertThrows(IllegalArgumentException::class.java) { SyncEntryCodec.parseEntry("""{"type":"NOTE"}""", "e1") }
    }

    @Test fun windowsPhotoWithCoverAndAddedAtParsesAndOlderPhotosDefault() {
        val hash = "a".repeat(64)
        val photo = SyncEntryCodec.parsePhoto("""{"id":"p","entryId":"e1","isCover":true,"addedAt":30,
            "blobHash":"$hash","blobSize":100}""", "p")
        assertTrue(photo.isCover)
        assertEquals(30L, photo.addedAt)
        val legacy = SyncEntryCodec.parsePhoto("""{"id":"p","entryId":"e1","blobHash":"$hash","blobSize":100}""", "p")
        assertFalse(legacy.isCover)
        assertEquals(0L, legacy.addedAt)
        assertThrows(IllegalArgumentException::class.java) {
            SyncEntryCodec.parsePhoto("""{"id":"p","entryId":"e1","blobHash":"bad","blobSize":100}""", "p")
        }
    }

    // Links of incoming entries.

    private val login = VaultEntry(id = "e1", type = EntryType.PASSWORD, title = "Login")
    private fun context(groups: Map<String, EntryType?> = emptyMap(), deleted: Set<String> = emptySet(),
        linkedType: EntryType? = null, linkedDeleted: Boolean = false) =
        EntryLinkRules.Context(groups, deleted, linkedType, linkedDeleted)

    @Test fun typeChangedToNoteDropsFoldersOfTheOldTypeButKeepsPlainGroups() {
        val note = login.copy(type = EntryType.NOTE, linkedAuthenticatorId = "auth")
        val result = EntryLinkRules.resolve(note, setOf("logins", "plain"),
            context(mapOf("logins" to EntryType.PASSWORD, "plain" to null), linkedType = EntryType.AUTHENTICATOR))
        result as EntryLinkRules.Resolution.Ready
        assertEquals(setOf("plain"), result.groupIds)
        assertEquals("", result.entry.linkedAuthenticatorId)
    }

    @Test fun deletedGroupsAreDroppedButMissingOnesWait() {
        val ready = EntryLinkRules.resolve(login, setOf("gone"), context(deleted = setOf("gone")))
        assertEquals(emptySet<String>(), (ready as EntryLinkRules.Resolution.Ready).groupIds)
        assertEquals(EntryLinkRules.Resolution.Waiting, EntryLinkRules.resolve(login, setOf("late"), context()))
    }

    @Test fun linkedAuthenticatorWaitsUntilItArrivesAndIsDroppedWhenDeleted() {
        val linked = login.copy(linkedAuthenticatorId = "auth")
        assertEquals(EntryLinkRules.Resolution.Waiting, EntryLinkRules.resolve(linked, emptySet(), context()))
        val dropped = EntryLinkRules.resolve(linked, emptySet(), context(linkedDeleted = true))
        assertEquals("", (dropped as EntryLinkRules.Resolution.Ready).entry.linkedAuthenticatorId)
        val kept = EntryLinkRules.resolve(linked, emptySet(), context(linkedType = EntryType.AUTHENTICATOR))
        assertEquals("auth", (kept as EntryLinkRules.Resolution.Ready).entry.linkedAuthenticatorId)
    }

    @Test fun onlyOneFolderIsKeptAndTheChoiceIsDeterministic() {
        val result = EntryLinkRules.resolve(login, setOf("b", "a"),
            context(mapOf("a" to EntryType.PASSWORD, "b" to EntryType.PASSWORD)))
        assertEquals(setOf("a"), (result as EntryLinkRules.Resolution.Ready).groupIds)
    }

    // Photo supersession.

    @Test fun photoUpsertIsSupersededOnlyByALaterChangeFromTheSameAuthor() {
        val blocked = QueuedOperationMeta("pc", 5, "photo", "p", "upsert")
        val later = listOf(QueuedOperationMeta("pc", 6, "photo", "p", "delete"))
        assertTrue(PhotoSupersession.isSuperseded(blocked, "p", "e", later))
        assertTrue(PhotoSupersession.isSuperseded(blocked, "p", "e", listOf(QueuedOperationMeta("pc", 9, "entry", "e", "delete"))))
        assertFalse(PhotoSupersession.isSuperseded(blocked, "p", "e", listOf(QueuedOperationMeta("phone", 9, "photo", "p", "delete"))))
        assertFalse(PhotoSupersession.isSuperseded(blocked, "p", "e", listOf(QueuedOperationMeta("pc", 4, "photo", "p", "delete"))))
        assertFalse(PhotoSupersession.isSuperseded(blocked, "p", "e", listOf(QueuedOperationMeta("pc", 9, "entry", "e", "upsert"))))
    }

    // Received frontier.

    @Test fun frontierCountsAppliedRelayedAndQueuedChangesWithoutGaps() {
        val applied = mapOf("pc" to SyncChainHead(2, "b".repeat(64)))
        val relayed = listOf(ChainLink("pc", 3, "c".repeat(64), "b".repeat(64)))
        val queued = listOf(ChainLink("pc", 4, "d".repeat(64), "c".repeat(64)),
            ChainLink("pc", 6, "f".repeat(64), "e".repeat(64)))
        assertEquals(SyncChainHead(4, "d".repeat(64)), IncomingChain.received(applied, relayed, queued)["pc"])
    }

    @Test fun admissionAcceptsTheNextChangeAcknowledgesResendsAndRejectsGaps() {
        val received = mapOf("pc" to SyncChainHead(4, "d".repeat(64)))
        assertEquals(IncomingChain.Admission.STORE,
            IncomingChain.admit(ChainLink("pc", 5, "e".repeat(64), "d".repeat(64)), received, emptySet()))
        assertEquals(IncomingChain.Admission.ALREADY_STORED,
            IncomingChain.admit(ChainLink("pc", 4, "d".repeat(64), "c".repeat(64)), received, setOf("d".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) {
            IncomingChain.admit(ChainLink("pc", 6, "f".repeat(64), "e".repeat(64)), received, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingChain.admit(ChainLink("pc", 5, "e".repeat(64), "0".repeat(63) + "1"), received, emptySet())
        }
    }

    // Remote-edit signal.

    @Test fun remoteChangesCarryARevisionAnEditorCanCompareAgainst() {
        RemoteEntryChanges.clear()
        val opened = RemoteEntryChanges.revision()
        assertNull(RemoteEntryChanges.changedSince("e1", opened))
        RemoteEntryChanges.record("e1", deleted = false, photosOnly = true)
        RemoteEntryChanges.record("e1", deleted = false)
        val change = requireNotNull(RemoteEntryChanges.changedSince("e1", opened))
        assertFalse(change.photosOnly)
        assertTrue(change.revision > opened)
        RemoteEntryChanges.record("e2", deleted = false, conflict = true)
        assertTrue(RemoteEntryChanges.changes.value.getValue("e2").conflict)
        RemoteEntryChanges.acknowledge("e1")
        assertNull(RemoteEntryChanges.changes.value["e1"])
        repeat(600) { RemoteEntryChanges.record("bulk-$it", deleted = true) }
        assertEquals(512, RemoteEntryChanges.changes.value.size)
        RemoteEntryChanges.clear()
    }

    // Orphan photos.

    @Test fun orphanSweepRemovesOnlyProvablyUnusedPhotoData() {
        val now = 10_000_000L
        val old = now - PhotoOrphanRules.FILE_GRACE_MILLIS - 1
        val live = VaultPhoto("p1", "e1", "p1.vaultphoto", "p1.vaultthumb")
        val ofDeletedEntry = VaultPhoto("p2", "gone", "p2.vaultphoto", "")
        val ofLateEntry = VaultPhoto("p3", "late", "p3.vaultphoto", "")
        fun manifest(id: String) = SyncAttachmentManifestEntity(id, "entry", "e1", "$id.vaultphoto", "a".repeat(64), 100, 1)
        val plan = PhotoOrphanRules.plan(listOf(live, ofDeletedEntry, ofLateEntry), setOf("e1"), setOf("gone"),
            listOf(manifest("p1"), manifest("deleted-photo")),
            listOf(PhotoFileInfo("p1.vaultphoto", old), PhotoFileInfo("p1.vaultthumb", old),
                PhotoFileInfo("p2.vaultphoto", old), PhotoFileInfo("stray.vaultphoto", old),
                PhotoFileInfo("fresh.vaultphoto", now), PhotoFileInfo("draft-x.vaultphoto", old),
                PhotoFileInfo("restore-x.vaultthumb", old), PhotoFileInfo("p3.vaultphoto", old)), now)
        assertEquals(listOf(ofDeletedEntry), plan.photoRows)
        assertEquals(setOf("deleted-photo"), plan.manifestIds)
        assertEquals(setOf("p2.vaultphoto", "stray.vaultphoto"), plan.files)
    }

    // Conflict determinism.

    @Test fun relationsDoNotDependOnArrivalOrder() {
        val a = RecordVersion(mapOf("a" to 2L, "b" to 1L))
        val b = RecordVersion(mapOf("a" to 1L, "b" to 2L))
        val base = RecordVersion(mapOf("a" to 1L, "b" to 1L))
        assertEquals(VersionRelation.CONCURRENT, ConflictVersions.incomingRelation(a, b))
        assertEquals(VersionRelation.CONCURRENT, ConflictVersions.incomingRelation(b, a))
        assertEquals(VersionRelation.AFTER, ConflictVersions.incomingRelation(a, base))
        assertEquals(VersionRelation.BEFORE, ConflictVersions.incomingRelation(base, a))
        assertEquals(VersionRelation.AFTER, ConflictVersions.incomingRelation(base, null))
    }

    @Test fun aResolutionSettlesTheConflictOnEveryDeviceWhicheverSideWasLocal() {
        val a = RecordVersion(mapOf("a" to 2L, "b" to 1L))
        val b = RecordVersion(mapOf("a" to 1L, "b" to 2L))
        val fromA = ConflictVersions.resolution(a, b, "a")
        val fromB = ConflictVersions.resolution(b, a, "a")
        assertEquals(fromA, fromB)
        assertTrue(ConflictVersions.settles(fromA, a, b) && ConflictVersions.settles(fromA, b, a))
        assertEquals(VersionRelation.AFTER, fromA.relationTo(a))
        assertEquals(VersionRelation.AFTER, fromA.relationTo(b))
        // A later edit that saw only one side does not settle it.
        assertFalse(ConflictVersions.settles(RecordVersion(mapOf("a" to 3L, "b" to 1L)), a, b))
    }

    @Test fun applyingAllPermutationsConvergesOnTheSameWinnerAfterResolution() {
        val base = RecordVersion(mapOf("a" to 1L))
        val edits = listOf(RecordVersion(mapOf("a" to 2L)), RecordVersion(mapOf("a" to 1L, "b" to 1L)),
            RecordVersion(mapOf("a" to 1L, "c" to 1L)))
        val outcomes = permutations(edits).map { order ->
            var current = base
            val conflicts = mutableListOf<RecordVersion>()
            order.forEach { incoming ->
                when (ConflictVersions.incomingRelation(incoming, current)) {
                    VersionRelation.AFTER -> current = incoming
                    VersionRelation.CONCURRENT -> conflicts += incoming
                    else -> Unit
                }
            }
            conflicts.fold(current) { local, remote -> ConflictVersions.resolution(local, remote, "a") }
        }
        // Every arrival order ends with a version that includes every edit.
        outcomes.forEach { final -> edits.forEach { assertEquals(VersionRelation.AFTER, final.relationTo(it)) } }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> = if (items.size <= 1) listOf(items)
        else items.flatMap { item -> permutations(items - item).map { listOf(item) + it } }
}
