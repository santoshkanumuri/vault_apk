package com.privatevault.app.sync

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncMembershipEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Generates and checks the shared membership test vectors that Windows copies to
 * `src-tauri/fixtures/membership-v1.json`. Set NUVORI_WRITE_VECTORS=1 to rewrite the file after an
 * intentional format change; otherwise a mismatch fails the build.
 */
class MembershipVectorsTest {
    @Test fun sharedVectorFileMatchesTheReferenceImplementation() {
        val generated = MembershipVectors.build()
        val file = vectorFile()
        if (!file.exists() || System.getenv("NUVORI_WRITE_VECTORS") == "1") {
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(generated) + "\n")
        }
        assertEquals(generated, JsonParser.parseString(file.readText()))
    }

    @Test fun vectorsVerifyWithTheProductionCode() {
        val vectors = JsonParser.parseString(vectorFile().takeIf { it.exists() }?.readText()
            ?: MembershipVectors.build().toString()).asJsonObject
        val decoder = Base64.getUrlDecoder()
        val transport = vectors.getAsJsonObject("transport")
        val vaultId = transport["vaultId"].asString
        val root = transport["epoch1SecretB64"].asString
        transport.getAsJsonArray("epochs").forEach {
            val epoch = it.asJsonObject
            assertEquals(epoch["secretB64"].asString, TransportEpochs.secret(root, vaultId, epoch["epoch"].asLong))
        }
        val probe = vectors.getAsJsonObject("probe")
        val challenge = hex(probe["challengeHex"].asString)
        assertEquals(probe["proofB64"].asString,
            Base64.getUrlEncoder().withoutPadding().encodeToString(TransportEpochs.probeProof(root, challenge)))
        val leave = vectors.getAsJsonObject("leaveRequest")
        val parsed = requireNotNull(MembershipWire.parseLeaveFrame(leave["frame"].asString.toByteArray(Charsets.US_ASCII)))
        assertEquals(leave["json"].asString, parsed.request.toJson())
        assertEquals(leave["signingBytesHex"].asString, hexOf(parsed.request.signingBytes()))
        val client = vectors.getAsJsonObject("client")
        assertTrue(DeviceIdentityCrypto.verify(decoder.decode(client["publicKeyB64"].asString),
            parsed.request.signingBytes(), parsed.signature))
        val history = vectors.getAsJsonObject("history")
        val events = MembershipWire.parseHistory(history.getAsJsonArray("events").toString().toByteArray())
        val clientHead = history["clientHeadSequence"].asInt
        val notice = RemovalNoticeVerifier.verify(events.take(clientHead), client["deviceId"].asString, events)
        assertTrue(notice is RemovalNotice.Removed)
        assertEquals(history["keyEpochAfter"].asLong, SyncMembershipManager.verify(events.map { it.toEvent() }).keyEpoch)
    }

    private fun vectorFile(): File {
        val relative = "src/test/resources/sync-vectors/membership-v1.json"
        return listOf(File(relative), File("app/$relative")).firstOrNull { File(it.path.removeSuffix("resources/sync-vectors/membership-v1.json")).exists() }
            ?: File(relative)
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/** Deterministic fixtures: Ed25519 signatures and HMACs over fixed inputs. */
internal object MembershipVectors {
    const val VAULT_ID = "vault-membership-v1"
    const val MANAGER_ID = "manager-device"
    const val CLIENT_ID = "client-device"
    const val OTHER_ID = "other-device"
    const val CREATED_AT = 1_767_225_600_000L
    val managerSeed = ByteArray(32) { 0x01 }
    val clientSeed = ByteArray(32) { 0x02 }
    val otherSeed = ByteArray(32) { 0x03 }
    val rootSecret: String = b64(ByteArray(32) { 0x11 })
    val challenge = ByteArray(32) { it.toByte() }

    fun publicKey(seed: ByteArray): String = b64(DeviceIdentityCrypto.publicKey(seed))

    /** GENESIS(manager), ADD(client), ADD(other): the client's last known history. */
    fun clientHistory(): List<SyncMembershipEvent> {
        val sign = { bytes: ByteArray -> DeviceIdentityCrypto.sign(managerSeed, bytes) }
        val genesis = SyncMembershipEvent.sign(VAULT_ID, 1, GENESIS_HASH, MembershipAction.GENESIS,
            MANAGER_ID, MANAGER_ID, publicKey(managerSeed), 1, sign)
        val client = SyncMembershipEvent.sign(VAULT_ID, 2, genesis.hash, MembershipAction.ADD,
            MANAGER_ID, CLIENT_ID, publicKey(clientSeed), 1, sign)
        val other = SyncMembershipEvent.sign(VAULT_ID, 3, client.hash, MembershipAction.ADD,
            MANAGER_ID, OTHER_ID, publicKey(otherSeed), 1, sign)
        return listOf(genesis, client, other)
    }

    fun leaveRequest(): LeaveRequest = LeaveRequest(VAULT_ID, CLIENT_ID, clientHistory().last().hash, 1, CREATED_AT)

    fun leaveFrame(): MembershipWire.LeaveFrame {
        val request = leaveRequest()
        return MembershipWire.LeaveFrame(TransportEpochs.probeProof(rootSecret, challenge), request,
            DeviceIdentityCrypto.sign(clientSeed, request.signingBytes()))
    }

    /** The manager's answer to the vector leave request: one signed REMOVE at key epoch 2. */
    fun removedHistory(): List<SyncMembershipEvent> {
        val history = clientHistory()
        val decision = LeaveRequestHandler.handle(leaveFrame(), challenge, rootSecret, history, setOf(1L),
            MANAGER_ID, CREATED_AT + 60_000) { DeviceIdentityCrypto.sign(managerSeed, it) }
        return history + (decision as LeaveDecision.Left).append
    }

    fun build(): JsonObject {
        val gson = com.google.gson.Gson()
        val frame = leaveFrame()
        val request = frame.request
        val removed = removedHistory()
        return JsonObject().apply {
            addProperty("format", "nuvori-membership-vectors-v1")
            addProperty("notes", "Base64 is URL-safe without padding. Ed25519 seeds are raw 32-byte private seeds. " +
                "Transport secret for epoch N>1 = base64url(HMAC-SHA256(decode(epoch-1 secret), " +
                "\"nuvori-transport-epoch-v1:<vaultId>:<N>\")); epoch 1 is the stored group secret. " +
                "Probe proof = HMAC-SHA256(decode(secret), \"nuvori-lan-probe-v1\" || challenge). " +
                "Leave signing bytes = UTF-8 \"nuvori-leave-v1\\n<vaultId>\\n<deviceId>\\n<membershipHead>\\n<keyEpoch>\\n<createdAtMillis>\".")
            add("manager", identity(MANAGER_ID, managerSeed))
            add("client", identity(CLIENT_ID, clientSeed))
            add("other", identity(OTHER_ID, otherSeed))
            add("transport", JsonObject().apply {
                addProperty("vaultId", VAULT_ID)
                addProperty("epoch1SecretB64", rootSecret)
                add("epochs", JsonArray().apply {
                    (1L..3L).forEach { epoch -> add(JsonObject().apply {
                        addProperty("epoch", epoch)
                        addProperty("secretB64", TransportEpochs.secret(rootSecret, VAULT_ID, epoch))
                    }) }
                })
            })
            add("probe", JsonObject().apply {
                addProperty("challengeHex", hexOf(challenge))
                addProperty("secretB64", rootSecret)
                addProperty("proofB64", b64(frame.proof))
            })
            add("leaveRequest", JsonObject().apply {
                add("fields", JsonParser.parseString(request.toJson()))
                addProperty("json", request.toJson())
                addProperty("signingBytesHex", hexOf(request.signingBytes()))
                addProperty("signatureB64", b64(frame.signature))
                addProperty("frame", MembershipWire.leaveFrame(frame.proof, request, frame.signature))
                addProperty("serverNowMillis", CREATED_AT + 60_000)
                addProperty("expectedReply", MembershipWire.LEFT)
            })
            add("history", JsonObject().apply {
                addProperty("clientDeviceId", CLIENT_ID)
                addProperty("clientHeadSequence", 3)
                addProperty("clientHeadHash", clientHistory().last().hash)
                addProperty("keyEpochAfter", 2)
                add("events", gson.toJsonTree(removed.map { SyncMembershipEventEntity.from(it) }))
            })
            add("replies", JsonObject().apply {
                addProperty("ok", MembershipWire.OK)
                addProperty("removed", MembershipWire.REMOVED)
                addProperty("left", MembershipWire.LEFT)
                addProperty("notManager", MembershipWire.NOT_MANAGER)
                addProperty("busy", MembershipWire.BUSY)
            })
        }
    }

    private fun identity(id: String, seed: ByteArray) = JsonObject().apply {
        addProperty("deviceId", id)
        addProperty("seedHex", hexOf(seed))
        addProperty("publicKeyB64", publicKey(seed))
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
