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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HTTP client for downloading media files from the glasses' WiFi hotspot.
 *
 * When the glasses activate their WiFi hotspot, they expose an HTTP server
 * that serves the stored photos and videos.
 *
 * TODO: The exact API endpoints and format will be determined after
 * analyzing the PCAP capture from Phase 1.
 */
class MediaTransferClient(private val context: Context) {

    companion object {
        private const val TAG = "MediaTransfer"
        private const val SAVE_DIRECTORY = "MetaRayBan"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _transferState = MutableStateFlow(TransferState())
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    /**
     * List media files available on the glasses.
     *
     * TODO: Implement after discovering the actual API from PCAP analysis.
     * Possible endpoints: /api/media, /api/files, /media/, /list, etc.
     */
    suspend fun listMediaFiles(gatewayIp: String): List<MediaFile> = withContext(Dispatchers.IO) {
        val baseUrl = "http://$gatewayIp:${MetaProtocol.TRANSFER_PORT}"
        Log.d(TAG, "Listing media from $baseUrl")

        // TODO: Replace with actual API endpoint after protocol analysis
        // This is a placeholder that will be updated
        val mediaFiles = mutableListOf<MediaFile>()

        // Try common endpoints to discover the API
        val endpoints = listOf(
            "/api/media", "/api/files", "/media", "/files",
            "/api/v1/media", "/list", "/catalog", "/index",
        )

        for (endpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl$endpoint")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    Log.d(TAG, "Found endpoint $endpoint: ${body.take(200)}")
                    // TODO: Parse response based on actual format (JSON, protobuf, etc.)
                    break
                }
                response.close()
            } catch (e: Exception) {
                // Connection refused, timeout, etc. - try next endpoint
            }
        }

        mediaFiles
    }

    /**
     * Download a single media file from the glasses and save to gallery.
     */
    suspend fun downloadFile(
        gatewayIp: String,
        mediaFile: MediaFile,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = mediaFile.downloadUrl ?: return@withContext false
        Log.d(TAG, "Downloading: ${mediaFile.filename}")

        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val totalBytes = body.contentLength()

            // Save to MediaStore (gallery)
            val mimeType = when (mediaFile.type) {
                MediaType.PHOTO -> "image/jpeg"
                MediaType.VIDEO -> "video/mp4"
                MediaType.UNKNOWN -> "application/octet-stream"
            }

            val collection = when (mediaFile.type) {
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

            val relativePath = when (mediaFile.type) {
                MediaType.PHOTO -> "${Environment.DIRECTORY_DCIM}/$SAVE_DIRECTORY"
                MediaType.VIDEO -> "${Environment.DIRECTORY_DCIM}/$SAVE_DIRECTORY"
                MediaType.UNKNOWN -> "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_DIRECTORY"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, mediaFile.filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(collection, values)
                ?: return@withContext false

            context.contentResolver.openOutputStream(uri)?.use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                val input = body.byteStream()

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesRead += read

                    // Update progress
                    _transferState.value = _transferState.value.copy(
                        bytesTransferred = _transferState.value.bytesTransferred + read,
                        currentFile = mediaFile.filename,
                    )
                }
            }

            // Mark as complete
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }

            Log.d(TAG, "Downloaded: ${mediaFile.filename} ($totalBytes bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        }
    }

    /**
     * Download all media files from the glasses.
     */
    suspend fun downloadAllMedia(gatewayIp: String): Int {
        val files = listMediaFiles(gatewayIp)
        if (files.isEmpty()) {
            Log.w(TAG, "No media files found")
            return 0
        }

        _transferState.value = TransferState(
            isActive = true,
            totalFiles = files.size,
            totalBytes = files.sumOf { it.size },
        )

        var downloaded = 0
        for (file in files) {
            if (downloadFile(gatewayIp, file)) {
                downloaded++
                _transferState.value = _transferState.value.copy(
                    completedFiles = downloaded,
                )
            }
        }

        _transferState.value = _transferState.value.copy(isActive = false)
        Log.d(TAG, "Downloaded $downloaded/${files.size} files")
        return downloaded
    }
}
