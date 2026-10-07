package com.sekhar.helium.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Makes a finished export usable outside the app.
 *
 * On Android 10+ the file is copied into the shared `Movies/Helium` collection so
 * it appears in the gallery and can be posted straight to a social app. On older
 * releases the app-private file is shared through a scoped `FileProvider` URI
 * instead, because publishing to MediaStore there would require broad storage
 * permission — which Helium deliberately does not request.
 */
object ExportPublisher {

    private const val ALBUM = "Helium"
    const val MIME_TYPE = "video/mp4"

    /**
     * Copies [file] into the public Movies collection.
     *
     * @return the public content URI, or `null` when the platform needs storage
     *   permission Helium does not hold (Android 9 and below).
     */
    suspend fun publishToGallery(context: Context, file: File): Uri? {
        if (!file.exists()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext null
            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }.isSuccess
            if (!copied) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            uri
        }
    }

    /** Scoped URI for an app-private file, suitable for `ACTION_SEND`. */
    fun fileProviderUri(context: Context, file: File): Uri? =
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()

    /** Share sheet for a finished export. */
    fun shareIntent(context: Context, uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share your video").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
