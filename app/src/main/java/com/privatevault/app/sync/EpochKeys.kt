package com.privatevault.app.sync

import androidx.annotation.Keep
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * New sync keys when the managing device removes a device (feature [FEATURE]). Shared vectors:
 * `sync-vectors/epoch-keys-v1.json` (scripts/sync-vectors/epoch_keys.py); Windows `epoch_keys.rs`.
 * X25519 and HKDF come from Tink. Security notes: protocol-upgrade-design.md, section 11.
 *
 * - Each device has an X25519 key derived from its Ed25519 seed and publishes it in a key statement
 *   signed by its identity ([KeyStatement]). The manager rotates only when every remaining active
 *   member has a valid statement: a stripped hello feature can't downgrade the removal silently.
 * - At a REMOVE the manager makes a random transport secret S(N) and package key P(N), seals them to
 *   each survivor ([seal]) and signs the set of packages, bound to the REMOVE event ([PackageSet]).
 *   The content key is K(N) = HKDF(P(N), salt = K(1)) ([contentKey]): opening a package (an X25519 key a
 *   locked phone can use) gives S(N) for Wi-Fi and Drive, but K(N) also needs K(1), which lives only
 *   in SQLCipher behind the master password. A set with no packages is a signed "no new keys" marker.
 * - A change of epoch N carries `e<N>.` in its signed nonce field ([nonceField], [aad]).
 */
internal object EpochKeys {
    const val FEATURE = "epoch-keys-v1"
    private const val DEVICE_INFO = "nuvori-device-x25519-v1"
    private val ZERO = ByteArray(32)

    fun x25519Private(ed25519Seed: ByteArray): ByteArray {
        require(ed25519Seed.size == 32)
        return Hkdf.computeHkdf("HMACSHA256", ed25519Seed, ByteArray(0), DEVICE_INFO.toByteArray(Charsets.UTF_8), 32)
    }

    fun x25519Public(private: ByteArray): ByteArray = X25519.publicFromPrivate(private)

    /** A fixed scalar for [validPoint]: a point it maps to all-zero (or that Tink bans) is low-order or invalid. */
    private val PROBE = ByteArray(32) { 0x5A }

    /** True for a usable X25519 public key: not one of the low-order points that make every shared secret zero. */
    fun validPoint(public: ByteArray): Boolean = public.size == 32 && runCatching {
        val shared = X25519.computeSharedSecret(PROBE, public)
        try { !MessageDigest.isEqual(shared, ZERO) } finally { shared.fill(0) }
    }.getOrDefault(false)

    /** K(N) from the package key P(N) and the group's epoch-1 content key K(1) (only the open vault holds K(1)). */
    fun contentKey(epoch1Key: ByteArray, vaultId: String, epoch: Long, packageKey: ByteArray): ByteArray {
        require(epoch1Key.size == 32 && packageKey.size == 32 && epoch >= 2)
        return Hkdf.computeHkdf("HMACSHA256", packageKey, epoch1Key,
            "nuvori-epoch-content-v1:$vaultId:$epoch".toByteArray(Charsets.UTF_8), 32)
    }

    /** Canonical base64url only (no padding, unused bits zero), so the text of a signed value can't change. */
    fun strictDecode(text: String?): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(text!!).takeIf { Base64.getUrlEncoder().withoutPadding().encodeToString(it) == text }
    }.getOrNull()

    private fun wrapKey(shared: ByteArray, ephemeral: ByteArray, recipient: ByteArray, vaultId: String, epoch: Long,
        deviceId: String): ByteArray = Hkdf.computeHkdf("HMACSHA256", shared, ephemeral + recipient,
            "nuvori-epoch-key-v1:$vaultId:$epoch:$deviceId".toByteArray(Charsets.UTF_8), 32)

    /** `E || nonce || AES-256-GCM(S || P)` for [deviceId]'s X25519 key [recipient]. */
    fun seal(vaultId: String, epoch: Long, deviceId: String, recipient: ByteArray, transportSecret: ByteArray,
        packageKey: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val ephemeral = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        return try { sealWith(vaultId, epoch, deviceId, recipient, transportSecret, packageKey, ephemeral, nonce) }
        finally { ephemeral.fill(0) }
    }

    internal fun sealWith(vaultId: String, epoch: Long, deviceId: String, recipient: ByteArray, transportSecret: ByteArray,
        packageKey: ByteArray, ephemeral: ByteArray, nonce: ByteArray): ByteArray {
        require(epoch >= 2 && recipient.size == 32 && transportSecret.size == 32 && packageKey.size == 32 && nonce.size == 12)
        val public = X25519.publicFromPrivate(ephemeral)
        val shared = X25519.computeSharedSecret(ephemeral, recipient)
        try {
            require(!MessageDigest.isEqual(shared, ZERO)) { "Invalid device key" }
            val key = wrapKey(shared, public, recipient, vaultId, epoch, deviceId)
            val plain = transportSecret + packageKey
            return try {
                public + nonce + Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD("$vaultId:$epoch:$deviceId".toByteArray(Charsets.UTF_8))
                    doFinal(plain)
                }
            } finally { plain.fill(0); key.fill(0) }
        } finally { shared.fill(0) }
    }

    /** (S, P) from a package sealed to this device, or null when it is not for it or was changed. */
    fun open(vaultId: String, epoch: Long, deviceId: String, private: ByteArray, sealed: ByteArray): Pair<ByteArray, ByteArray>? {
        if (sealed.size != 32 + 12 + 64 + 16 || private.size != 32) return null
        val ephemeral = sealed.copyOfRange(0, 32)
        val shared = runCatching { X25519.computeSharedSecret(private, ephemeral) }.getOrNull() ?: return null
        try {
            if (MessageDigest.isEqual(shared, ZERO)) return null
            val key = wrapKey(shared, ephemeral, X25519.publicFromPrivate(private), vaultId, epoch, deviceId)
            val plain = try {
                runCatching {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 32, 12))
                        updateAAD("$vaultId:$epoch:$deviceId".toByteArray(Charsets.UTF_8))
                        doFinal(sealed, 44, sealed.size - 44)
                    }
                }.getOrNull()
            } finally { key.fill(0) }
            plain ?: return null
            return try { plain.copyOfRange(0, 32) to plain.copyOfRange(32, 64) } finally { plain.fill(0) }
        } finally { shared.fill(0) }
    }

    /** The epoch of a change's payload nonce: 1 for today's bare nonce, N for `e<N>.`; null when invalid. */
    fun epochOf(payloadNonce: String): Long? {
        // A bare base64url nonce has no '.', but may start with 'e': only a '.' marks the epoch form.
        if (!payloadNonce.startsWith("e") || '.' !in payloadNonce) return if (decode(payloadNonce)?.size == 12) 1L else null
        val dot = payloadNonce.indexOf('.')
        if (dot < 2) return null
        val digits = payloadNonce.substring(1, dot)
        if (digits.length > 10 || digits.startsWith("0") || !digits.all { it in '0'..'9' }) return null
        val epoch = digits.toLong()
        if (epoch < 2 || epoch > Int.MAX_VALUE) return null
        return if (decode(payloadNonce.substring(dot + 1))?.size == 12) epoch else null
    }

    fun nonceBytes(payloadNonce: String): ByteArray {
        requireNotNull(epochOf(payloadNonce)) { "Invalid sync payload nonce" }
        return requireNotNull(decode(payloadNonce.substringAfter('.'))).also { require(it.size == 12) }
    }

    fun nonceField(epoch: Long, nonce: ByteArray): String {
        require(epoch >= 1 && nonce.size == 12)
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        return if (epoch == 1L) text else "e$epoch.$text"
    }

    fun aad(entityType: String, vaultId: String, epoch: Long, mutationId: String, entityId: String): ByteArray =
        (if (epoch == 1L) "nuvori-sync-$entityType-v1:$vaultId:$mutationId:$entityId"
        else "nuvori-sync-$entityType-v2:$vaultId:$epoch:$mutationId:$entityId").toByteArray(Charsets.UTF_8)

    /**
     * The transport secret of [epoch]: its own random one when held, else HMAC-derived from the
     * newest random one below it (from the group's root when the group never rotated, as before).
     * A device removed at a rotation knows the root but never a random secret after it.
     */
    fun transportSecret(root: ByteArray, vaultId: String, epoch: Long, random: Map<Long, ByteArray>): ByteArray {
        require(epoch >= 1)
        random[epoch]?.let { return it.copyOf() }
        if (epoch == 1L) return root.copyOf()
        val base = random.filterKeys { it < epoch }.maxByOrNull { it.key }?.value ?: root
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(base, "HmacSHA256"))
            doFinal("nuvori-transport-epoch-v1:$vaultId:$epoch".toByteArray(Charsets.UTF_8))
        }
    }

    /** The secret the Google Drive key comes from at [epoch]: the newest random one at or below it, else the root. */
    fun driveSecret(root: ByteArray, epoch: Long, random: Map<Long, ByteArray>): Pair<Long, ByteArray> =
        random.filterKeys { it <= epoch }.maxByOrNull { it.key }?.let { it.key to it.value } ?: (1L to root)

    private fun decode(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
}

/** A device's X25519 key for one group, signed by its Ed25519 identity. */
@Keep
internal data class KeyStatement(val vaultId: String, val deviceId: String, val x25519: String, val signature: String) {
    fun signingBytes(): ByteArray = "nuvori-device-key-v1\n$vaultId\n$deviceId\n$x25519".toByteArray(Charsets.UTF_8)

    /** The X25519 key: canonical text, 32 bytes, and not a low-order point ([EpochKeys.validPoint]). */
    fun publicKey(): ByteArray? = EpochKeys.strictDecode(x25519)?.takeIf { EpochKeys.validPoint(it) }

    /** True when [identityPublicKey] (the member's signed key) signed it for [expectedVault]. */
    fun valid(expectedVault: String, identityPublicKey: String): Boolean = runCatching {
        vaultId == expectedVault && deviceId.isNotBlank() && publicKey() != null &&
            DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(identityPublicKey), signingBytes(),
                requireNotNull(EpochKeys.strictDecode(signature)))
    }.getOrDefault(false)

    companion object {
        fun create(vaultId: String, deviceId: String, x25519Public: ByteArray, signer: (ByteArray) -> ByteArray): KeyStatement {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val unsigned = KeyStatement(vaultId, deviceId, encoder.encodeToString(x25519Public), "")
            return unsigned.copy(signature = encoder.encodeToString(signer(unsigned.signingBytes())))
        }

        fun of(identityStore: AndroidDeviceIdentityStore, vaultId: String): KeyStatement {
            val identity = identityStore.getOrCreate()
            val private = identityStore.x25519Private()
            return try { create(vaultId, identity.deviceId, EpochKeys.x25519Public(private), identityStore::sign) }
            finally { private.fill(0) }
        }
    }
}

/**
 * The packages of one epoch, signed by the managing device and bound to the REMOVE event that
 * started the epoch. [packages] maps a survivor's device ID to its sealed package (base64url).
 */
@Keep
internal data class PackageSet(val vaultId: String, val epoch: Long, val removeHash: String,
    val packages: Map<String, String>, val signature: String) {
    fun signingBytes(): ByteArray = buildString {
        append("nuvori-epoch-keys-v1\n").append(vaultId).append('\n').append(epoch).append('\n').append(removeHash).append('\n')
        packages.toSortedMap(UTF8_ORDER).forEach { (device, sealed) ->
                val digest = MessageDigest.getInstance("SHA-256").digest(requireNotNull(EpochKeys.strictDecode(sealed)))
                append(device).append(':').append(digest.joinToString("") { "%02x".format(it) }).append('\n')
            }
    }.toByteArray(Charsets.UTF_8)

    /**
     * True when the manager [managerPublicKey] signed it for the REMOVE at [removeHash] in this group,
     * every package is canonical base64url of 124 bytes, and the set is not too large. An empty set is
     * a signed marker: that REMOVE made no new keys ([EpochKeyRotation.awaitedEpoch]).
     */
    fun valid(expectedVault: String, managerPublicKey: String, expectedRemoveHash: String): Boolean = runCatching {
        @Suppress("SENSELESS_COMPARISON")
        require(packages != null && vaultId != null && removeHash != null && signature != null)
        vaultId == expectedVault && epoch >= 2 && removeHash == expectedRemoveHash && packages.size in 0..MAX_ACTIVE_SYNC_DEVICES &&
            packages.all { (device, sealed) -> device.isNotBlank() && EpochKeys.strictDecode(sealed)?.size == 124 } &&
            DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(managerPublicKey), signingBytes(),
                requireNotNull(EpochKeys.strictDecode(signature)))
    }.getOrDefault(false)

    companion object {
        /** Device IDs sorted by their UTF-8 bytes, as the vectors and Windows sort them. */
        private val UTF8_ORDER = Comparator<String> { left, right ->
            val a = left.toByteArray(Charsets.UTF_8)
            val b = right.toByteArray(Charsets.UTF_8)
            (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] }
                ?.let { (a[it].toInt() and 0xff) - (b[it].toInt() and 0xff) } ?: (a.size - b.size)
        }

        fun sign(vaultId: String, epoch: Long, removeHash: String, packages: Map<String, ByteArray>,
            signer: (ByteArray) -> ByteArray): PackageSet {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val unsigned = PackageSet(vaultId, epoch, removeHash, packages.mapValues { encoder.encodeToString(it.value) }, "")
            return unsigned.copy(signature = encoder.encodeToString(signer(unsigned.signingBytes())))
        }
    }
}
