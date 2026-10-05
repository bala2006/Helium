package com.sekhar.helium.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The complete vocabulary of deterministic editing operations.
 *
 * Two rules make AI editing safe and predictable:
 *
 * 1. **Operations never touch the source file.** They only describe a new
 *    [Timeline]. Original media is immutable.
 * 2. **Time is anchored to the source wherever possible.** An operation such as
 *    [RemoveRange] names a `sourceId` + [TimeRange] in *source* time rather than
 *    a timeline position. That makes the same transaction valid no matter how
 *    many earlier edits shifted the timeline, and makes undo a pure replay.
 *
 * Clip-anchored operations ([SplitClip], [TrimClip], [MoveClip], …) reference a
 * [ClipId] and are used for direct manipulation and for AI edits that already
 * resolved a specific clip through an inspection tool.
 */
@Serializable
sealed interface EditOperation {
    /** Stable id, unique inside a transaction; used for logging and results. */
    val operationId: String
}

// ---------------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------------

/** Splits a clip in two at a source timestamp. */
@Serializable
@SerialName("split_clip")
data class SplitClip(
    override val operationId: String,
    val clipId: ClipId,
    val atSourceMs: Long,
) : EditOperation

/** Replaces the source range a clip plays, re-trimming it in place. */
@Serializable
@SerialName("trim_clip")
data class TrimClip(
    override val operationId: String,
    val clipId: ClipId,
    val newSourceRange: TimeRange,
) : EditOperation

/**
 * Removes a region of a source from everywhere it appears on the timeline,
 * closing the resulting gap. This is how "remove all boring pauses" and
 * "cut everything before I say 'here we go'" are expressed.
 */
@Serializable
@SerialName("remove_range")
data class RemoveRange(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
) : EditOperation

/** Re-inserts a previously removed source region. Inverse of [RemoveRange]. */
@Serializable
@SerialName("restore_range")
data class RestoreRange(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
) : EditOperation

/** Moves a clip to another video track and/or timeline position. */
@Serializable
@SerialName("move_clip")
data class MoveClip(
    override val operationId: String,
    val clipId: ClipId,
    val toTrackId: TrackId,
    val newTimelineStartMs: Long,
) : EditOperation

/** Copies a clip to a new timeline position on the same track. */
@Serializable
@SerialName("duplicate_clip")
data class DuplicateClip(
    override val operationId: String,
    val clipId: ClipId,
    val newTimelineStartMs: Long,
) : EditOperation

// ---------------------------------------------------------------------------------
// Speed and audio
// ---------------------------------------------------------------------------------

/** Changes playback speed for every clip showing [range] of [sourceId]. */
@Serializable
@SerialName("set_speed")
data class SetSpeed(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
    val speed: Float,
) : EditOperation {
    init {
        require(speed in 0.1f..10f) { "speed must be within 0.1..10 but was $speed" }
    }
}

/** Sets the linear volume of the original audio over [range]. */
@Serializable
@SerialName("set_volume")
data class SetVolume(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
    val volume: Float,
) : EditOperation {
    init {
        require(volume in 0f..4f) { "volume must be within 0..4 but was $volume" }
    }
}

/** Silences the original audio over [range] without deleting the video. */
@Serializable
@SerialName("mute_range")
data class MuteRange(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
) : EditOperation

// ---------------------------------------------------------------------------------
// Framing
// ---------------------------------------------------------------------------------

/** Sets the project output aspect ratio. */
@Serializable
@SerialName("set_aspect_ratio")
data class SetAspectRatioOp(
    override val operationId: String,
    val aspectRatio: AspectRatio,
) : EditOperation

/** Applies an explicit crop to one clip. */
@Serializable
@SerialName("crop_clip")
data class CropClip(
    override val operationId: String,
    val clipId: ClipId,
    val crop: CropRect,
) : EditOperation

/**
 * Re-frames a clip to [targetAspect] while keeping the subject centred.
 * `focusX`/`focusY` come from a person/object track when one is available.
 */
@Serializable
@SerialName("reframe_clip")
data class ReframeClip(
    override val operationId: String,
    val clipId: ClipId,
    val targetAspect: AspectRatio,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoomScale: Float = 1f,
) : EditOperation

/** Adds a zoom ("punch in") over [range] of [sourceId]. */
@Serializable
@SerialName("add_zoom")
data class AddZoom(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
    val scale: Float,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val easeInMs: Long = 250L,
    val easeOutMs: Long = 250L,
) : EditOperation {
    init {
        require(scale in 1f..8f) { "zoom scale must be within 1..8 but was $scale" }
    }
}

/** Blurs a normalised region — used to redact private information. */
@Serializable
@SerialName("blur_region")
data class BlurRegion(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
    val region: NormalizedRect,
    val featherPx: Float = 24f,
    val trackSubject: Boolean = false,
) : EditOperation

/** Applies a colour grade over [range] of [sourceId]. */
@Serializable
@SerialName("set_color")
data class SetColorAdjust(
    override val operationId: String,
    val sourceId: SourceId,
    val range: TimeRange,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val vignette: Float = 0f,
) : EditOperation

// ---------------------------------------------------------------------------------
// Text, captions and overlays
// ---------------------------------------------------------------------------------

/** Adds free-standing text at an absolute timeline position. */
@Serializable
@SerialName("add_text")
data class AddText(
    override val operationId: String,
    val timelineStartMs: Long,
    val durationMs: Long,
    val text: String,
    val style: TextStyle = TextStyle(),
    val trackId: TrackId? = null,
) : EditOperation

/** One caption cue, expressed in source time. */
@Serializable
data class CaptionCueSpec(
    val range: TimeRange,
    val text: String,
    val words: List<WordTiming> = emptyList(),
)

/**
 * Adds captions derived from a transcript. Cue ranges are in *source* time so
 * they stay correct after other edits shift the timeline.
 */
@Serializable
@SerialName("add_caption")
data class AddCaption(
    override val operationId: String,
    val sourceId: SourceId,
    val cues: List<CaptionCueSpec>,
    val style: TextStyle = TextStyle.REEL_CAPTION,
    val trackId: TrackId? = null,
) : EditOperation

/** Removes caption/text items by id, by substring, or wholesale for a source. */
@Serializable
@SerialName("remove_caption")
data class RemoveCaption(
    override val operationId: String,
    val textItemIds: List<TextItemId> = emptyList(),
    val containingText: String? = null,
    val sourceId: SourceId? = null,
) : EditOperation

/** Places a still image (sticker, badge, meme) over the video. */
@Serializable
@SerialName("add_image_overlay")
data class AddImageOverlay(
    override val operationId: String,
    val imageUri: String,
    val timelineStartMs: Long,
    val durationMs: Long,
    val position: NormalizedPoint = NormalizedPoint(0.5f, 0.5f),
    val scale: Float = 0.4f,
    val opacity: Float = 1f,
    val label: String? = null,
) : EditOperation

/** Holds the frame at [atSourceMs] for [holdMs], optionally adding a label. */
@Serializable
@SerialName("freeze_frame")
data class FreezeFrame(
    override val operationId: String,
    val sourceId: SourceId,
    val atSourceMs: Long,
    val holdMs: Long = 1500L,
    val overlayText: String? = null,
) : EditOperation

// ---------------------------------------------------------------------------------
// Transitions and music
// ---------------------------------------------------------------------------------

/** Attaches a transition to a clip boundary. */
@Serializable
@SerialName("add_transition")
data class AddTransition(
    override val operationId: String,
    val clipId: ClipId,
    val boundary: TransitionBoundary,
    val spec: TransitionSpec,
) : EditOperation

/** Adds a music bed on a dedicated audio track. */
@Serializable
@SerialName("add_music")
data class AddMusic(
    override val operationId: String,
    val musicSourceId: SourceId,
    val timelineStartMs: Long = 0L,
    val durationMs: Long? = null,
    val volume: Float = 0.55f,
    val duckUnderSpeech: Boolean = true,
    val fadeInMs: Long = 400L,
    val fadeOutMs: Long = 900L,
) : EditOperation

/** Enables or disables automatic ducking for a music clip. */
@Serializable
@SerialName("duck_music")
data class DuckMusic(
    override val operationId: String,
    val audioClipId: ClipId,
    val duckAmountDb: Float = -12f,
    val enabled: Boolean = true,
) : EditOperation

/** Applies fades to an audio clip. */
@Serializable
@SerialName("fade_audio")
data class FadeAudio(
    override val operationId: String,
    val audioClipId: ClipId,
    val fadeInMs: Long,
    val fadeOutMs: Long,
) : EditOperation

// ---------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------

/**
 * Reverts an earlier transaction by replaying the history without it.
 * This is what "undo the zoom you just added" resolves to when the AI removes a
 * specific previous edit rather than the most recent one.
 */
@Serializable
@SerialName("delete_edit")
data class DeleteEdit(
    override val operationId: String,
    val targetTransactionId: String,
) : EditOperation
