package com.privatevault.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.security.EncryptedPhotoStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.EOFException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class PairingUiState(val stage: String = "idle", val invitation: String = "",
    val confirmation: String = "", val message: String = "")

internal data class PairingInvitation(val address: String, val port: Int, val session: String,
    val vault: String, val code: String) {
    fun encode(): String = "nuvori-pair://v3/" + Base64.getUrlEncoder().withoutPadding().encodeToString(
        JsonObject().apply {
            addProperty("address", address); addProperty("port", port); addProperty("session", session)
            addProperty("vault", vault); addProperty("code", code)
        }.toString().toByteArray(Charsets.UTF_8))

    companion object {
        fun decode(value: String): PairingInvitation {
            require(value.length <= 2048 && value.startsWith("nuvori-pair://v3/")) { "Invalid pairing link" }
            val json = JsonParser.parseString(Base64.getUrlDecoder().decode(value.removePrefix("nuvori-pair://v3/"))
                .toString(Charsets.UTF_8)).asJsonObject
            val result = PairingInvitation(json.get("address").asString, json.get("port").asInt,
                json.get("session").asString, json.get("vault").asString, json.get("code").asString)
            require(result.port in 1024..65535 && result.session.length in 1..128 && result.vault.length in 1..128 &&
                result.code.length == 24 && result.code.all { it in '0'..'9' }) { "Invalid pairing link" }
            require(InetAddresses.parseNumericAddress(result.address).let {
                isPrivateAddress(it) && !it.isLinkLocalAddress
            }) { "Pair using a reachable local network" }
            return result
        }
    }
}

internal data class ReversePairingInvitation(val address: String, val port: Int,
    val session: String, val code: String) {
    companion object {
        fun decode(value: String): ReversePairingInvitation {
            require(value.length <= 2048 && value.startsWith("nuvori-pair-reverse://v1/")) {
                "Invalid Windows pairing QR"
            }
            val json = JsonParser.parseString(Base64.getUrlDecoder()
                .decode(value.removePrefix("nuvori-pair-reverse://v1/"))
                .toString(Charsets.UTF_8)).asJsonObject
            val result = ReversePairingInvitation(json.get("address").asString,
                json.get("port").asInt, json.get("session").asString, json.get("code").asString)
            require(result.port in 1024..65535 && result.session.length in 1..128 &&
                result.code.length == 24 && result.code.all { it in '0'..'9' }) {
                "Invalid Windows pairing QR"
            }
            require(InetAddresses.parseNumericAddress(result.address).let {
                isPrivateAddress(it) && !it.isLinkLocalAddress
            }) { "Invalid Windows pairing QR" }
            return result
        }
    }
}

class AndroidPairing(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(PairingUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var connection: Socket? = null
    @Volatile private var approval: CompletableDeferred<Boolean>? = null

    fun host(database: VaultDatabase, localKey: ByteArray, masterPassword: CharArray,
             reverseLink: String? = null) {
        val previous = job
        cancel()
        val key = localKey.copyOf()
        val password = masterPassword.copyOf()
        job = scope.launch {
            var committedHost = false
            try {
                previous?.join()
                val (network, address) = wifi()
                val reverse = reverseLink?.let(ReversePairingInvitation::decode)
                val identity = AndroidDeviceIdentityStore(context).getOrCreate()
                val vaultId = requireNotNull(database.dao().settings()).vaultId
                database.prepareSyncGroup(AndroidDeviceIdentityStore(context))
                val server = if (reverse == null) ServerSocket().apply {
                    bind(InetSocketAddress(address, 0))
                    soTimeout = 120_000
                } else null
                listener = server
                val random = SecureRandom()
                val invitation = PairingInvitation(reverse?.address ?: address.hostAddress!!,
                    reverse?.port ?: requireNotNull(server).localPort,
                    reverse?.session ?: UUID.randomUUID().toString(), vaultId,
                    reverse?.code ?: CharArray(24) { ('0'.code + random.nextInt(10)).toChar() }.concatToString())
                mutableState.value = if (reverse == null) PairingUiState("offering", invitation.encode(),
                    message = "Scan this QR on the new device, or copy its link to Windows, within two minutes.")
                else PairingUiState("connecting", message = "Connecting to Windows on the local networkâ€¦")
                val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
                repeat(if (reverse == null) 3 else 1) {
                    ensureActive()
                    require(android.os.SystemClock.elapsedRealtime() < deadline) { "Pairing expired" }
                    val socket = if (reverse == null) {
                        requireNotNull(server).soTimeout = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt()
                        server.accept()
                    } else Socket().apply {
                        network.bindSocket(this)
                        connect(InetSocketAddress(InetAddresses.parseNumericAddress(reverse.address), reverse.port), 10_000)
                    }
                    connection = socket
                    try {
                        socket.use {
                            socket.soTimeout = 30_000
                            val frames = SyncFrames(socket.getInputStream(), socket.getOutputStream())
                            if (reverse != null) frames.send(vaultId.toByteArray(Charsets.UTF_8), 128)
                            val result = pairingHandshake(frames, identity, invitation.code.toCharArray(),
                                invitation.session, vaultId, true)
                            try {
                                verifyMasterPassword(frames, identity, password, invitation.session,
                                    vaultId, true, result.peer)
                                EncryptedSyncChannel(frames, result.key, true).use { channel ->
                                    confirm(channel, result.confirmation)
                                    require(android.os.SystemClock.elapsedRealtime() < deadline) { "Pairing expired" }
                                    socket.soTimeout = 300_000
                                    val existing = database.syncDao().membership(vaultId, result.peer.deviceId)
                                    require(existing != null || database.syncDao().activeMembershipCount(vaultId) < MAX_ACTIVE_SYNC_DEVICES) {
                                        "This vault already has $MAX_ACTIVE_SYNC_DEVICES active Android devices"
                                    }
                                    val member = SyncMembershipEntity.from(DeviceMembership(vaultId, result.peer.deviceId,
                                        if (result.peerPlatform == "windows") "Windows device" else "Android device",
                                        result.peer.publicKeyBase64Url, MemberStatus.ACTIVE, identity.deviceId,
                                        database.syncDao().membershipEvents(vaultId).size + 1L, 1))
                                    mutableState.value = PairingUiState("transferring", message = "Sending the encrypted vault copy…")
                                    val admission = SyncChannelOutput(channel).use { output ->
                                        SyncSnapshot(context, database, EncryptedPhotoStore(context))
                                            .export(output, key, result.key, member,
                                                windowsPeer = result.peerPlatform == "windows")
                                    }
                                    require(channel.receive().contentEquals("ready".toByteArray())) { "Enrollment was not prepared" }
                                    ensureActive()
                                    if (admission != null) database.withTransaction {
                                        database.syncDao().insertMembershipEvent(admission)
                                        database.syncDao().upsertMembership(member)
                                    }
                                    committedHost = true
                                    channel.send("commit".toByteArray())
                                    require(channel.receive().contentEquals("committed".toByteArray()))
                                    runCatching {
                                        LanSyncService.store(context).publish(database)
                                        LanSyncService.store(context).recordPeerAddress(result.peer.deviceId,
                                            socket.inetAddress.hostAddress!!)
                                    }
                                    mutableState.value = PairingUiState("paired", message = "The new device has the vault copy.")
                                }
                            } finally { result.key.fill(0) }
                        }
                        return@launch
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        if (mutableState.value.stage != "offering") throw failure
                        mutableState.value = PairingUiState("offering", invitation.encode(),
                            message = "A connection closed before confirmation. Scan this QR again.")
                    } finally { connection = null }
                }
                error("Pairing attempts exhausted")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (committedHost) {
                    runCatching { LanSyncService.store(context).publish(database) }
                    mutableState.value = PairingUiState("enrolled_pending", message =
                        "Enrollment was saved, but final confirmation was lost. If the other device has the vault, use Sync now. If it is still empty, pair that same device again.")
                } else mutableState.value = PairingUiState("failed", message =
                    if (reverseLink != null && mutableState.value.stage == "connecting" &&
                        (failure is ConnectException || failure is SocketTimeoutException ||
                            failure is java.net.NoRouteToHostException))
                        "Windows did not answer. Keep its QR open and allow Nuvori through the PC's firewall on this local network."
                    else pairingFailure(failure, mutableState.value.stage))
            }
            finally { listener?.close(); listener = null; key.fill(0); password.fill('\u0000') }
        }
    }

    fun join(database: VaultDatabase, localKey: ByteArray, link: String, masterPassword: CharArray) {
        val previous = job
        cancel()
        mutableState.value = PairingUiState("connecting", message = "QR read. Checking the invitation…")
        val key = localKey.copyOf()
        val password = masterPassword.copyOf()
        job = scope.launch {
            var committedJoin = false
            try {
                previous?.join()
                val invitation = PairingInvitation.decode(link.trim())
                require(database.dao().allEntries().isEmpty() && database.dao().allPasskeys().isEmpty() &&
                    database.dao().allGroupsWithEntries().isEmpty()) { "Join from an empty vault" }
                require(database.syncDao().memberships(requireNotNull(database.dao().settings()).vaultId)
                    .none { it.deviceId != AndroidDeviceIdentityStore(context).getOrCreate().deviceId &&
                        it.status == MemberStatus.ACTIVE.name }) { "This vault is already paired" }
                val (network, _) = wifi()
                val identity = AndroidDeviceIdentityStore(context).getOrCreate()
                mutableState.value = PairingUiState("connecting", message = "Connecting to the other phone…")
                val socket = Socket()
                connection = socket
                socket.use {
                    network.bindSocket(socket)
                    socket.connect(InetSocketAddress(InetAddresses.parseNumericAddress(invitation.address), invitation.port), 10_000)
                    socket.soTimeout = 30_000
                    val frames = SyncFrames(socket.getInputStream(), socket.getOutputStream())
                    val result = pairingHandshake(frames, identity, invitation.code.toCharArray(), invitation.session, invitation.vault, false)
                    try {
                        verifyMasterPassword(frames, identity, password, invitation.session,
                            invitation.vault, false, result.peer)
                        EncryptedSyncChannel(frames, result.key, false).use { channel ->
                            confirm(channel, result.confirmation)
                            socket.soTimeout = 300_000
                            mutableState.value = PairingUiState("transferring", message = "Receiving the encrypted vault copy…")
                            val snapshot = SyncSnapshot(context, database, EncryptedPhotoStore(context))
                            snapshot.prepare(SyncChannelInput(channel), key, result.key, invitation.vault, identity.deviceId,
                                result.peer).use { prepared ->
                                channel.send("ready".toByteArray())
                                require(channel.receive().contentEquals("commit".toByteArray()))
                                ensureActive()
                                snapshot.commit(prepared)
                                committedJoin = true
                                channel.send("committed".toByteArray())
                            }
                            runCatching {
                                LanSyncService.store(context).publish(database)
                                LanSyncService.store(context).recordPeerAddress(result.peer.deviceId,
                                    invitation.address)
                            }
                            mutableState.value = PairingUiState("paired", message = "Vault received. Both devices use the same master password.")
                        }
                    } finally { result.key.fill(0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = if (committedJoin)
                PairingUiState("paired", message = "Vault received. The other phone may still be waiting for confirmation.")
                else PairingUiState("failed", message = pairingFailure(failure, mutableState.value.stage)) }
            finally { connection = null; key.fill(0); password.fill('\u0000') }
        }
    }

    fun approve() { approval?.complete(true) }

    override fun close() { cancel(); scope.cancel() }

    fun cancel() {
        job?.cancel(); job = null
        approval?.cancel(); approval = null
        runCatching { connection?.close() }; connection = null
        runCatching { listener?.close() }; listener = null
        mutableState.value = PairingUiState()
    }

    private suspend fun confirm(channel: EncryptedSyncChannel, code: String) {
        val pending = CompletableDeferred<Boolean>()
        approval = pending
        mutableState.value = PairingUiState("confirm", confirmation = code,
            message = "Check that both devices show this code, then confirm on each device.")
        try { require(withTimeout(30_000) { pending.await() }) }
        finally { approval = null }
        channel.send("approved".toByteArray())
        require(channel.receive().contentEquals("approved".toByteArray())) { "The other phone did not approve" }
    }

    private fun verifyMasterPassword(frames: SyncFrames, identity: DeviceIdentity, password: CharArray,
        session: String, vaultId: String, creator: Boolean, peer: DeviceIdentity) {
        try {
            pairingHandshake(frames, identity, password.copyOf(), "$session-master", vaultId,
                creator, expectedPeer = peer, masterPassword = true).key.fill(0)
        } catch (_: Exception) {
            throw IllegalArgumentException("The master passwords do not match. Use the same password on both devices.")
        }
    }

    private fun wifi(): Pair<Network, InetAddress> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        for (network in manager.allNetworks) {
            if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) continue
            val address = preferredWifiAddress(manager.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }.orEmpty()) ?: continue
            return network to address
        }
        error("Connect to Wi-Fi to pair")
    }
}

private fun pairingFailure(failure: Exception, stage: String): String = when {
    failure.message == "Join from an empty vault" ->
        "This phone already has vault items. Pairing needs an empty vault; use Sync now if these phones are already paired."
    failure.message == "This vault is already paired" ->
        "This vault is already paired. Use Sync now instead of scanning the QR again."
    failure.message == "Connect to Wi-Fi to pair" -> "Connect this phone to Wi-Fi and try again."
    failure.message?.startsWith("The master passwords do not match") == true -> failure.message!!
    failure.message == "Invalid pairing link" || failure.message == "Pair using a reachable local network" ->
        "The QR is not a valid local pairing invitation. Generate a new QR on the other phone."
    failure is ConnectException || failure is java.net.NoRouteToHostException ->
        "QR read, but this device cannot reach the other device on the local network. Check the network route and retry."
    failure is SocketTimeoutException ->
        "The $stage step timed out. Keep both devices unlocked and retry with a new QR."
    failure is EOFException ->
        "The $stage connection closed on the other phone. Check its pairing message and retry."
    failure.message?.contains("active Android devices") == true ||
        failure.message?.contains("sync needs recovery") == true -> failure.message!!
    else -> "Pairing stopped during $stage. Check the other phone's message and retry with a new QR."
}

internal fun isPrivateAddress(address: InetAddress): Boolean = !address.isLoopbackAddress &&
    (address.isSiteLocalAddress || address.isLinkLocalAddress ||
        (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc))

internal fun preferredWifiAddress(addresses: List<InetAddress>): InetAddress? =
    addresses.firstOrNull { it.address.size == 4 && it.isSiteLocalAddress } ?:
        addresses.firstOrNull { isPrivateAddress(it) && !it.isLinkLocalAddress }
