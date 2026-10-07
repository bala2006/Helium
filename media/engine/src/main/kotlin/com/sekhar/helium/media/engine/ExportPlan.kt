package com.sekhar.helium.media.engine

import com.sekhar.helium.core.model.CropRect
import com.sekhar.helium.core.model.ExportSettings
import com.sekhar.helium.core.model.NormalizedRect
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.TextItem
import com.sekhar.helium.core.model.TimeRange
import com.sekhar.helium.core.model.Timeline
import kotlin.math.roundToInt

/**
 * One segment of media to render.
 *
 * [sourceStartMs] and [sourceEndMs] always point at the ORIGINAL media, never at
 * the low-resolution proxy. The quality bar for export is "identical to what the
 * user shot", so the proxy is only ever used for preview and analysis.
 */
data class ExportClip(
    val sourceId: SourceId,
    val uri: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val speed: Float,
    val volume: Float,
    val muted: Boolean,
    val crop: NormalizedRect,
    val freezeHoldMs: Long,
    val hasAudio: Boolean,
) {
    val sourceDurationMs: Long get() = sourceEndMs - sourceStartMs
    val timelineDurationMs: Long get() = (sourceDurationMs / speed).toLong() + freezeHoldMs
}

/** Text that must be composited over the video. */
data class ExportTextItem(
    val item: TextItem,
)

/** Audio to mix in addition to the original media audio. */
data class ExportAudioClip(
    val uri: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val volume: Float,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val duckUnderSpeech: Boolean,
)

/**
 * A fully resolved, engine-independent description of the export.
 *
 * Rendering this is the only job of the media engine, which is why the plan is a
 * pure function of the project: it can be unit tested without any Android media
 * stack, and a different backend (MediaCodec, a native pipeline, or a server
 * renderer) can consume exactly the same structure.
 */
data class ExportPlan(
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val videoCodecMime: String,
    val audioCodecMime: String,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val clips: List<ExportClip>,
    val audioClips: List<ExportAudioClip>,
    val textItems: List<ExportTextItem>,
    /** Effects and features the current engine cannot yet bake in. */
    val unsupportedFeatures: List<String>,
) {
    val durationMs: Long get() = clips.sumOf { it.timelineDurationMs }
    val isEmpty: Boolean get() = clips.isEmpty()
}

/**
 * Builds an [ExportPlan] from a project.
 *
 * The builder also reports what it had to leave out, so an export never silently
 * drops an effect the user added: the app surfaces
 * [ExportPlan.unsupportedFeatures] in the export sheet.
 */
object ExportPlanBuilder {

    /**
     * @param bakedEffects effect kinds the calling engine can actually render.
     *   Anything outside this set is reported in [ExportPlan.unsupportedFeatures].
     */
    fun build(
        project: Project,
        settings: ExportSettings,
        bakedEffects: Set<RenderableEffect> = RenderableEffect.DEFAULT,
    ): ExportPlan {
        val primarySource = project.timeline.primaryVideoTrack?.clips?.firstOrNull()?.sourceId
        val sourceMetadata = primarySource?.let { project.source(it) }?.metadata

        val outputWidth: Int
        val outputHeight: Int
        if (settings.isOriginal && project.aspectRatio.isDefined && sourceMetadata != null) {
            // "Original" still honours an explicit aspect ratio change by cropping
            // the source to that ratio at full resolution.
            val (w, h) = explicitAspectSize(
                sourceMetadata.displayWidth,
                sourceMetadata.displayHeight,
                project.aspectRatio.ratio,
            )
            outputWidth = w
            outputHeight = h
        } else {
            val (w, h) = settings.resolveSize(
                sourceWidth = sourceMetadata?.displayWidth ?: 1080,
                sourceHeight = sourceMetadata?.displayHeight ?: 1920,
            )
            outputWidth = w
            outputHeight = h
        }

        val clips = mutableListOf<ExportClip>()
        project.timeline.allClips()
            .sortedBy { it.timelineStartMs }
            .forEach { clip ->
                val source = project.source(clip.sourceId) ?: return@forEach
                clips += ExportClip(
                    sourceId = clip.sourceId,
                    uri = source.uri,
                    sourceStartMs = clip.sourceRange.startMs,
                    sourceEndMs = clip.sourceRange.endMsExclusive,
                    timelineStartMs = clip.timelineStartMs,
                    speed = clip.speed,
                    volume = if (clip.muted) 0f else clip.volume,
                    muted = clip.muted,
                    crop = clip.crop.rect,
                    freezeHoldMs = clip.freeze?.holdMs ?: 0L,
                    hasAudio = source.metadata.hasAudio,
                )
            }

        // Only *added* audio is mixed here: a source's original audio travels with
        // its own video clip (`hasAudio`/`volume`/`muted`), so selecting by track
        // rather than by clip label keeps the two from ever being mixed twice.
        val audioClips = project.timeline.audioTracks
            .filter { it.isMusic }
            .flatMap { track -> track.clips.map { track.muted to it } }
            .sortedBy { (_, audio) -> audio.timelineStartMs }
            .mapNotNull { (trackMuted, audio) ->
                val source = project.source(audio.sourceId) ?: return@mapNotNull null
                ExportAudioClip(
                    uri = source.uri,
                    sourceStartMs = audio.sourceRange.startMs,
                    sourceEndMs = audio.sourceRange.endMsExclusive,
                    timelineStartMs = audio.timelineStartMs,
                    volume = if (audio.muted || trackMuted) 0f else audio.volume,
                    fadeInMs = audio.fadeInMs,
                    fadeOutMs = audio.fadeOutMs,
                    duckUnderSpeech = audio.duckUnderSpeech,
                )
            }

        val unsupported = mutableSetOf<String>()
        project.timeline.allClips().forEach { clip ->
            clip.effects.forEach { effect ->
                val kind = RenderableEffect.of(effect::class.simpleName.orEmpty())
                if (kind == null) {
                    unsupported += effect::class.simpleName.orEmpty()
                } else if (kind !in bakedEffects) {
                    unsupported += kind.displayName
                }
            }
            if (clip.freeze != null) unsupported += "Freeze frame hold"
        }
        if (project.timeline.allTextItems().isNotEmpty()) unsupported += "Text and captions"
        if (project.timeline.allOverlayItems().isNotEmpty()) unsupported += "Image overlays"
        if (project.timeline.allClips().any { it.speed != 1f }) unsupported += "Speed changes"
        if (audioClips.isNotEmpty()) unsupported += "Added music track"

        return ExportPlan(
            width = outputWidth,
            height = outputHeight,
            frameRate = settings.frameRate,
            videoCodecMime = settings.videoCodec.mimeType,
            audioCodecMime = settings.audioCodec.mimeType,
            videoBitrate = settings.videoBitrate,
            audioBitrate = settings.audioBitrate,
            clips = clips,
            audioClips = audioClips,
            textItems = project.timeline.allTextItems().map(::ExportTextItem),
            unsupportedFeatures = unsupported.sorted(),
        )
    }

    /**
     * Output size for an explicit aspect ratio on the "match original" preset.
     *
     * The lossless crop of the source — the largest rect of [aspectRatio] that
     * fits inside it — is used whenever it already covers the 1080p-class canvas
     * for that ratio. When it does not (the common case of a landscape clip the
     * user turned into a Reel) the canvas is used instead, so the export is the
     * expected 1080×1920 rather than a sub-1080p 607×1080 strip.
     */
    fun explicitAspectSize(sourceWidth: Int, sourceHeight: Int, aspectRatio: Float): Pair<Int, Int> {
        val (losslessWidth, losslessHeight) =
            aspectCorrectedSize(sourceWidth, sourceHeight, aspectRatio)
        val (canvasWidth, canvasHeight) = canvasSize(aspectRatio)
        return if (losslessWidth >= canvasWidth && losslessHeight >= canvasHeight) {
            losslessWidth to losslessHeight
        } else {
            canvasWidth to canvasHeight
        }
    }

    /**
     * 1080p-class output canvas for [aspectRatio]: 1080×1920 for 9:16, 1920×1080
     * for 16:9, 1080×1350 for 4:5. One side is always 1080, so a Reel is never
     * rendered narrower than the platform-standard width.
     */
    fun canvasSize(aspectRatio: Float): Pair<Int, Int> {
        if (!aspectRatio.isFinite() || aspectRatio <= 0f) return 1080 to 1920
        return if (aspectRatio >= 1f) {
            evenDimension((1080f * aspectRatio).roundToInt()) to 1080
        } else {
            1080 to evenDimension((1080f / aspectRatio).roundToInt())
        }
    }

    /** Largest even-sized output of [aspectRatio] that fits inside the source. */
    fun aspectCorrectedSize(sourceWidth: Int, sourceHeight: Int, aspectRatio: Float): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0 || aspectRatio <= 0f) return 1080 to 1920
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val width: Int
        val height: Int
        if (aspectRatio > sourceAspect) {
            width = sourceWidth
            height = (sourceWidth / aspectRatio).toInt().coerceAtLeast(2)
        } else {
            height = sourceHeight
            width = (sourceHeight * aspectRatio).toInt().coerceAtLeast(2)
        }
        return evenDimension(width) to evenDimension(height)
    }

    /** H.264/H.265 encoders require even dimensions, and no dimension may be 0. */
    private fun evenDimension(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return safe - safe % 2
    }

    /** Convenience used by tests and the export sheet preview. */
    fun estimateDurationMs(timeline: Timeline): Long = timeline.durationMs

    /** Crop that turns [sourceAspect] into [targetAspect], centred. */
    fun centeredCrop(sourceAspect: Float, targetAspect: Float): NormalizedRect {
        if (sourceAspect <= 0f || targetAspect <= 0f) return CropRect.FULL.rect
        return if (targetAspect < sourceAspect) {
            // Target is taller: crop the sides.
            val width = targetAspect / sourceAspect
            NormalizedRect((1f - width) / 2f, 0f, (1f + width) / 2f, 1f)
        } else {
            // Target is wider: crop top and bottom.
            val height = sourceAspect / targetAspect
            NormalizedRect(0f, (1f - height) / 2f, 1f, (1f + height) / 2f)
        }
    }

    /** Range helper kept for callers that need the plan's own bounds. */
    fun fullRange(durationMs: Long): TimeRange = TimeRange(0L, maxOf(0L, durationMs))
}

/** Effects the media engine knows how to render. */
enum class RenderableEffect(val displayName: String) {
    ZOOM("Zoom"),
    BLUR("Blur"),
    COLOR("Colour adjustment"),
    TRANSITION("Transition"),
    ;

    companion object {
        /**
         * Effects the shipping Media3 engine bakes into an export.
         *
         * Intentionally empty: framing (size, aspect and crop) is applied
         * structurally, but zoom ramps, colour grades, blur masks, transitions
         * and text are still preview-only, so the export sheet tells the user
         * exactly which edits were not rendered instead of dropping them quietly.
         */
        val DEFAULT: Set<RenderableEffect> = emptySet()

        /** Maps a model class name onto a renderable effect kind. */
        fun of(className: String): RenderableEffect? = when (className) {
            "ZoomEffect" -> ZOOM
            "BlurEffect" -> BLUR
            "ColorAdjustEffect" -> COLOR
            "TransitionEffect" -> TRANSITION
            else -> null
        }
    }
}
