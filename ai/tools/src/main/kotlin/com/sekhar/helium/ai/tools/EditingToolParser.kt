package com.sekhar.helium.ai.tools

import com.sekhar.helium.core.model.AddCaption
import com.sekhar.helium.core.model.AddImageOverlay
import com.sekhar.helium.core.model.AddMusic
import com.sekhar.helium.core.model.AddText
import com.sekhar.helium.core.model.AddTransition
import com.sekhar.helium.core.model.AddZoom
import com.sekhar.helium.core.model.AspectRatio
import com.sekhar.helium.core.model.BlurRegion
import com.sekhar.helium.core.model.CaptionCueSpec
import com.sekhar.helium.core.model.ClipId
import com.sekhar.helium.core.model.CropClip
import com.sekhar.helium.core.model.CropRect
import com.sekhar.helium.core.model.DeleteEdit
import com.sekhar.helium.core.model.DuckMusic
import com.sekhar.helium.core.model.DuplicateClip
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.FadeAudio
import com.sekhar.helium.core.model.FreezeFrame
import com.sekhar.helium.core.model.MoveClip
import com.sekhar.helium.core.model.MuteRange
import com.sekhar.helium.core.model.NormalizedPoint
import com.sekhar.helium.core.model.NormalizedRect
import com.sekhar.helium.core.model.ReframeClip
import com.sekhar.helium.core.model.RemoveCaption
import com.sekhar.helium.core.model.RemoveRange
import com.sekhar.helium.core.model.RestoreRange
import com.sekhar.helium.core.model.SetAspectRatioOp
import com.sekhar.helium.core.model.SetSpeed
import com.sekhar.helium.core.model.SetVolume
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SplitClip
import com.sekhar.helium.core.model.TextItemId
import com.sekhar.helium.core.model.TextStyle
import com.sekhar.helium.core.model.TimeRange
import com.sekhar.helium.core.model.TrackId
import com.sekhar.helium.core.model.TransitionBoundary
import com.sekhar.helium.core.model.TransitionKind
import com.sekhar.helium.core.model.TransitionSpec
import com.sekhar.helium.core.model.TrimClip
import com.sekhar.helium.core.model.WordTiming
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Result of turning model arguments into a typed operation. */
sealed interface ParseOutcome {
    data class Parsed(val operation: EditOperation) : ParseOutcome
    data class Invalid(val errors: List<String>) : ParseOutcome
}

/**
 * Validates raw model output into typed [EditOperation]s.
 *
 * The model is untrusted input, so this parser:
 *
 * * requires every field it needs and reports *all* missing ones at once;
 * * accepts numbers given as JSON strings (`"12500"`), because models do that;
 * * rejects inverted ranges, out-of-bounds coordinates and unknown enum values
 *   with a message that names the offending field;
 * * never constructs an operation that would throw in its own `init` block.
 *
 * The result is that a hallucinated tool call becomes a normal tool error the
 * model can correct, instead of a crash or a corrupted timeline.
 */
object EditingToolParser {

    fun parse(name: String, args: JsonObject, operationId: String): ParseOutcome {
        val errors = mutableListOf<String>()

        fun requiredString(key: String): String? {
            val value = args.string(key)
            if (value.isNullOrBlank()) errors += "missing required string '$key'"
            return value
        }

        fun requiredLong(key: String, min: Long = Long.MIN_VALUE): Long? {
            val value = args.long(key)
            if (value == null) errors += "missing required integer '$key'"
            else if (value < min) errors += "'$key' must be >= $min but was $value"
            return value
        }

        fun requiredRange(): TimeRange? {
            val start = requiredLong("startMs", 0L) ?: return null
            val end = requiredLong("endMs", 0L) ?: return null
            if (end <= start) errors += "'endMs' ($end) must be greater than 'startMs' ($start)"
            if (errors.isNotEmpty()) return null
            return TimeRange(start, end)
        }

        fun requiredAspect(key: String): AspectRatio? {
            val raw = requiredString(key) ?: return null
            val aspect = parseAspectRatio(raw)
            if (aspect == null) errors += "unsupported '$key' value '$raw' (use 9:16, 16:9, 1:1, 4:5 or original)"
            return aspect
        }

        val operation: EditOperation? = when (name) {
            ToolNames.SPLIT_CLIP -> {
                val clipId = requiredString("clipId")
                val at = requiredLong("atSourceMs", 0L)
                if (errors.isEmpty()) SplitClip(operationId, ClipId(clipId!!), at!!) else null
            }

            ToolNames.TRIM_CLIP -> {
                val clipId = requiredString("clipId")
                val range = requiredRange()
                if (errors.isEmpty()) TrimClip(operationId, ClipId(clipId!!), range!!) else null
            }

            ToolNames.REMOVE_RANGE -> {
                val videoId = requiredString("videoId")
                val range = requiredRange()
                if (errors.isEmpty()) RemoveRange(operationId, SourceId(videoId!!), range!!) else null
            }

            ToolNames.RESTORE_RANGE -> {
                val videoId = requiredString("videoId")
                val range = requiredRange()
                if (errors.isEmpty()) RestoreRange(operationId, SourceId(videoId!!), range!!) else null
            }

            ToolNames.MOVE_CLIP -> {
                val clipId = requiredString("clipId")
                val trackId = requiredString("toTrackId")
                val start = requiredLong("newTimelineStartMs", 0L)
                if (errors.isEmpty()) MoveClip(operationId, ClipId(clipId!!), TrackId(trackId!!), start!!) else null
            }

            ToolNames.DUPLICATE_CLIP -> {
                val clipId = requiredString("clipId")
                val start = requiredLong("newTimelineStartMs", 0L)
                if (errors.isEmpty()) DuplicateClip(operationId, ClipId(clipId!!), start!!) else null
            }

            ToolNames.SET_SPEED -> {
                val videoId = requiredString("videoId")
                val range = requiredRange()
                val speed = args.float("speed")
                if (speed == null) errors += "missing required number 'speed'"
                else if (speed !in 0.1f..10f) errors += "'speed' must be between 0.1 and 10 but was $speed"
                if (errors.isEmpty()) SetSpeed(operationId, SourceId(videoId!!), range!!, speed!!) else null
            }

            ToolNames.SET_VOLUME -> {
                val videoId = requiredString("videoId")
                val range = requiredRange()
                val volume = args.float("volume")
                if (volume == null) errors += "missing required number 'volume'"
                else if (volume !in 0f..4f) errors += "'volume' must be between 0 and 4 but was $volume"
                if (errors.isEmpty()) SetVolume(operationId, SourceId(videoId!!), range!!, volume!!) else null
            }

            ToolNames.MUTE_RANGE -> {
                val videoId = requiredString("videoId")
                val range = requiredRange()
                if (errors.isEmpty()) MuteRange(operationId, SourceId(videoId!!), range!!) else null
            }

            ToolNames.SET_ASPECT_RATIO -> {
                val aspect = requiredAspect("aspectRatio")
                if (errors.isEmpty()) SetAspectRatioOp(operationId, aspect!!) else null
            }

            ToolNames.CROP_CLIP -> {
                val clipId = requiredString("clipId")
                val rect = readRect(args, errors)
                if (errors.isEmpty()) CropClip(operationId, ClipId(clipId!!), CropRect(rect!!)) else null
            }

            ToolNames.REFRAME_CLIP -> {
                val clipId = requiredString("clipId")
                val aspect = requiredAspect("targetAspect")
                val focusX = args.float("focusX") ?: 0.5f
                val focusY = args.float("focusY") ?: 0.5f
                val zoom = args.float("zoomScale") ?: 1f
                if (focusX !in 0f..1f) errors += "'focusX' must be within 0..1 but was $focusX"
                if (focusY !in 0f..1f) errors += "'focusY' must be within 0..1 but was $focusY"
                if (zoom < 1f) errors += "'zoomScale' must be >= 1 but was $zoom"
                if (errors.isEmpty()) {
                    ReframeClip(operationId, ClipId(clipId!!), aspect!!, focusX, focusY, zoom)
                } else {
                    null
                }
            }

            ToolNames.ADD_ZOOM -> {
                val videoId = requiredString("videoId")
                val range = requiredRange()
                val scale = args.float("scale")
                if (scale == null) errors += "missing required number 'scale'"
                else if (scale !in 1f..8f) errors += "'scale' must be between 1 and 8 but was $scale"
                if (errors.isEmpty()) {
                    AddZoom(
                        operationId = operationId,
                        sourceId = SourceId(videoId!!),
                        range = range!!,
                        scale = scale!!,
                        focusX = args.float("focusX") ?: 0.5f,
                        focusY = args.float("focusY") ?: 0.5f,
                        easeInMs = args.long("easeInMs") ?: 250L,
                        easeOutMs = args.long("easeOutMs") ?: 250L,
                    )
                } else {
                    null
                }
            }

            ToolNames.ADD_TEXT -> {
                val start = requiredLong("timelineStartMs", 0L)
                val duration = requiredLong("durationMs", 1L)
                val text = requiredString("text")
                if (errors.isEmpty()) {
                    AddText(operationId, start!!, duration!!, text!!, styleForPreset(args.string("preset")))
                } else {
                    null
                }
            }

            ToolNames.ADD_CAPTION -> {
                val videoId = requiredString("videoId")
                val cues = readCues(args, errors)
                if (errors.isEmpty() && cues.isNullOrEmpty()) errors += "'cues' must contain at least one cue"
                if (errors.isEmpty()) {
                    AddCaption(
                        operationId = operationId,
                        sourceId = SourceId(videoId!!),
                        cues = cues!!,
                        style = styleForPreset(args.string("preset") ?: "reel_caption"),
                    )
                } else {
                    null
                }
            }

            ToolNames.REMOVE_CAPTION -> {
                val ids = args.array("textItemIds")?.mapNotNull { it.stringValue()?.takeIf(String::isNotBlank) }
                    ?.map { TextItemId(it) } ?: emptyList()
                val containing = args.string("containingText")?.takeIf { it.isNotBlank() }
                val videoId = args.string("videoId")?.takeIf { it.isNotBlank() }
                if (ids.isEmpty() && containing == null && videoId == null) {
                    errors += "provide at least one of 'textItemIds', 'containingText' or 'videoId'"
                }
                if (errors.isEmpty()) {
                    RemoveCaption(operationId, ids, containing, videoId?.let { SourceId(it) })
                } else {
                    null
                }
            }

            ToolNames.ADD_IMAGE_OVERLAY -> {
                val uri = requiredString("imageUri")
                val start = requiredLong("timelineStartMs", 0L)
                val duration = requiredLong("durationMs", 1L)
                val x = args.float("x") ?: 0.5f
                val y = args.float("y") ?: 0.5f
                if (x !in 0f..1f) errors += "'x' must be within 0..1 but was $x"
                if (y !in 0f..1f) errors += "'y' must be within 0..1 but was $y"
                if (errors.isEmpty()) {
                    AddImageOverlay(
                        operationId = operationId,
                        imageUri = uri!!,
                        timelineStartMs = start!!,
                        durationMs = duration!!,
                        position = NormalizedPoint(x, y),
                        scale = (args.float("scale") ?: 0.4f).coerceIn(0.05f, 1f),
                        opacity = (args.float("opacity") ?: 1f).coerceIn(0f, 1f),
                    )
                } else {
                    null
                }
            }

            ToolNames.FREEZE_FRAME -> {
                val videoId = requiredString("videoId")
                val at = requiredLong("atSourceMs", 0L)
                val hold = args.long("holdMs") ?: 1_500L
                if (hold <= 0L) errors += "'holdMs' must be positive but was $hold"
                if (errors.isEmpty()) {
                    FreezeFrame(
                        operationId = operationId,
                        sourceId = SourceId(videoId!!),
                        atSourceMs = at!!,
                        holdMs = hold,
                        overlayText = args.string("overlayText")?.takeIf { it.isNotBlank() },
                    )
                } else {
                    null
                }
            }

            ToolNames.ADD_TRANSITION -> {
                val clipId = requiredString("clipId")
                val boundaryRaw = requiredString("boundary")
                val kindRaw = requiredString("kind")
                val boundary = when (boundaryRaw?.lowercase()) {
                    "start" -> TransitionBoundary.START
                    "end" -> TransitionBoundary.END
                    else -> null
                }
                if (boundary == null && boundaryRaw != null) {
                    errors += "unsupported 'boundary' value '$boundaryRaw' (use start or end)"
                }
                val kind = parseTransitionKind(kindRaw)
                if (kind == null && kindRaw != null) {
                    errors += "unsupported 'kind' value '$kindRaw'"
                }
                if (errors.isEmpty()) {
                    AddTransition(
                        operationId = operationId,
                        clipId = ClipId(clipId!!),
                        boundary = boundary!!,
                        spec = TransitionSpec(kind!!, args.long("durationMs") ?: 400L),
                    )
                } else {
                    null
                }
            }

            ToolNames.ADD_MUSIC -> {
                val musicSourceId = requiredString("musicSourceId")
                if (errors.isEmpty()) {
                    AddMusic(
                        operationId = operationId,
                        musicSourceId = SourceId(musicSourceId!!),
                        timelineStartMs = args.long("timelineStartMs") ?: 0L,
                        durationMs = args.long("durationMs")?.takeIf { it > 0L },
                        volume = (args.float("volume") ?: 0.55f).coerceIn(0f, 2f),
                        duckUnderSpeech = args.boolean("duckUnderSpeech") ?: true,
                    )
                } else {
                    null
                }
            }

            ToolNames.DUCK_MUSIC -> {
                val audioClipId = requiredString("audioClipId")
                if (errors.isEmpty()) {
                    DuckMusic(
                        operationId = operationId,
                        audioClipId = ClipId(audioClipId!!),
                        duckAmountDb = (args.float("duckAmountDb") ?: -12f).coerceIn(-40f, 0f),
                        enabled = args.boolean("enabled") ?: true,
                    )
                } else {
                    null
                }
            }

            ToolNames.FADE_AUDIO -> {
                val audioClipId = requiredString("audioClipId")
                val fadeIn = args.long("fadeInMs") ?: 0L
                val fadeOut = args.long("fadeOutMs") ?: 0L
                if (fadeIn < 0L || fadeOut < 0L) errors += "fade durations cannot be negative"
                if (errors.isEmpty()) {
                    FadeAudio(operationId, ClipId(audioClipId!!), fadeIn, fadeOut)
                } else {
                    null
                }
            }

            ToolNames.BLUR_REGION -> {
                val videoId = requiredString("videoId")
                val range = requiredRange()
                val rect = readRect(args, errors)
                if (errors.isEmpty()) {
                    BlurRegion(
                        operationId = operationId,
                        sourceId = SourceId(videoId!!),
                        range = range!!,
                        region = rect!!,
                        trackSubject = args.boolean("trackSubject") ?: false,
                    )
                } else {
                    null
                }
            }

            ToolNames.DELETE_EDIT -> {
                val target = requiredString("targetTransactionId")
                if (errors.isEmpty()) DeleteEdit(operationId, target!!) else null
            }

            else -> {
                errors += "unknown editing tool '$name'"
                null
            }
        }

        return if (operation != null && errors.isEmpty()) {
            ParseOutcome.Parsed(operation)
        } else {
            ParseOutcome.Invalid(errors.ifEmpty { listOf("could not parse arguments for '$name'") })
        }
    }

    // -----------------------------------------------------------------------------
    // Field readers
    // -----------------------------------------------------------------------------

    private fun readRect(args: JsonObject, errors: MutableList<String>): NormalizedRect? {
        val left = args.float("left")
        val top = args.float("top")
        val right = args.float("right")
        val bottom = args.float("bottom")
        if (left == null || top == null || right == null || bottom == null) {
            errors += "crop/blur region needs numeric left, top, right and bottom"
            return null
        }
        listOf("left" to left, "top" to top, "right" to right, "bottom" to bottom).forEach { (name, value) ->
            if (value < 0f || value > 1f) errors += "'$name' must be within 0..1 but was $value"
        }
        if (right <= left) errors += "'right' must be greater than 'left'"
        if (bottom <= top) errors += "'bottom' must be greater than 'top'"
        if (errors.isNotEmpty()) return null
        return NormalizedRect(left, top, right, bottom)
    }

    private fun readCues(args: JsonObject, errors: MutableList<String>): List<CaptionCueSpec>? {
        val raw = args.array("cues")
        if (raw == null) {
            errors += "missing required array 'cues'"
            return null
        }
        val cues = mutableListOf<CaptionCueSpec>()
        raw.forEachIndexed { index, element ->
            val cue = element as? JsonObject
            if (cue == null) {
                errors += "cues[$index] must be an object"
                return@forEachIndexed
            }
            val start = cue.long("startMs")
            val end = cue.long("endMs")
            val text = cue.string("text")
            if (start == null || end == null) {
                errors += "cues[$index] needs numeric startMs and endMs"
                return@forEachIndexed
            }
            if (end <= start || start < 0L) {
                errors += "cues[$index] has an invalid range ($start..$end)"
                return@forEachIndexed
            }
            if (text.isNullOrBlank()) {
                errors += "cues[$index] needs non-empty text"
                return@forEachIndexed
            }
            val words = cue.array("words")?.mapNotNull { wordElement ->
                val word = wordElement as? JsonObject ?: return@mapNotNull null
                val wordStart = word.long("startMs") ?: return@mapNotNull null
                val wordEnd = word.long("endMs") ?: return@mapNotNull null
                val wordText = word.string("text") ?: return@mapNotNull null
                if (wordEnd <= wordStart || wordStart < 0L) return@mapNotNull null
                WordTiming(wordText, TimeRange(wordStart, wordEnd))
            } ?: emptyList()
            cues += CaptionCueSpec(TimeRange(start, end), text, words)
        }
        return cues
    }

    private fun styleForPreset(preset: String?): TextStyle = when (preset?.lowercase()) {
        "title" -> TextStyle.TITLE
        "reel_caption" -> TextStyle.REEL_CAPTION
        "plain" -> TextStyle(backgroundArgb = null, shadow = false, safeZoneAware = true)
        else -> TextStyle.REEL_CAPTION
    }

    private fun parseTransitionKind(raw: String?): TransitionKind? = when (raw?.lowercase()) {
        "cut" -> TransitionKind.CUT
        "fade" -> TransitionKind.FADE
        "cross_dissolve", "cross-dissolve", "dissolve" -> TransitionKind.CROSS_DISSOLVE
        "slide_left" -> TransitionKind.SLIDE_LEFT
        "slide_up" -> TransitionKind.SLIDE_UP
        "wipe" -> TransitionKind.WIPE
        "zoom_blur" -> TransitionKind.ZOOM_BLUR
        else -> null
    }

    /** Accepts `9:16`, `9x16`, `portrait`, `original`, or a decimal ratio. */
    fun parseAspectRatio(raw: String): AspectRatio? {
        val value = raw.trim().lowercase()
        return when (value) {
            "9:16", "9x16", "portrait", "vertical", "reel", "story", "short" -> AspectRatio.PORTRAIT_9_16
            "16:9", "16x9", "landscape", "wide" -> AspectRatio.LANDSCAPE_16_9
            "1:1", "1x1", "square" -> AspectRatio.SQUARE_1_1
            "4:5", "4x5" -> AspectRatio.VERTICAL_4_5
            "original", "source", "keep" -> AspectRatio.ORIGINAL
            else -> {
                val ratio = value.toDoubleOrNull() ?: value.split(':', 'x')
                    .takeIf { it.size == 2 }
                    ?.let { (a, b) -> a.trim().toDoubleOrNull()?.let { n -> b.trim().toDoubleOrNull()?.let { d -> if (d == 0.0) null else n / d } } }
                when {
                    ratio == null -> null
                    kotlin.math.abs(ratio - 9.0 / 16.0) < 0.02 -> AspectRatio.PORTRAIT_9_16
                    kotlin.math.abs(ratio - 16.0 / 9.0) < 0.05 -> AspectRatio.LANDSCAPE_16_9
                    kotlin.math.abs(ratio - 1.0) < 0.02 -> AspectRatio.SQUARE_1_1
                    kotlin.math.abs(ratio - 4.0 / 5.0) < 0.02 -> AspectRatio.VERTICAL_4_5
                    else -> null
                }
            }
        }
    }

    // --- lenient element accessors -------------------------------------------------

    private fun JsonObject.string(key: String): String? = this[key]?.stringValue()

    private fun JsonObject.long(key: String): Long? = this[key]?.let { element ->
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> element.longOrNull
                ?: element.doubleOrNull?.toLong()
                ?: element.content.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JsonObject.float(key: String): Float? = this[key]?.let { element ->
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> element.doubleOrNull
                ?: element.longOrNull?.toDouble()
                ?: element.content.trim().toDoubleOrNull()
            else -> null
        }
    }?.toFloat()

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.let { element ->
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> element.booleanOrNull
                ?: when (element.content.trim().lowercase()) {
                    "true", "yes", "1" -> true
                    "false", "no", "0" -> false
                    else -> null
                }
            else -> null
        }
    }

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonElement.stringValue(): String? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> content.takeIf { it.isNotBlank() }
        else -> null
    }
}
