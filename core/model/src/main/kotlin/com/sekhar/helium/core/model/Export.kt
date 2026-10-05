package com.sekhar.helium.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ready-made export targets offered in the export sheet. */
@Serializable
enum class ExportPreset(
    val displayName: String,
    val width: Int,
    val height: Int,
    val aspectRatio: AspectRatio,
) {
    ORIGINAL("Match original", 0, 0, AspectRatio.ORIGINAL),
    VERTICAL_1080P("Reel / Story · 1080×1920", 1080, 1920, AspectRatio.PORTRAIT_9_16),
    LANDSCAPE_1080P("Landscape · 1920×1080", 1920, 1080, AspectRatio.LANDSCAPE_16_9),
    SQUARE_1080("Square · 1080×1080", 1080, 1080, AspectRatio.SQUARE_1_1),
    VERTICAL_720P("Reel / Story · 720×1280", 720, 1280, AspectRatio.PORTRAIT_9_16),
}

/** Supported video codecs. H.264 is preferred for maximum compatibility. */
@Serializable
enum class VideoCodec(val displayName: String, val mimeType: String) {
    H264("H.264 (most compatible)", "video/avc"),
    HEVC("HEVC / H.265 (smaller)", "video/hevc"),
}

/** Supported audio codecs. */
@Serializable
enum class AudioCodec(val displayName: String, val mimeType: String) {
    AAC("AAC", "audio/mp4a-latm"),
}

/** Everything the export engine needs; decoupled from any UI state. */
@Serializable
data class ExportSettings(
    val preset: ExportPreset = ExportPreset.ORIGINAL,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val videoBitrate: Int = 8_000_000,
    val audioBitrate: Int = 192_000,
    val frameRate: Float = 30f,
    val container: String = "mp4",
    val includeOriginalAudio: Boolean = true,
    val burnInCaptions: Boolean = true,
) {
    /** True when the preset should follow whatever the source resolution is. */
    val isOriginal: Boolean get() = preset == ExportPreset.ORIGINAL

    /** Effective output size given the source dimensions. */
    fun resolveSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (!isOriginal && width > 0 && height > 0) return width to height
        // "Original" keeps the source size, but even dimensions are required by
        // H.264/H.265 encoders.
        val w = if (sourceWidth > 0) sourceWidth else 1080
        val h = if (sourceHeight > 0) sourceHeight else 1920
        return (w - w % 2) to (h - h % 2)
    }
}

/** Live export progress surfaced to the UI. */
@Serializable
sealed interface ExportState {
    @Serializable
    @SerialName("idle")
    data object Idle : ExportState

    @Serializable
    @SerialName("preparing")
    data object Preparing : ExportState

    @Serializable
    @SerialName("running")
    data class Running(val progress: Float, val stage: String) : ExportState

    @Serializable
    @SerialName("completed")
    data class Completed(
        val outputPath: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    ) : ExportState

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : ExportState

    @Serializable
    @SerialName("failed")
    data class Failed(val message: String, val recoverable: Boolean = true) : ExportState
}
