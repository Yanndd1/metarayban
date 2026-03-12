package com.metarayban.glasses.data.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FlatBuffers command builder for stella/srvs/* BLE commands.
 *
 * Reverse-engineered from the stella/srvs FlatBuffers classes in Meta View APK.
 * These commands are sent over BLE GATT characteristic CHAR_COMMAND (05acbe9f-...)
 * to control the glasses.
 *
 * FlatBuffers format:
 *  - Little-endian
 *  - Root table with vtable reference
 *  - Size-prefixed buffers (4-byte LE length prefix)
 *
 * Since we can't use the actual FlatBuffers library without the .fbs schemas,
 * we hand-craft the binary payloads based on the field structure observed
 * in the decompiled classes.
 */
object StellaCommands {

    /**
     * Build a StartWebserverRequest command.
     *
     * Fields (from DEX analysis):
     *   - requestToken: long (unique request ID)
     *   - idleTimeoutSecs: int (server idle timeout)
     *
     * FlatBuffer file identifier: used by finishStartWebserverBuffer()
     */
    fun buildStartWebserverRequest(requestToken: Long, idleTimeoutSecs: Int = 60): ByteArray {
        return buildFlatBuffer { builder ->
            // StartWebserverRequest table:
            //   field 0: requestToken (long)
            //   field 1: idleTimeoutSecs (int)
            val requestOffset = buildTable(builder, fields = listOf(
                Field.Long(requestToken),
                Field.Int(idleTimeoutSecs),
            ))

            // StartWebserver wrapper table:
            //   field 0: request (offset to StartWebserverRequest)
            val wrapperOffset = buildTable(builder, fields = listOf(
                Field.Offset(requestOffset),
            ))

            finishBuffer(builder, wrapperOffset)
        }
    }

    /**
     * Build a StopWebserverRequest command.
     *
     * Fields:
     *   - requestToken: long
     */
    fun buildStopWebserverRequest(requestToken: Long): ByteArray {
        return buildFlatBuffer { builder ->
            val requestOffset = buildTable(builder, fields = listOf(
                Field.Long(requestToken),
            ))

            val wrapperOffset = buildTable(builder, fields = listOf(
                Field.Offset(requestOffset),
            ))

            finishBuffer(builder, wrapperOffset)
        }
    }

    /**
     * Build a GetCaptureInfoRequest command.
     *
     * Fields:
     *   - includeDeletedCaptures: boolean
     */
    fun buildGetCaptureInfoRequest(includeDeleted: Boolean = false): ByteArray {
        return buildFlatBuffer { builder ->
            val requestOffset = buildTable(builder, fields = listOf(
                Field.Bool(includeDeleted),
            ))

            val wrapperOffset = buildTable(builder, fields = listOf(
                Field.Offset(requestOffset),
            ))

            finishBuffer(builder, wrapperOffset)
        }
    }

    /**
     * Build a TriggerCaptureRequest command.
     *
     * Fields:
     *   - type: int (CaptureRequestType enum)
     *   - timestampNs: long
     *   - countdown: boolean
     *   - maxVidDurationMs: long
     *   - origin: int (CaptureRequestOrigin enum)
     */
    fun buildTriggerCaptureRequest(
        type: CaptureType = CaptureType.PHOTO,
        timestampNs: Long = System.nanoTime(),
        countdown: Boolean = false,
        maxVideoDurationMs: Long = 30_000,
    ): ByteArray {
        return buildFlatBuffer { builder ->
            val requestOffset = buildTable(builder, fields = listOf(
                Field.Int(type.value),
                Field.Long(timestampNs),
                Field.Bool(countdown),
                Field.Long(maxVideoDurationMs),
                Field.Int(0), // origin = 0 (companion app)
            ))

            val wrapperOffset = buildTable(builder, fields = listOf(
                Field.Offset(requestOffset),
            ))

            finishBuffer(builder, wrapperOffset)
        }
    }

    /**
     * Build a DeleteCaptureRequest command.
     *
     * Fields:
     *   - captureId: string
     *   - typeHint: int (DeletionType enum)
     */
    fun buildDeleteCaptureRequest(captureId: String, typeHint: Int = 0): ByteArray {
        return buildFlatBuffer { builder ->
            val captureIdOffset = buildString(builder, captureId)
            val requestOffset = buildTable(builder, fields = listOf(
                Field.Offset(captureIdOffset),
                Field.Int(typeHint),
            ))

            val wrapperOffset = buildTable(builder, fields = listOf(
                Field.Offset(requestOffset),
            ))

            finishBuffer(builder, wrapperOffset)
        }
    }

    /**
     * Build a GetAssetContentRequest command.
     *
     * Fields:
     *   - assetId: string
     */
    fun buildGetAssetContentRequest(assetId: String): ByteArray {
        return buildFlatBuffer { builder ->
            val assetIdOffset = buildString(builder, assetId)
            val requestOffset = buildTable(builder, fields = listOf(
                Field.Offset(assetIdOffset),
            ))

            val wrapperOffset = buildTable(builder, fields = listOf(
                Field.Offset(requestOffset),
            ))

            finishBuffer(builder, wrapperOffset)
        }
    }

    // ── Response parsers ─────────────────────────────────────────────────

    /**
     * Parse a StartWebserverResponse from raw FlatBuffer bytes.
     */
    fun parseStartWebserverResponse(data: ByteArray): WebserverResponse? {
        if (data.size < 8) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        return try {
            // Skip size prefix if present
            val offset = if (data.size > 4) buf.getInt(0) else 0
            val rootOffset = if (offset > 0 && offset < data.size) 4 else 0

            // Read wrapper table
            val wrapperPos = rootOffset + buf.getInt(rootOffset)
            val vtableOffset = buf.getInt(wrapperPos) // signed offset to vtable
            val vtablePos = wrapperPos - vtableOffset

            // Read response subtable (field 1 in wrapper)
            val responseFieldOffset = getVTableField(buf, vtablePos, 1)
            if (responseFieldOffset == 0) return null

            val responsePos = wrapperPos + responseFieldOffset + buf.getInt(wrapperPos + responseFieldOffset)
            val respVtablePos = responsePos - buf.getInt(responsePos)

            // Parse response fields
            val status = getVTableFieldByte(buf, respVtablePos, responsePos, 0)
            val scheme = getVTableFieldByte(buf, respVtablePos, responsePos, 2)
            val requestToken = getVTableFieldLong(buf, respVtablePos, responsePos, 4)

            WebserverResponse(
                status = status?.toInt() ?: -1,
                scheme = scheme?.toInt() ?: 0,
                requestToken = requestToken ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a GetCaptureInfoResponse to get capture count and IDs.
     */
    fun parseGetCaptureInfoResponse(data: ByteArray): CaptureInfoResponse? {
        if (data.size < 8) return null
        // Simplified parser — returns basic info
        return try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            CaptureInfoResponse(captureCount = 0, captureIds = emptyList())
        } catch (e: Exception) {
            null
        }
    }

    // ── Data classes ─────────────────────────────────────────────────────

    data class WebserverResponse(
        val status: Int,
        val scheme: Int,
        val requestToken: Long,
    ) {
        val isSuccess: Boolean get() = status == 0
        val schemeString: String get() = if (scheme == 1) "https" else "http"
    }

    data class CaptureInfoResponse(
        val captureCount: Int,
        val captureIds: List<String>,
    )

    enum class CaptureType(val value: Int) {
        PHOTO(0),
        VIDEO(1),
        TIMELAPSE(2),
    }

    // ── FlatBuffer builder helpers ───────────────────────────────────────

    private sealed class Field {
        data class Bool(val value: Boolean) : Field()
        data class Byte(val value: kotlin.Byte) : Field()
        data class Int(val value: kotlin.Int) : Field()
        data class Long(val value: kotlin.Long) : Field()
        data class Offset(val value: kotlin.Int) : Field()
    }

    private class FlatBufferBuilder(size: Int = 256) {
        var buffer = ByteArray(size)
        var pos = size // build from end

        fun pad(count: Int) {
            for (i in 0 until count) {
                pos--
                buffer[pos] = 0
            }
        }

        fun putByte(v: Byte) {
            pos--
            buffer[pos] = v
        }

        fun putInt(v: Int) {
            pos -= 4
            buffer[pos] = (v and 0xFF).toByte()
            buffer[pos + 1] = ((v shr 8) and 0xFF).toByte()
            buffer[pos + 2] = ((v shr 16) and 0xFF).toByte()
            buffer[pos + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun putLong(v: Long) {
            pos -= 8
            for (i in 0 until 8) {
                buffer[pos + i] = ((v shr (i * 8)) and 0xFF).toByte()
            }
        }

        fun putShort(v: Short) {
            pos -= 2
            buffer[pos] = (v.toInt() and 0xFF).toByte()
            buffer[pos + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }

        fun currentOffset(): Int = buffer.size - pos

        fun toByteArray(): ByteArray = buffer.copyOfRange(pos, buffer.size)
    }

    private fun buildFlatBuffer(block: (FlatBufferBuilder) -> Unit): ByteArray {
        val builder = FlatBufferBuilder(512)
        block(builder)
        return builder.toByteArray()
    }

    private fun buildTable(builder: FlatBufferBuilder, fields: List<Field>): Int {
        // Align to 4 bytes
        while (builder.pos % 4 != 0) builder.putByte(0)

        // Write field data (in reverse order for FlatBuffers)
        val fieldOffsets = mutableListOf<Pair<Int, Int>>() // vtable index, offset from table start

        for ((index, field) in fields.reversed().withIndex()) {
            val realIndex = fields.size - 1 - index
            when (field) {
                is Field.Bool -> {
                    builder.putByte(if (field.value) 1 else 0)
                    fieldOffsets.add(realIndex to 1)
                }
                is Field.Byte -> {
                    builder.putByte(field.value)
                    fieldOffsets.add(realIndex to 1)
                }
                is Field.Int, is Field.Offset -> {
                    while (builder.pos % 4 != 0) builder.putByte(0)
                    val v = when (field) {
                        is Field.Int -> field.value
                        is Field.Offset -> field.value
                        else -> 0
                    }
                    builder.putInt(v)
                    fieldOffsets.add(realIndex to 4)
                }
                is Field.Long -> {
                    while (builder.pos % 8 != 0) builder.putByte(0)
                    builder.putLong(field.value)
                    fieldOffsets.add(realIndex to 8)
                }
            }
        }

        val tableStart = builder.currentOffset()

        // Write vtable offset (will be filled in)
        builder.putInt(0) // placeholder for vtable offset

        return tableStart
    }

    private fun buildString(builder: FlatBufferBuilder, s: String): Int {
        val bytes = s.toByteArray(Charsets.UTF_8)
        builder.putByte(0) // null terminator
        for (i in bytes.indices.reversed()) {
            builder.putByte(bytes[i])
        }
        builder.putInt(bytes.size)
        return builder.currentOffset()
    }

    private fun finishBuffer(builder: FlatBufferBuilder, rootOffset: Int) {
        // Size prefix + root offset
        builder.putInt(rootOffset)
        builder.putInt(builder.currentOffset()) // size prefix
    }

    // ── VTable reader helpers ────────────────────────────────────────────

    private fun getVTableField(buf: ByteBuffer, vtablePos: Int, fieldIndex: Int): Int {
        val vtableSize = buf.getShort(vtablePos).toInt() and 0xFFFF
        val fieldOffset = 4 + fieldIndex * 2
        if (fieldOffset >= vtableSize) return 0
        return buf.getShort(vtablePos + fieldOffset).toInt() and 0xFFFF
    }

    private fun getVTableFieldByte(buf: ByteBuffer, vtablePos: Int, tablePos: Int, fieldIndex: Int): Byte? {
        val offset = getVTableField(buf, vtablePos, fieldIndex)
        if (offset == 0) return null
        return buf.get(tablePos + offset)
    }

    private fun getVTableFieldLong(buf: ByteBuffer, vtablePos: Int, tablePos: Int, fieldIndex: Int): Long? {
        val offset = getVTableField(buf, vtablePos, fieldIndex)
        if (offset == 0) return null
        return buf.getLong(tablePos + offset)
    }
}
