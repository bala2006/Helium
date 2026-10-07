package com.sekhar.helium.data

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.sekhar.helium.core.common.Hashing
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SourceMedia
import com.sekhar.helium.di.AppContainer
import kotlinx.coroutines.withContext

/** Outcome of an import attempt. */
data class ImportOutcome(
    val project: Project,
    val imported: List<SourceMedia>,
    val warnings: List<String>,
)

/**
 * Turns picked documents into project sources.
 *
 * Two rules matter here:
 *
 * * **Nothing is copied.** A source keeps the user's `content://` URI; Helium
 *   only takes a persistable read grant so the project still opens after a
 *   reboot. A multi-gigabyte file is never duplicated.
 * * **Unreadable media is reported, not fatal.** A file whose metadata cannot be
 *   read is added with an explanation so the import of the other clips still
 *   succeeds.
 */
class MediaImporter(private val container: AppContainer) {

    suspend fun import(uris: List<Uri>, project: Project): ImportOutcome =
        withContext(container.dispatchers.io) {
            var updated = project
            val imported = mutableListOf<SourceMedia>()
            val warnings = mutableListOf<String>()

            uris.forEach { uri ->
                val persisted = runCatching {
                    container.appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.isSuccess
                if (!persisted) {
                    warnings += "Could not keep permanent access to one file; re-import it if the project asks."
                }

                val (name, sizeBytes) = queryMetadata(uri)
                val futureSourceId = SourceId(container.ids.newId())
                val metadata = runCatching { container.signalExtractor.readMetadata(uri) }.getOrNull()
                val fingerprint = Hashing.sourceFingerprint(uri.toString(), sizeBytes, 0L)

                val source = SourceMedia(
                    id = futureSourceId,
                    uri = uri.toString(),
                    displayName = name,
                    fingerprint = fingerprint,
                    metadata = metadata ?: com.sekhar.helium.core.model.VideoMetadata(
                        durationMs = 0L,
                        width = 0,
                        height = 0,
                    ),
                    sizeBytes = sizeBytes,
                    importedAtEpochMs = container.time.nowEpochMs(),
                    unsupportedReason = if (metadata == null || !metadata.isSupported) {
                        "Helium could not read this file's video track."
                    } else {
                        null
                    },
                )

                if (source.unsupportedReason != null) {
                    warnings += "$name could not be read and was skipped."
                } else {
                    updated = updated.withSource(source, container.ids)
                    imported += source
                }
            }

            ImportOutcome(project = updated, imported = imported, warnings = warnings)
        }

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        val resolver = container.appContext.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "video"
        var size = 0L
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        } catch (_: Throwable) {
            // Keep the fallback values: a missing name is not worth failing an import.
        } finally {
            cursor?.close()
        }
        return name to size
    }
}
