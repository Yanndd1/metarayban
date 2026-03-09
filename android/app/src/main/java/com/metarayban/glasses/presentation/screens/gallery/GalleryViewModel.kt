package com.metarayban.glasses.presentation.screens.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _mediaItems = MutableStateFlow<List<Uri>>(emptyList())
    val mediaItems: StateFlow<List<Uri>> = _mediaItems.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _mediaItems.value = loadMediaFromGallery()
        }
    }

    /**
     * Load media files saved by our app from DCIM/MetaRayBan/.
     */
    private suspend fun loadMediaFromGallery(): List<Uri> = withContext(Dispatchers.IO) {
        val uris = mutableListOf<Uri>()
        val targetPath = "${Environment.DIRECTORY_DCIM}/MetaRayBan"

        // Query images
        queryMedia(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            targetPath,
        ).let { uris.addAll(it) }

        // Query videos
        queryMedia(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            targetPath,
        ).let { uris.addAll(it) }

        // Sort by date descending
        uris.sortedByDescending { it.lastPathSegment }
    }

    private fun queryMedia(collection: Uri, relativePath: String): List<Uri> {
        val uris = mutableListOf<Uri>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$relativePath%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                uris.add(uri)
            }
        }

        return uris
    }
}
