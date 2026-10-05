package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/** Orientation of a decoded video frame after rotation metadata is applied. */
@Serializable
enum class Orientation { PORTRAIT, LANDSCAPE, SQUARE }

/**
 * Technical metadata for an imported source.
 *
 * `frameRate == 0f` means unknown (typically variable-frame-rate media), which
 * the exporter handles by letting the encoder pick timestamps.
 */
@Serializable
data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val frameRate: Float = 0f,
    val hasAudio: Boolean = false,
    val videoMimeType: String? = null,
    val audioMimeType: String? = null,
    val bitrate: Int = 0,
    val isVariableFrameRate: Boolean = false,
) {
    val orientation: Orientation
        get() = when {
            width == height -> Orientation.SQUARE
            height > width -> Orientation.PORTRAIT
            else -> Orientation.LANDSCAPE
        }

    /** Width after rotation metadata is applied. */
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    /** Height after rotation metadata is applied. */
    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    val aspectRatio: Float
        get() = if (displayHeight == 0) 1f else displayWidth.toFloat() / displayHeight.toFloat()

    val isSupported: Boolean
        get() = durationMs > 0L && displayWidth > 0 && displayHeight > 0
}

/**
 * An imported, user-owned media file.
 *
 * `uri` is a persisted `content://` URI — Helium never copies or rewrites the
 * original file. `proxyPath` points at an app-private low-resolution editing
 * proxy and may be `null` while (or if) proxy generation is skipped.
 */
@Serializable
data class SourceMedia(
    val id: SourceId,
    val uri: String,
    val displayName: String,
    val fingerprint: String,
    val metadata: VideoMetadata,
    val sizeBytes: Long,
    val importedAtEpochMs: Long,
    val proxyPath: String? = null,
    val proxyWidth: Int = 0,
    val proxyHeight: Int = 0,
    val transcriptLanguage: String? = null,
    val unsupportedReason: String? = null,
) {
    val isUsable: Boolean get() = unsupportedReason == null && metadata.isSupported
}
