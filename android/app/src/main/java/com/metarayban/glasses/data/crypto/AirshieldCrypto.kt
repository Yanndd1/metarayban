package com.metarayban.glasses.data.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Airshield encryption layer — pure Kotlin implementation.
 *
 * Reverse-engineered from com.facebook.wearable.airshield.* classes in Meta View APK.
 *
 * Protocol flow:
 *  1. Both sides generate ephemeral ECDH P-256 key pairs
 *  2. Exchange 64-byte public keys (uncompressed x||y, prefixed with 0x80)
 *  3. Compute shared secret via ECDH
 *  4. Derive session keys via HKDF-SHA256
 *  5. Encrypt/decrypt frames with AES-256-GCM
 *
 * Classes mirrored from APK:
 *  - StreamSecurerImpl → AirshieldSession
 *  - CipherBuilder     → buildCipher()
 *  - Framing           → AirshieldFraming
 *  - Preamble          → AirshieldPreamble
 */
class AirshieldCrypto {

    companion object {
        /** Frame prefix: handshake (ECDH public key exchange) */
        const val PREFIX_HANDSHAKE: Byte = 0x80.toByte()

        /** Frame prefix: encrypted data */
        const val PREFIX_DATA: Byte = 0x40

        /** P-256 public key size (uncompressed x||y without 04 header) */
        const val PUBLIC_KEY_SIZE = 64

        /** AES-256-GCM key size */
        const val AES_KEY_SIZE = 32

        /** AES-GCM IV/nonce size */
        const val GCM_NONCE_SIZE = 12

        /** AES-GCM authentication tag size in bits */
        const val GCM_TAG_BITS = 128

        /** HKDF-SHA256 hash output size */
        const val HASH_SIZE = 32

        private const val EC_CURVE = "secp256r1"
        private const val HMAC_ALGO = "HmacSHA256"
        private const val AES_ALGO = "AES/GCM/NoPadding"
    }

    private var localKeyPair: KeyPair? = null
    private var remotePublicKeyBytes: ByteArray? = null
    private var sharedSecret: ByteArray? = null
    private var txKey: ByteArray? = null
    private var rxKey: ByteArray? = null
    private var txNonce: ByteArray? = null
    private var rxNonce: ByteArray? = null
    private var txCounter: Long = 0
    private var rxCounter: Long = 0
    private var isInitiator: Boolean = true

    /**
     * Generate a local ECDH P-256 key pair and return the 64-byte public key.
     *
     * Mirrors: PrivateKey.generate() + PrivateKey.recoverPublicKey()
     */
    fun generateKeyPair(): ByteArray {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE))
        localKeyPair = kpg.generateKeyPair()

        val ecPub = localKeyPair!!.public as ECPublicKey
        return encodePublicKey(ecPub)
    }

    /**
     * Build the handshake packet: PREFIX_HANDSHAKE + 64-byte public key.
     *
     * Mirrors: Preamble construction
     */
    fun buildHandshakePacket(): ByteArray {
        val pubKey = generateKeyPair()
        val packet = ByteArray(1 + PUBLIC_KEY_SIZE)
        packet[0] = PREFIX_HANDSHAKE
        System.arraycopy(pubKey, 0, packet, 1, PUBLIC_KEY_SIZE)
        return packet
    }

    /**
     * Process a received handshake packet and derive session keys.
     *
     * Mirrors: CipherBuilder.setRemotePublicKey() + HKDF.calculateNative()
     *
     * @param packet Raw packet starting with PREFIX_HANDSHAKE
     * @param isMain Whether this device is the "main" (initiator) side
     * @return true if handshake completed successfully
     */
    fun processHandshake(packet: ByteArray, isMain: Boolean = true): Boolean {
        if (packet.isEmpty() || packet[0] != PREFIX_HANDSHAKE) return false
        if (packet.size < 1 + PUBLIC_KEY_SIZE) return false

        isInitiator = isMain
        remotePublicKeyBytes = packet.copyOfRange(1, 1 + PUBLIC_KEY_SIZE)

        if (localKeyPair == null) {
            generateKeyPair()
        }

        // ECDH key agreement — mirrors PrivateKey.derive(PublicKey) -> Hash
        val remoteKey = decodePublicKey(remotePublicKeyBytes!!)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(localKeyPair!!.private)
        ka.doPhase(remoteKey, true)
        sharedSecret = ka.generateSecret()

        // Derive session keys via HKDF-SHA256
        // Mirrors: HKDF.calculateNative() and CipherBuilder internals
        deriveSessionKeys()

        return true
    }

    /**
     * Encrypt a data frame.
     *
     * Mirrors: Framing.pack() → Cipher.update()
     *
     * @param plaintext Raw data to encrypt
     * @return Framed encrypted packet: PREFIX_DATA + nonce(12) + ciphertext + tag(16)
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = txKey ?: throw IllegalStateException("Handshake not completed")
        val nonce = incrementNonce(txNonce!!, txCounter++)

        val cipher = Cipher.getInstance(AES_ALGO)
        val spec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)

        val ciphertext = cipher.doFinal(plaintext)

        // Frame: prefix + nonce + ciphertext (includes GCM tag)
        val frame = ByteArray(1 + GCM_NONCE_SIZE + ciphertext.size)
        frame[0] = PREFIX_DATA
        System.arraycopy(nonce, 0, frame, 1, GCM_NONCE_SIZE)
        System.arraycopy(ciphertext, 0, frame, 1 + GCM_NONCE_SIZE, ciphertext.size)
        return frame
    }

    /**
     * Decrypt a received data frame.
     *
     * Mirrors: Framing.unpack() → Cipher.update()
     *
     * @param frame Framed encrypted packet
     * @return Decrypted plaintext, or null if decryption fails
     */
    fun decrypt(frame: ByteArray): ByteArray? {
        val key = rxKey ?: throw IllegalStateException("Handshake not completed")
        if (frame.isEmpty() || frame[0] != PREFIX_DATA) return null
        if (frame.size < 1 + GCM_NONCE_SIZE + 16) return null // minimum: prefix + nonce + tag

        val nonce = frame.copyOfRange(1, 1 + GCM_NONCE_SIZE)
        val ciphertext = frame.copyOfRange(1 + GCM_NONCE_SIZE, frame.size)

        return try {
            val cipher = Cipher.getInstance(AES_ALGO)
            val spec = GCMParameterSpec(GCM_TAG_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a raw packet is a handshake packet.
     */
    fun isHandshakePacket(packet: ByteArray): Boolean =
        packet.isNotEmpty() && packet[0] == PREFIX_HANDSHAKE

    /**
     * Check if a raw packet is an encrypted data packet.
     */
    fun isDataPacket(packet: ByteArray): Boolean =
        packet.isNotEmpty() && packet[0] == PREFIX_DATA

    /**
     * Get the current session state.
     */
    fun isSessionEstablished(): Boolean = txKey != null && rxKey != null

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Derive tx/rx keys and nonces from the shared secret using HKDF-SHA256.
     *
     * Mirrors the CipherBuilder flow:
     *   CipherBuilder.setPrivateKey(local)
     *   CipherBuilder.setRemotePublicKey(remote)
     *   CipherBuilder.setSeed(seed)
     *   CipherBuilder.buildEncryptionFraming() / buildDecryptionFraming()
     *
     * The exact info/salt strings are determined by Airshield's native code.
     * We use a reasonable derivation that matches the structure.
     */
    private fun deriveSessionKeys() {
        val secret = sharedSecret ?: throw IllegalStateException("No shared secret")

        // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
        val salt = ByteArray(HASH_SIZE) // zero salt (common default)
        val prk = hkdfExtract(salt, secret)

        // HKDF-Expand for 4 keys: tx_key, rx_key, tx_nonce, rx_nonce
        // The initiator's tx is the responder's rx and vice versa
        val keyMaterial = hkdfExpand(prk, "airshield-keys".toByteArray(), AES_KEY_SIZE * 2 + GCM_NONCE_SIZE * 2)

        val key1 = keyMaterial.copyOfRange(0, AES_KEY_SIZE)
        val key2 = keyMaterial.copyOfRange(AES_KEY_SIZE, AES_KEY_SIZE * 2)
        val nonce1 = keyMaterial.copyOfRange(AES_KEY_SIZE * 2, AES_KEY_SIZE * 2 + GCM_NONCE_SIZE)
        val nonce2 = keyMaterial.copyOfRange(AES_KEY_SIZE * 2 + GCM_NONCE_SIZE, keyMaterial.size)

        if (isInitiator) {
            txKey = key1; rxKey = key2
            txNonce = nonce1; rxNonce = nonce2
        } else {
            txKey = key2; rxKey = key1
            txNonce = nonce2; rxNonce = nonce1
        }

        txCounter = 0
        rxCounter = 0
    }

    /**
     * HKDF-Extract (RFC 5869): PRK = HMAC-SHA256(salt, IKM)
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(salt, HMAC_ALGO))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand (RFC 5869): OKM = T(1) || T(2) || ... || T(N)
     * where T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(prk, HMAC_ALGO))

        val n = (length + HASH_SIZE - 1) / HASH_SIZE
        val okm = ByteArray(n * HASH_SIZE)
        var prev = ByteArray(0)

        for (i in 1..n) {
            mac.reset()
            mac.update(prev)
            mac.update(info)
            mac.update(i.toByte())
            prev = mac.doFinal()
            System.arraycopy(prev, 0, okm, (i - 1) * HASH_SIZE, HASH_SIZE)
        }

        return okm.copyOfRange(0, length)
    }

    /**
     * Increment a nonce by XORing with a counter value.
     * Common pattern in AES-GCM to ensure nonce uniqueness.
     */
    private fun incrementNonce(baseNonce: ByteArray, counter: Long): ByteArray {
        val nonce = baseNonce.copyOf()
        // XOR the counter into the last 8 bytes of the nonce
        for (i in 0 until 8) {
            nonce[GCM_NONCE_SIZE - 1 - i] = (nonce[GCM_NONCE_SIZE - 1 - i].toInt() xor
                ((counter shr (i * 8)) and 0xFF).toInt()).toByte()
        }
        return nonce
    }

    /**
     * Encode an EC public key as 64 bytes (uncompressed x||y, no 04 header).
     * Mirrors: PublicKey.serialize()
     */
    private fun encodePublicKey(key: ECPublicKey): ByteArray {
        val point = key.w
        val x = point.affineX.toByteArray().let { padOrTrim(it, 32) }
        val y = point.affineY.toByteArray().let { padOrTrim(it, 32) }
        return x + y
    }

    /**
     * Decode a 64-byte public key back to ECPublicKey.
     * Mirrors: PublicKey.from(byte[])
     */
    private fun decodePublicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == PUBLIC_KEY_SIZE) { "Invalid public key size: ${raw.size}" }

        val x = java.math.BigInteger(1, raw.copyOfRange(0, 32))
        val y = java.math.BigInteger(1, raw.copyOfRange(32, 64))
        val point = ECPoint(x, y)

        val kf = KeyFactory.getInstance("EC")
        // Get the curve parameters from a generated key
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE))
        val params = (kpg.generateKeyPair().public as ECPublicKey).params
        val spec = ECPublicKeySpec(point, params)
        return kf.generatePublic(spec) as ECPublicKey
    }

    /**
     * Pad or trim a BigInteger byte array to exactly `size` bytes.
     */
    private fun padOrTrim(bytes: ByteArray, size: Int): ByteArray {
        return when {
            bytes.size == size -> bytes
            bytes.size > size -> bytes.copyOfRange(bytes.size - size, bytes.size)
            else -> ByteArray(size - bytes.size) + bytes
        }
    }
}
