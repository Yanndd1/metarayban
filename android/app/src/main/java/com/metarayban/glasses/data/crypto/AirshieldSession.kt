package com.metarayban.glasses.data.crypto

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Airshield session manager — handles the full encrypted connection lifecycle.
 *
 * Mirrors: StreamSecurerImpl from com.facebook.wearable.airshield.securer
 *
 * Flow:
 *  1. Connect TCP to glasses on port 20203 (WiFi Direct)
 *  2. Send handshake (0x80 + local ECDH public key)
 *  3. Receive handshake (0x80 + remote ECDH public key)
 *  4. Derive session keys (HKDF-SHA256)
 *  5. Exchange encrypted data frames (0x40 + nonce + ciphertext)
 *
 * The Preamble phase establishes crypto, then StreamSecurerImpl
 * handles ongoing encrypt/decrypt via Framing.pack()/unpack().
 */
class AirshieldSession(
    private val glassesIp: String = "192.168.49.66",
    private val port: Int = 20203,
) {
    companion object {
        private const val TAG = "AirshieldSession"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Maximum frame size (from PCAP: largest observed ~6KB) */
        private const val MAX_FRAME_SIZE = 65536
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        HANDSHAKING,
        AUTHENTICATED,
        TRANSFERRING,
        ERROR
    }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val crypto = AirshieldCrypto()
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    /** Raw captured packets for protocol analysis */
    private val capturedPackets = mutableListOf<CapturedPacket>()

    data class CapturedPacket(
        val direction: Direction,
        val prefix: Byte,
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class Direction { TX, RX }
    }

    /**
     * Connect to glasses and perform the Airshield handshake.
     *
     * Mirrors the flow in StreamSecurerImpl:
     *   start() → onSend(handshake) → receiveData(handshake) → onPreambleReady
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = State.CONNECTING
            Log.d(TAG, "Connecting to $glassesIp:$port")

            socket = Socket().apply {
                connect(java.net.InetSocketAddress(glassesIp, port), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
            inputStream = socket!!.getInputStream()
            outputStream = socket!!.getOutputStream()

            _state.value = State.HANDSHAKING
            performHandshake()
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _state.value = State.ERROR
            false
        }
    }

    /**
     * Perform the ECDH handshake.
     *
     * Mirrors: Preamble + CipherBuilder flow
     *  - StreamSecurerImpl.start() triggers handshake
     *  - CipherBuilder.setPrivateKey(generated)
     *  - Send: 0x80 + localPublicKey (64 bytes)
     *  - Receive: 0x80 + remotePublicKey (64 bytes)
     *  - CipherBuilder.setRemotePublicKey(received)
     *  - CipherBuilder.buildEncryptionFraming() / buildDecryptionFraming()
     */
    private suspend fun performHandshake(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Step 1: Generate and send our handshake
            val handshakePacket = crypto.buildHandshakePacket()
            sendRaw(handshakePacket)
            capturedPackets.add(
                CapturedPacket(CapturedPacket.Direction.TX, AirshieldCrypto.PREFIX_HANDSHAKE, handshakePacket)
            )
            Log.d(TAG, "Sent handshake: ${handshakePacket.size} bytes")

            // Step 2: Receive remote handshake
            val remotePacket = receivePacket()
            if (remotePacket == null || !crypto.isHandshakePacket(remotePacket)) {
                Log.e(TAG, "Invalid handshake response: ${remotePacket?.size ?: 0} bytes")
                _state.value = State.ERROR
                return@withContext false
            }
            capturedPackets.add(
                CapturedPacket(CapturedPacket.Direction.RX, AirshieldCrypto.PREFIX_HANDSHAKE, remotePacket)
            )
            Log.d(TAG, "Received handshake: ${remotePacket.size} bytes")

            // Step 3: Process handshake and derive keys
            // We are the initiator (phone = main/PeerA)
            if (!crypto.processHandshake(remotePacket, isMain = true)) {
                Log.e(TAG, "Handshake processing failed")
                _state.value = State.ERROR
                return@withContext false
            }

            _state.value = State.AUTHENTICATED
            Log.i(TAG, "Airshield handshake complete — session keys derived")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed", e)
            _state.value = State.ERROR
            false
        }
    }

    /**
     * Send an encrypted data frame.
     *
     * Mirrors: Stream.send(ByteBuffer) → Framing.pack() → onSend callback
     */
    suspend fun sendEncrypted(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!crypto.isSessionEstablished()) return@withContext false
        try {
            val frame = crypto.encrypt(data)
            sendRaw(frame)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            false
        }
    }

    /**
     * Receive and decrypt a data frame.
     *
     * Mirrors: StreamSecurerImpl.receiveData() → Framing.unpack() → Stream.handleReceived()
     */
    suspend fun receiveDecrypted(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val frame = receivePacket() ?: return@withContext null
            if (crypto.isDataPacket(frame)) {
                crypto.decrypt(frame)
            } else {
                Log.w(TAG, "Received non-data packet: prefix=0x${String.format("%02X", frame[0])}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receive failed", e)
            null
        }
    }

    /**
     * Capture raw protocol traffic for analysis (without decryption).
     * Useful for reverse-engineering the exact frame format.
     *
     * Mirrors: MediaTransferClient.captureRawPackets()
     */
    suspend fun captureRawTraffic(maxPackets: Int = 100): List<CapturedPacket> = withContext(Dispatchers.IO) {
        _state.value = State.TRANSFERRING
        val packets = mutableListOf<CapturedPacket>()

        try {
            repeat(maxPackets) {
                val packet = receivePacket() ?: return@withContext packets
                packets.add(
                    CapturedPacket(
                        direction = CapturedPacket.Direction.RX,
                        prefix = packet[0],
                        data = packet
                    )
                )

                if (crypto.isHandshakePacket(packet)) {
                    Log.d(TAG, "Captured handshake packet: ${packet.size} bytes")
                } else if (crypto.isDataPacket(packet)) {
                    Log.d(TAG, "Captured data packet: ${packet.size} bytes")
                    // Try to decrypt if session is established
                    if (crypto.isSessionEstablished()) {
                        val decrypted = crypto.decrypt(packet)
                        if (decrypted != null) {
                            Log.d(TAG, "Decrypted ${decrypted.size} bytes")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture ended: ${e.message}")
        }

        packets
    }

    /**
     * Disconnect and clean up.
     *
     * Mirrors: StreamSecurerImpl.stop()
     */
    fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
        _state.value = State.DISCONNECTED
    }

    /** Get all captured packets for analysis */
    fun getCapturedPackets(): List<CapturedPacket> = capturedPackets.toList()

    // ── Private I/O helpers ──────────────────────────────────────────────

    private fun sendRaw(data: ByteArray) {
        val os = outputStream ?: throw IllegalStateException("Not connected")
        // Length-prefixed framing: 4-byte BE length + data
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
        os.write(header)
        os.write(data)
        os.flush()
    }

    private fun receivePacket(): ByteArray? {
        val ins = inputStream ?: return null

        // Read 4-byte length header
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = ins.read(header, read, 4 - read)
            if (n == -1) return null
            read += n
        }

        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            Log.w(TAG, "Invalid frame length: $length")
            return null
        }

        // Read frame data
        val data = ByteArray(length)
        read = 0
        while (read < length) {
            val n = ins.read(data, read, length - read)
            if (n == -1) return null
            read += n
        }

        return data
    }
}
