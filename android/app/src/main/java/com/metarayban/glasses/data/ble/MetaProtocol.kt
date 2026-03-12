package com.metarayban.glasses.data.ble

import java.util.UUID

/**
 * Meta Ray-Ban BLE + WiFi Direct protocol constants.
 *
 * Reverse-engineered from:
 * - GATT exploration of Ray-Ban Meta V1 (RB Meta 00WJ)
 * - PCAP capture of WiFi Direct media transfer (TCP port 20203)
 * - BLE snoop log analysis (btsnoop_hci.log)
 * - Meta View APK (com.facebook.stella) decompilation
 *
 * Protocol stack (top to bottom):
 *   MediaExchange → WifiFetchManager → DataX → Airshield → WiFi Direct TCP
 */
object MetaProtocol {

    // ── BLE Identifiers ─────────────────────────────────────────────────

    /** Meta BLE service UUID (16-bit: 0xFD5F) */
    val META_SERVICE_UUID: UUID = UUID.fromString("0000fd5f-0000-1000-8000-00805f9b34fb")

    /** BLE manufacturer ID in advertisement data */
    const val MANUFACTURER_ID = 0x01AB

    /** Known device name prefixes */
    val DEVICE_NAME_PREFIXES = listOf("RB Meta", "Ray-Ban", "Meta")

    // ── GATT Characteristics (Meta 0xFD5F service) ──────────────────────

    /**
     * Command/Notify characteristic.
     * Properties: notify, read (AUTH_REQUIRED)
     * Main bidirectional command channel using FlatBuffers serialization.
     * Handle: 41 (from GATT map)
     *
     * FlatBuffers schema namespace: stella.srvs.*
     * Key commands: StartWebserverRequest, StaModeConnectRequest, etc.
     */
    val CHAR_COMMAND: UUID = UUID.fromString("05acbe9f-6f61-4ca9-80bf-c8bbb52991c0")

    /**
     * Notification enable characteristic.
     * Write 0x0100 to enable notifications on CHAR_COMMAND.
     * Handle: 40 (from GATT map)
     */
    val CHAR_NOTIFY_ENABLE: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Status characteristic.
     * Properties: read
     * Handle: 44 (from GATT map)
     * Known values: 0x8100 = connected
     */
    val CHAR_STATUS: UUID = UUID.fromString("c53673dd-e411-47c7-8fa2-3a8413578e77")

    /**
     * Flags characteristic.
     * Properties: read
     * Handle: 46 (from GATT map)
     * Observed values: ff0300, ff0402, ff01ff, ff0400, ff020c
     */
    val CHAR_FLAGS: UUID = UUID.fromString("f9fbf15d-c73d-4e7a-b498-5f1f2a0853a0")

    // ── Standard GATT Services ──────────────────────────────────────────

    val GENERIC_ACCESS_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration Descriptor (for enabling notifications) */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Firmware revision characteristic (Device Information service) */
    val CHAR_FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    /** Device name characteristic */
    val CHAR_DEVICE_NAME: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    // ── WiFi Direct (P2P) Configuration ─────────────────────────────────

    /**
     * WiFi Direct P2P group SSID prefix.
     * The phone creates a P2P group as Group Owner with this prefix.
     * Glasses connect as a client to this group.
     */
    const val WIFI_DIRECT_SSID_PREFIX = "DIRECT-FB-"

    /**
     * WiFi Direct subnet used by WifiP2pManager.
     * Phone (Group Owner): 192.168.49.1
     * Glasses (Client):    192.168.49.66 (observed, may vary)
     */
    const val WIFI_DIRECT_SUBNET = "192.168.49"
    const val WIFI_DIRECT_PHONE_IP = "192.168.49.1"
    const val WIFI_DIRECT_GLASSES_IP = "192.168.49.66"

    /**
     * TCP port for the proprietary transfer protocol.
     * NOT HTTP — this is a custom binary protocol wrapped in Airshield encryption.
     *
     * From PCAP: all media data flows through TCP 20203 on WiFi Direct.
     */
    const val TRANSFER_PORT = 20203

    // ── Airshield Encryption Protocol ───────────────────────────────────

    /**
     * Handshake phase prefix byte.
     * Packets starting with 0x80 are part of the ECDH key exchange.
     * Payload: 64-byte public key (likely ECDH P-256 or X25519).
     */
    const val AIRSHIELD_HANDSHAKE_PREFIX: Byte = 0x80.toByte()

    /**
     * Data phase prefix byte.
     * Packets starting with 0x40 contain encrypted payload.
     * Encrypted with session key derived from Airshield handshake.
     */
    const val AIRSHIELD_DATA_PREFIX: Byte = 0x40

    /**
     * Airshield crypto parameters (confirmed from DEX analysis):
     * - Key Exchange: ECDH P-256 (secp256r1) — 64-byte public key (x||y)
     * - Key Derivation: HKDF-SHA256
     * - Symmetric: AES-256-GCM (AES/GCM/NoPadding)
     * - Auth: VOPRF-Ristretto (ed25519/ristretto255)
     * - HPKE suite: DHKEM(P-256, HKDF-SHA256) confirmed in APK
     *
     * Key Airshield classes (com.facebook.wearable.airshield):
     *   securer/StreamSecurerImpl — session controller
     *   securer/Preamble — handshake exchange (challenges, auth)
     *   stream/CipherBuilder — key setup (privateKey, remotePublicKey, IV, seed)
     *   stream/Framing — encrypt/decrypt frames (pack/unpack)
     *   security/HKDF — key derivation
     *   security/Cipher — AES-256-GCM operations
     *   security/PrivateKey.derive(PublicKey) — ECDH shared secret
     *
     * Native libs: libairshield_jni.so, libpb_datax_jni.so, libvoprfmerged.so
     * Implementation: com.metarayban.glasses.data.crypto.AirshieldCrypto
     */
    const val AIRSHIELD_EC_CURVE = "secp256r1"
    const val AIRSHIELD_AES_KEY_BITS = 256
    const val AIRSHIELD_GCM_NONCE_BYTES = 12
    const val AIRSHIELD_PUBLIC_KEY_BYTES = 64

    // ── FlatBuffers BLE Commands ────────────────────────────────────────

    /**
     * BLE command identifiers (sent via CHAR_COMMAND).
     * Serialized using FlatBuffers (namespace: stella.srvs).
     *
     * Key commands for media transfer:
     * - StartWebserverRequest:  Tell glasses to start media server
     * - StartWebserverResponse: Glasses confirm server started
     * - StaModeConnectRequest:  Request STA mode WiFi connection
     * - StopWebserverRequest:   Tell glasses to stop media server
     *
     * Other discovered commands:
     * - TriggerCaptureRequest/Response: Take photo/video
     * - DeleteCaptureRequest/Response: Delete media from glasses
     * - GetCaptureInfoRequest/Response: Get capture metadata
     * - GetAssetContentRequest/Response: Fetch asset data
     * - GetSystemInfoRequest/Response: Device info query
     * - NotifyCaptureRequest/Response: Capture notification
     * - GetWifiCapabilitiesRequest/Response: WiFi capability query
     */
    object BleCommands {
        // FlatBuffers command type identifiers (stella:soc:*)
        const val START_WEBSERVER = "stella:soc:start_webserver"
        const val STOP_WEBSERVER = "stella:soc:stop_webserver"
    }

    // ── Transfer Protocol Details ───────────────────────────────────────

    /**
     * From PCAP analysis:
     * - Phone → Glasses: 51 packets, 1,814 bytes (commands)
     * - Glasses → Phone: 304 packets, 1,890,906 bytes (media data)
     * - Session lasted ~2 minutes for a small transfer
     * - Handshake: 0x80 + 64-byte key, Data: 0x40 + encrypted payload
     * - No JPEG/MP4 markers visible in stream = fully encrypted
     */

    // ── Meta View APK Package Info ──────────────────────────────────────

    /** Meta View companion app package name */
    const val META_VIEW_PACKAGE = "com.facebook.stella"

    /** Internal codename for the glasses platform */
    const val PLATFORM_CODENAME_STELLA = "stella"
    const val PLATFORM_CODENAME_SILVERSTONE = "silverstone"

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Check if a BLE device name matches known Meta glasses patterns.
     */
    fun isMetaGlasses(deviceName: String?): Boolean {
        if (deviceName == null) return false
        return DEVICE_NAME_PREFIXES.any { deviceName.startsWith(it, ignoreCase = true) }
    }

    /**
     * Check if manufacturer data contains Meta identifier.
     */
    fun hasMetaManufacturerData(manufacturerData: Map<Int, ByteArray>?): Boolean {
        return manufacturerData?.containsKey(MANUFACTURER_ID) == true
    }
}
