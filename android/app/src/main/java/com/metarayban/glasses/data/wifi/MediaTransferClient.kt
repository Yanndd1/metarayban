package com.metarayban.glasses.data.wifi

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.metarayban.glasses.data.ble.MetaProtocol
import com.metarayban.glasses.data.model.MediaFile
import com.metarayban.glasses.data.model.MediaType
import com.metarayban.glasses.data.model.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client for the proprietary media transfer protocol over WiFi Direct.
 *
 * Reverse-engineered from Meta View APK (com.facebook.stella):
 *
 * Protocol stack:
 * ┌───────────────────────────┐
 * │  MediaExchange API        │ ← This class
 * ├───────────────────────────┤
 * │  DataX framing            │ ← Message routing (TypedBuffer)
 * ├───────────────────────────┤
 * │  Airshield encryption     │ ← ECDH + HKDF + AES-GCM (likely)
 * ├───────────────────────────┤
 * │  TCP socket               │ ← Port 20203 on 192.168.49.x
 * └───────────────────────────┘
 *
 * IMPORTANT: The transfer protocol is encrypted with Airshield.
 * Phase 2 will focus on either:
 * 1. Reverse-engineering libairshield_jni.so to replicate the crypto
 * 2. Using Frida to hook StreamSecurerImpl and intercept plaintext
 * 3. Extracting bonding keys to establish our own Airshield session
 *
 * For now, this class implements the raw TCP connection and observes
 * the protocol structure to help with further reverse engineering.
 */
class MediaTransferClient(private val context: Context) {

    companion object {
        private const val TAG = "MediaTransfer"
        private const val SAVE_DIRECTORY = "MetaRayBan"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private val _transferState = MutableStateFlow(TransferState())
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null

    /**
     * Connect to the glasses' transfer server over WiFi Direct.
     *
     * @param glassesIp IP address of the glasses on the WiFi Direct subnet
     *                  (typically 192.168.49.66, but may vary)
     * @return true if TCP connection established
     */
    suspend fun connect(
        glassesIp: String = MetaProtocol.WIFI_DIRECT_GLASSES_IP
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to $glassesIp:${MetaProtocol.TRANSFER_PORT}")

            val sock = Socket()
            sock.connect(
                InetSocketAddress(glassesIp, MetaProtocol.TRANSFER_PORT),
                CONNECT_TIMEOUT_MS
            )
            sock.soTimeout = READ_TIMEOUT_MS

            socket = sock
            inputStream = DataInputStream(sock.getInputStream())
            outputStream = DataOutputStream(sock.getOutputStream())

            Log.d(TAG, "Connected to glasses transfer server")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            false
        }
    }

    /**
     * Perform the Airshield handshake.
     *
     * From PCAP analysis:
     * 1. Phone → Glasses: 0x80 + 64-byte public key
     * 2. Glasses → Phone: 0x80 + 64-byte public key response
     * 3. Key derivation via HKDF
     * 4. Encrypted data exchange with 0x40 prefix
     *
     * TODO Phase 2: Implement actual ECDH key exchange.
     * For now, this reads and logs the handshake to help reverse engineering.
     */
    suspend fun observeHandshake(): HandshakeResult = withContext(Dispatchers.IO) {
        val input = inputStream ?: return@withContext HandshakeResult.NotConnected

        try {
            // Read the first packet from the glasses
            val firstByte = input.readByte()
            Log.d(TAG, "First byte from glasses: 0x${String.format("%02X", firstByte)}")

            if (firstByte == MetaProtocol.AIRSHIELD_HANDSHAKE_PREFIX) {
                // This is a handshake packet — read the rest
                // The exact length depends on the Airshield version
                val buffer = ByteArray(1024)
                val read = input.read(buffer)
                Log.d(TAG, "Handshake packet: $read bytes")
                Log.d(TAG, "Handshake hex: ${buffer.take(read).joinToString("") {
                    String.format("%02X", it)
                }}")

                HandshakeResult.HandshakeReceived(
                    prefix = firstByte,
                    payload = buffer.copyOf(read)
                )
            } else {
                Log.w(TAG, "Unexpected first byte: 0x${String.format("%02X", firstByte)}")
                HandshakeResult.UnexpectedData(firstByte)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake observation failed: ${e.message}", e)
            HandshakeResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Read raw packets from the transfer stream.
     *
     * Used for protocol analysis — captures packets and logs them.
     * In Phase 2, this will be replaced with actual Airshield decryption.
     *
     * @param maxPackets Maximum number of packets to capture
     * @return List of captured packets with metadata
     */
    suspend fun captureRawPackets(maxPackets: Int = 100): List<CapturedPacket> =
        withContext(Dispatchers.IO) {
            val input = inputStream ?: return@withContext emptyList()
            val packets = mutableListOf<CapturedPacket>()

            try {
                repeat(maxPackets) {
                    val prefix = input.readByte()
                    val buffer = ByteArray(8192)
                    val read = input.read(buffer)

                    if (read > 0) {
                        val packet = CapturedPacket(
                            index = packets.size,
                            prefix = prefix,
                            data = buffer.copyOf(read),
                            isHandshake = prefix == MetaProtocol.AIRSHIELD_HANDSHAKE_PREFIX,
                            isData = prefix == MetaProtocol.AIRSHIELD_DATA_PREFIX
                        )
                        packets.add(packet)

                        Log.d(TAG, "Packet #${packet.index}: prefix=0x${
                            String.format("%02X", prefix)
                        }, size=$read, type=${
                            when {
                                packet.isHandshake -> "HANDSHAKE"
                                packet.isData -> "DATA"
                                else -> "UNKNOWN"
                            }
                        }")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Capture ended: ${e.message} (${packets.size} packets)")
            }

            packets
        }

    /**
     * Save a media file to the device gallery using MediaStore.
     *
     * This will be used once we can decrypt the Airshield payloads.
     */
    suspend fun saveToGallery(
        filename: String,
        data: ByteArray,
        mediaType: MediaType
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val mimeType = when (mediaType) {
                MediaType.PHOTO -> "image/jpeg"
                MediaType.VIDEO -> "video/mp4"
                MediaType.UNKNOWN -> "application/octet-stream"
            }

            val collection = when (mediaType) {
                MediaType.PHOTO -> MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                MediaType.UNKNOWN -> MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
            }

            val relativePath = when (mediaType) {
                MediaType.PHOTO -> "${Environment.DIRECTORY_DCIM}/$SAVE_DIRECTORY"
                MediaType.VIDEO -> "${Environment.DIRECTORY_DCIM}/$SAVE_DIRECTORY"
                MediaType.UNKNOWN -> "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_DIRECTORY"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(collection, values)
                ?: return@withContext false

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }

            Log.d(TAG, "Saved to gallery: $filename (${data.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save: ${e.message}", e)
            false
        }
    }

    /**
     * Disconnect from the glasses.
     */
    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
        inputStream = null
        outputStream = null
        socket = null
        Log.d(TAG, "Disconnected from transfer server")
    }
}

/**
 * Result of the Airshield handshake observation.
 */
sealed class HandshakeResult {
    data object NotConnected : HandshakeResult()
    data class HandshakeReceived(val prefix: Byte, val payload: ByteArray) : HandshakeResult()
    data class UnexpectedData(val firstByte: Byte) : HandshakeResult()
    data class Error(val message: String) : HandshakeResult()
}

/**
 * A captured raw packet from the transfer stream.
 */
data class CapturedPacket(
    val index: Int,
    val prefix: Byte,
    val data: ByteArray,
    val isHandshake: Boolean,
    val isData: Boolean,
) {
    val hexPreview: String
        get() = data.take(32).joinToString("") { String.format("%02X", it) }
}
