package com.metarayban.glasses.data.ble

import java.util.UUID

/**
 * Meta Ray-Ban BLE protocol constants.
 *
 * Based on GATT exploration of Ray-Ban Meta V1 (RB Meta 00WJ).
 * Service/characteristic UUIDs and handle mappings from BLE scan.
 *
 * The proprietary protocol details (auth sequence, WiFi activation command)
 * will be filled in after protocol capture analysis.
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
     * This is the main bidirectional command channel.
     * Handle: 41 (from GATT map)
     */
    val CHAR_COMMAND: UUID = UUID.fromString("05acbe9f-6f61-4ca9-80bf-c8bbb52991c0")

    /**
     * Status characteristic.
     * Properties: read
     * Handle: 44 (from GATT map)
     */
    val CHAR_STATUS: UUID = UUID.fromString("c53673dd-e411-47c7-8fa2-3a8413578e77")

    /**
     * Flags characteristic.
     * Properties: read
     * Handle: 46 (from GATT map)
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

    // ── Protocol Commands (to be filled after capture analysis) ─────────

    /**
     * TODO: These will be populated after analyzing btsnoop_hci.log captures.
     * The captures will reveal the exact byte sequences for:
     * - Authentication handshake
     * - WiFi hotspot activation
     * - Media listing request
     * - Transfer initiation
     */

    // Placeholder: WiFi activation command (write to CHAR_COMMAND)
    // val CMD_ACTIVATE_WIFI: ByteArray = byteArrayOf(...)

    // Placeholder: Auth challenge response
    // val CMD_AUTH_RESPONSE: ByteArray = byteArrayOf(...)

    // ── WiFi Transfer ───────────────────────────────────────────────────

    /**
     * WiFi hotspot SSID pattern created by the glasses.
     * TODO: Confirm exact pattern from capture.
     */
    const val WIFI_SSID_PREFIX = "DIRECT-"

    /**
     * Default HTTP port on the glasses' hotspot.
     * TODO: Confirm from WiFi probe / PCAP analysis.
     */
    const val TRANSFER_PORT = 80

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
