package com.privatevault.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val DEVICE_IDENTITY_FORMAT_VERSION = 1
const val DEVICE_IDENTITY_ALGORITHM = "Ed25519"

data class DeviceIdentity(
    val deviceId: String,
    val publicKey: ByteArray,
) {
    val publicKeyBase64Url: String
        get() = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)
}

object DeviceIdentityCrypto {
    const val PRIVATE_SEED_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    fun publicKey(privateSeed: ByteArray): ByteArray {
        require(privateSeed.size == PRIVATE_SEED_BYTES) { "Ed25519 seed must be 32 bytes" }
        return Ed25519PrivateKeyParameters(privateSeed, 0).generatePublicKey().encoded
    }

    fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
        require(privateSeed.size == PRIVATE_SEED_BYTES) { "Ed25519 seed must be 32 bytes" }
        return Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
            update(message, 0, message.size)
            generateSignature()
        }
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        return Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }
}

class AndroidDeviceIdentityStore(
    context: Context,
    private val random: SecureRandom = SecureRandom(),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getOrCreate(): DeviceIdentity {
        if (!preferences.contains(FORMAT_KEY)) return create()
        check(preferences.getInt(FORMAT_KEY, 0) == DEVICE_IDENTITY_FORMAT_VERSION) {
            "Unsupported device identity format"
        }

        return load("")
    }

    fun sign(message: ByteArray): ByteArray {
        getOrCreate()
        return signWith("", message)
    }

    /**
     * This device's X25519 private key for epoch-key packages ([EpochKeys.x25519Private]), derived
     * from the identity seed. Usable while the vault is locked, as signing is. Zero it after use.
     */
    fun x25519Private(): ByteArray {
        getOrCreate()
        val deviceId = preferences.getString(DEVICE_ID_KEY, null) ?: error("Missing device ID")
        val privateSeed = unwrapSeed("", deviceId)
        return try { EpochKeys.x25519Private(privateSeed) } finally { privateSeed.fill(0) }
    }

    /**
     * The identity this device switches to when it leaves or learns it was removed (see
     * [startIndependentSyncGroup]): it signs the new group's genesis before [promoteNext] makes it
     * the identity in use. The old group keeps the current identity as a member (or as removed)
     * forever and never adds the same device ID or key again, so only a new identity lets this
     * device pair with that group again. A prepared identity was never in any group: when a leave
     * fails, the next leave uses it.
     */
    fun prepareNext(): DeviceIdentity {
        next()?.let { return it }
        val current = getOrCreate()
        var created: DeviceIdentity
        do { created = create(NEXT_PREFIX) } while (created.deviceId == current.deviceId ||
            created.publicKey.contentEquals(current.publicKey))
        return created
    }

    /** The prepared next identity, or null. */
    fun next(): DeviceIdentity? =
        if (preferences.getString(NEXT_PREFIX + DEVICE_ID_KEY, null).isNullOrBlank()) null else load(NEXT_PREFIX)

    fun signWithNext(message: ByteArray): ByteArray {
        requireNotNull(next()) { "No next device identity" }
        return signWith(NEXT_PREFIX, message)
    }

    /**
     * Makes the prepared identity the one in use, in one preferences commit. The old wrapped seed
     * is overwritten, so the old identity can never sign again and is never reused. Signatures it
     * made still verify: peers check them against its public key in the old signed membership.
     * Null when nothing was prepared.
     */
    fun promoteNext(): DeviceIdentity? {
        val next = next() ?: return null
        val values = listOf(DEVICE_ID_KEY, PUBLIC_KEY, NONCE_KEY, WRAPPED_SEED_KEY)
            .associateWith { requireNotNull(preferences.getString(NEXT_PREFIX + it, null)) }
        check(preferences.edit().apply {
            putInt(FORMAT_KEY, DEVICE_IDENTITY_FORMAT_VERSION)
            values.forEach { (key, value) -> putString(key, value); remove(NEXT_PREFIX + key) }
        }.commit()) { "Could not persist device identity" }
        return next
    }

    /**
     * Forgets this store's identity. The Keystore wrapping key ([KEY_ALIAS]) stays: it is shared by every
     * identity store on the device, and deleting it here made every other stored identity unreadable (tests
     * clear their own stores, and that broke the app's own identity: "Signature/MAC verification failed" at
     * unlock). Without its preferences, the wrapped seed is gone, so keeping the key exposes nothing.
     */
    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear device identity" }
    }

    private fun load(prefix: String): DeviceIdentity {
        val deviceId = preferences.getString(prefix + DEVICE_ID_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?: error("Missing device ID")
        val storedPublicKey = decode(
            preferences.getString(prefix + PUBLIC_KEY, null) ?: error("Missing identity public key"),
        )
        val privateSeed = unwrapSeed(prefix, deviceId)
        return try {
            val derivedPublicKey = DeviceIdentityCrypto.publicKey(privateSeed)
            check(storedPublicKey.contentEquals(derivedPublicKey)) { "Device identity is inconsistent" }
            DeviceIdentity(deviceId, derivedPublicKey)
        } finally {
            privateSeed.fill(0)
        }
    }

    private fun signWith(prefix: String, message: ByteArray): ByteArray {
        val deviceId = preferences.getString(prefix + DEVICE_ID_KEY, null) ?: error("Missing device ID")
        val privateSeed = unwrapSeed(prefix, deviceId)
        return try {
            DeviceIdentityCrypto.sign(privateSeed, message)
        } finally {
            privateSeed.fill(0)
        }
    }

    private fun create(prefix: String = ""): DeviceIdentity {
        val deviceId = UUID.randomUUID().toString()
        val privateSeed = ByteArray(DeviceIdentityCrypto.PRIVATE_SEED_BYTES).also(random::nextBytes)
        return try {
            val publicKey = DeviceIdentityCrypto.publicKey(privateSeed)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey())
                updateAAD(aad(deviceId))
            }
            val wrappedSeed = cipher.doFinal(privateSeed)
            check(
                preferences.edit().apply {
                    if (prefix.isEmpty()) putInt(FORMAT_KEY, DEVICE_IDENTITY_FORMAT_VERSION)
                    putString(prefix + DEVICE_ID_KEY, deviceId)
                    putString(prefix + PUBLIC_KEY, encode(publicKey))
                    putString(prefix + NONCE_KEY, encode(cipher.iv))
                    putString(prefix + WRAPPED_SEED_KEY, encode(wrappedSeed))
                }.commit(),
            ) { "Could not persist device identity" }
            DeviceIdentity(deviceId, publicKey)
        } finally {
            privateSeed.fill(0)
        }
    }

    private fun unwrapSeed(prefix: String, deviceId: String): ByteArray {
        val nonce = decode(preferences.getString(prefix + NONCE_KEY, null) ?: error("Missing identity nonce"))
        val wrappedSeed = decode(
            preferences.getString(prefix + WRAPPED_SEED_KEY, null) ?: error("Missing wrapped identity seed"),
        )
        return unwrapSeed(deviceId, nonce, wrappedSeed)
    }

    private fun unwrapSeed(deviceId: String, nonce: ByteArray, wrappedSeed: ByteArray): ByteArray {
        val seed = Cipher.getInstance(CIPHER_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, nonce))
            updateAAD(aad(deviceId))
            doFinal(wrappedSeed)
        }
        check(seed.size == DeviceIdentityCrypto.PRIVATE_SEED_BYTES) { "Invalid identity seed" }
        return seed
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(!keyStore.containsAlias(KEY_ALIAS)) { "Invalid identity wrapping key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun aad(deviceId: String) = "$AAD_PREFIX:$deviceId".toByteArray(Charsets.UTF_8)

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val PREFERENCES_NAME = "device_identity"
        const val FORMAT_KEY = "format"
        const val DEVICE_ID_KEY = "device_id"
        const val PUBLIC_KEY = "public_key"
        const val NONCE_KEY = "nonce"
        const val WRAPPED_SEED_KEY = "wrapped_seed"
        const val KEY_ALIAS = "nuvori_device_identity_wrap_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AAD_PREFIX = "nuvori-device-identity-v1"
        /** Keys of the prepared next identity ([prepareNext]); the same fields with this prefix. */
        const val NEXT_PREFIX = "next_"
    }
}
