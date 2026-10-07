package com.sekhar.helium.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Canonical tool names. Shared by the catalog, the parser and the backend. */
object ToolNames {
    // Inspection.
    const val GET_PROJECT_STATE = "get_project_state"
    const val GET_VIDEO_OVERVIEW = "get_video_overview"
    const val SEARCH_VIDEO = "search_video"
    const val GET_SCENE = "get_scene"
    const val GET_TRANSCRIPT = "get_transcript"
    const val FIND_SPOKEN_TEXT = "find_spoken_text"
    const val FIND_VISIBLE_TEXT = "find_visible_text"
    const val GET_AUDIO_EVENTS = "get_audio_events"
    const val GET_VISUAL_EVENTS = "get_visual_events"
    const val INSPECT_SEGMENT = "inspect_segment"
    const val GET_FRAME = "get_frame"
    const val GET_ORIGINAL_FRAME = "get_original_frame"
    const val GET_TEMPORAL_STRIP = "get_temporal_strip"
    const val FIND_HIGH_MOTION_SEGMENTS = "find_high_motion_segments"
    const val FIND_SILENCE = "find_silence"

    // Editing.
    const val SPLIT_CLIP = "split_clip"
    const val TRIM_CLIP = "trim_clip"
    const val REMOVE_RANGE = "remove_range"
    const val RESTORE_RANGE = "restore_range"
    const val MOVE_CLIP = "move_clip"
    const val DUPLICATE_CLIP = "duplicate_clip"
    const val SET_SPEED = "set_speed"
    const val SET_VOLUME = "set_volume"
    const val MUTE_RANGE = "mute_range"
    const val SET_ASPECT_RATIO = "set_aspect_ratio"
    const val CROP_CLIP = "crop_clip"
    const val REFRAME_CLIP = "reframe_clip"
    const val ADD_ZOOM = "add_zoom"
    const val ADD_TEXT = "add_text"
    const val ADD_CAPTION = "add_caption"
    const val REMOVE_CAPTION = "remove_caption"
    const val ADD_IMAGE_OVERLAY = "add_image_overlay"
    const val FREEZE_FRAME = "freeze_frame"
    const val ADD_TRANSITION = "add_transition"
    const val ADD_MUSIC = "add_music"
    const val DUCK_MUSIC = "duck_music"
    const val FADE_AUDIO = "fade_audio"
    const val BLUR_REGION = "blur_region"
    const val DELETE_EDIT = "delete_edit"
}

/** What a tool does, which drives the agent's loop termination and logging. */
@Serializable
enum class ToolCategory {
    /** Read-only evidence retrieval. Safe to call repeatedly. */
    INSPECTION,

    /** Produces an [com.sekhar.helium.core.model.EditOperation]. */
    EDITING,

    /** Reads the project/timeline itself. */
    PROJECT,
}

/**
 * A model-visible tool.
 *
 * [parameters] is a JSON Schema object. Helium validates every argument set
 * against a typed parser before anything touches the timeline, and the schema is
 * what the provider advertises to the model, so the two can never drift.
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val category: ToolCategory,
) {
    /** True when calling this tool changes the timeline. */
    val mutatesTimeline: Boolean get() = category == ToolCategory.EDITING

    /** True when the tool is guaranteed not to need any evidence image. */
    val isTextOnly: Boolean get() = name != ToolNames.GET_FRAME &&
        name != ToolNames.GET_ORIGINAL_FRAME &&
        name != ToolNames.INSPECT_SEGMENT &&
        name != ToolNames.GET_TEMPORAL_STRIP
}

/** A tool invocation requested by the model. */
@Serializable
data class ToolCallRequest(
    val callId: String,
    val name: String,
    /** Raw argument JSON exactly as produced by the model. */
    val argumentsJson: String,
)

/**
 * An image handed back to the model as evidence.
 *
 * Every image carries its provenance ([label], [timestampMs] and [quality]) so
 * the privacy accounting can report exactly what left the device.
 */
@Serializable
data class EvidenceImage(
    val label: String,
    val timestampMs: Long?,
    val quality: String,
    val base64: String,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    /** Which source this image belongs to, for the privacy report. */
    val sourceId: String? = null,
) {
    val approximateBytes: Int get() = (base64.length * 3) / 4
}

/** The result of executing a tool, ready to be appended to the conversation. */
@Serializable
data class ToolResultPayload(
    val callId: String,
    val name: String,
    val ok: Boolean,
    /** Compact JSON (or plain text) describing the result to the model. */
    val content: String,
    val images: List<EvidenceImage> = emptyList(),
    val error: String? = null,
    val durationMs: Long = 0L,
) {
    companion object {
        fun failure(callId: String, name: String, message: String): ToolResultPayload =
            ToolResultPayload(callId = callId, name = name, ok = false, content = "", error = message)
    }
}
