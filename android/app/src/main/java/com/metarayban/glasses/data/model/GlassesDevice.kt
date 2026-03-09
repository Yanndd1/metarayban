package com.metarayban.glasses.data.model

/**
 * Represents a discovered Ray-Ban Meta glasses device.
 */
data class GlassesDevice(
    val name: String,
    val address: String,  // BLE MAC address
    val rssi: Int,
    val isMetaGlasses: Boolean,
    val manufacturerData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassesDevice) return false
        return address == other.address
    }

    override fun hashCode(): Int = address.hashCode()
}

/**
 * Connection state with the glasses.
 */
enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    BONDING,
    AUTHENTICATING,
    CONNECTED,
    ERROR,
}

/**
 * Glasses info read from GATT characteristics.
 */
data class GlassesInfo(
    val deviceName: String = "",
    val firmwareVersion: String = "",
    val batteryLevel: Int = -1,
    val statusData: ByteArray? = null,
    val flagsData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassesInfo) return false
        return deviceName == other.deviceName && firmwareVersion == other.firmwareVersion
    }

    override fun hashCode(): Int = deviceName.hashCode() + firmwareVersion.hashCode()
}

/**
 * Media file metadata from the glasses.
 */
data class MediaFile(
    val filename: String,
    val size: Long,
    val type: MediaType,
    val timestamp: Long,
    val thumbnailUrl: String? = null,
    val downloadUrl: String? = null,
    val downloaded: Boolean = false,
    val localPath: String? = null,
)

enum class MediaType {
    PHOTO,
    VIDEO,
    UNKNOWN,
}

/**
 * Transfer progress state.
 */
data class TransferState(
    val isActive: Boolean = false,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val currentFile: String = "",
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val wifiSsid: String = "",
    val error: String? = null,
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f

    val fileProgressPercent: Float
        get() = if (totalFiles > 0) completedFiles.toFloat() / totalFiles else 0f
}
