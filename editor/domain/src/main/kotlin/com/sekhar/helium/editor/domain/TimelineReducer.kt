package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.AddCaption
import com.sekhar.helium.core.model.AddImageOverlay
import com.sekhar.helium.core.model.AddMusic
import com.sekhar.helium.core.model.AddText
import com.sekhar.helium.core.model.AddTransition
import com.sekhar.helium.core.model.AddZoom
import com.sekhar.helium.core.model.AudioClip
import com.sekhar.helium.core.model.AudioTrack
import com.sekhar.helium.core.model.BlurEffect
import com.sekhar.helium.core.model.BlurRegion
import com.sekhar.helium.core.model.Clip
import com.sekhar.helium.core.model.ClipId
import com.sekhar.helium.core.model.ColorAdjustEffect
import com.sekhar.helium.core.model.CropClip
import com.sekhar.helium.core.model.DeleteEdit
import com.sekhar.helium.core.model.DuckMusic
import com.sekhar.helium.core.model.DuplicateClip
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.Effect
import com.sekhar.helium.core.model.FadeAudio
import com.sekhar.helium.core.model.FreezeFrame
import com.sekhar.helium.core.model.FreezeSpec
import com.sekhar.helium.core.model.MoveClip
import com.sekhar.helium.core.model.MuteRange
import com.sekhar.helium.core.model.OverlayItem
import com.sekhar.helium.core.model.OverlayItemId
import com.sekhar.helium.core.model.OverlayTrack
import com.sekhar.helium.core.model.ReframeClip
import com.sekhar.helium.core.model.RemoveCaption
import com.sekhar.helium.core.model.RemoveRange
import com.sekhar.helium.core.model.RestoreRange
import com.sekhar.helium.core.model.SetAspectRatioOp
import com.sekhar.helium.core.model.SetColorAdjust
import com.sekhar.helium.core.model.SetSpeed
import com.sekhar.helium.core.model.SetVolume
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SplitClip
import com.sekhar.helium.core.model.TextItem
import com.sekhar.helium.core.model.TextItemId
import com.sekhar.helium.core.model.TextTrack
import com.sekhar.helium.core.model.Timeline
import com.sekhar.helium.core.model.TimeRange
import com.sekhar.helium.core.model.TrackId
import com.sekhar.helium.core.model.TransitionBoundary
import com.sekhar.helium.core.model.TransitionEffect
import com.sekhar.helium.core.model.TrimClip
import com.sekhar.helium.core.model.VideoMetadata
import com.sekhar.helium.core.model.VideoTrack
import com.sekhar.helium.core.model.WordTiming
import com.sekhar.helium.core.model.ZoomEffect
import kotlin.math.roundToLong

/**
 * Applies a single [EditOperation] to a [Timeline].
 *
 * Design notes that make AI editing trustworthy:
 *
 * * **Pure.** The reducer has no state; the same timeline plus the same
 *   operation always produce the same result. Undo is therefore just "replay one
 *   fewer transaction", with no inverse-operation bookkeeping.
 * * **Never throws.** Every failure is returned as [ReduceResult.Failure], so a
 *   bad model output can never corrupt a project.
 * * **Video tracks stay packed.** Clips on a video track are laid out
 *   back-to-back, so cutting a range out ripples the rest of the track closed
 *   instead of leaving a black gap. Audio, text and overlay tracks keep absolute
 *   positions because music and captions need free placement.
 *
 * Every id the reducer creates is derived from the operation that created it
 * (see [derivedId]), never from a random source. That is what makes deterministic
 * replay work: rebuilding the timeline from the history must reproduce identical
 * clip ids, both so undo/redo cannot churn the timeline and so a `clipId` the AI
 * referenced in an earlier turn stays valid.
 */
class TimelineReducer {

    /** Applies [operation] to [timeline], using [sources] to validate ranges. */
    fun apply(
        timeline: Timeline,
        operation: EditOperation,
        sources: Map<SourceId, VideoMetadata> = emptyMap(),
    ): ReduceResult = when (operation) {
        is SplitClip -> split(timeline, operation)
        is TrimClip -> trim(timeline, operation, sources)
        is RemoveRange -> removeRange(timeline, operation, sources)
        is RestoreRange -> restoreRange(timeline, operation, sources)
        is MoveClip -> move(timeline, operation)
        is DuplicateClip -> duplicate(timeline, operation)
        is SetSpeed -> setSpeed(timeline, operation, sources)
        is SetVolume -> setVolume(timeline, operation, sources)
        is MuteRange -> setVolume(
            timeline,
            SetVolume(operation.operationId, operation.sourceId, operation.range, 0f),
            sources,
        )
        is SetAspectRatioOp -> ReduceResult.Success(timeline)
        is CropClip -> crop(timeline, operation)
        is ReframeClip -> reframe(timeline, operation, sources)
        is AddZoom -> addZoom(timeline, operation, sources)
        is SetColorAdjust -> setColor(timeline, operation, sources)
        is BlurRegion -> addBlur(timeline, operation, sources)
        is AddText -> addText(timeline, operation)
        is AddCaption -> addCaption(timeline, operation, sources)
        is RemoveCaption -> removeCaption(timeline, operation)
        is AddImageOverlay -> addOverlay(timeline, operation)
        is FreezeFrame -> freeze(timeline, operation, sources)
        is AddTransition -> addTransition(timeline, operation)
        is AddMusic -> addMusic(timeline, operation, sources)
        is DuckMusic -> duckMusic(timeline, operation)
        is FadeAudio -> fadeAudio(timeline, operation)
        // History-level operations are handled by EditEngine, not the timeline.
        is DeleteEdit -> ReduceResult.Success(timeline)
    }

    // =============================================================================
    // Structure
    // =============================================================================

    private fun split(timeline: Timeline, op: SplitClip): ReduceResult {
        val track = trackContaining(timeline, op.clipId)
            ?: return failure(op, "No clip with id ${op.clipId.value}")
        val clip = track.clips.first { it.id == op.clipId }
        val offset = op.atSourceMs - clip.sourceRange.startMs
        if (offset <= 0L || offset >= clip.sourceDurationMs) {
            return failure(op, "Split point ${op.atSourceMs}ms is not inside clip ${op.clipId.value}")
        }
        val pieces = reslice(clip, RangeMath.splitAt(clip.sourceRange, listOf(op.atSourceMs)))
        return ReduceResult.Success(
            timeline.withTrack(track.id, pack(track.copy(clips = replaceIn(track.clips, clip.id, pieces)))),
        )
    }

    private fun trim(timeline: Timeline, op: TrimClip, sources: Map<SourceId, VideoMetadata>): ReduceResult {
        val track = trackContaining(timeline, op.clipId)
            ?: return failure(op, "No clip with id ${op.clipId.value}")
        val clip = track.clips.first { it.id == op.clipId }
        val limit = sources[clip.sourceId]?.durationMs
        if (limit != null && op.newSourceRange.endMsExclusive > limit) {
            return failure(op, "Trim end ${op.newSourceRange.endMsExclusive}ms exceeds source length ${limit}ms")
        }
        if (op.newSourceRange.isEmpty) return failure(op, "Trimmed clip would be empty")
        val trimmed = reslice(clip, listOf(op.newSourceRange)).firstOrNull()
            ?: return failure(op, "Trim produced no clip")
        val newClips = replaceIn(track.clips, clip.id, listOf(trimmed.copy(id = clip.id)))
        return ReduceResult.Success(timeline.withTrack(track.id, pack(track.copy(clips = newClips))))
    }

    private fun removeRange(
        timeline: Timeline,
        op: RemoveRange,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        val metadata = sources[op.sourceId] ?: return failure(op, "Unknown source ${op.sourceId.value}")
        val range = op.range.clampTo(TimeRange(0L, metadata.durationMs))
        if (range.isEmpty) return failure(op, "Removal range is empty")

        // Video: drop the range out of every clip that shows it, then close the gaps.
        val videos = timeline.videoTracks.map { track ->
            val updated = track.clips.flatMap { clip ->
                if (clip.sourceId != op.sourceId) listOf(clip)
                else {
                    val keep = RangeMath.subtract(clip.sourceRange, listOf(range))
                    if (keep.isEmpty()) emptyList() else reslice(clip, keep)
                }
            }
            pack(track.copy(clips = updated))
        }

        // Audio: same treatment, but positions are absolute so we remove only the media.
        val audios = timeline.audioTracks.map { track ->
            val updated = track.clips.flatMap { clip ->
                if (clip.sourceId != op.sourceId) listOf(clip)
                else {
                    val keep = RangeMath.subtract(clip.sourceRange, listOf(range))
                    if (keep.isEmpty()) emptyList() else resliceAudio(clip, keep)
                }
            }
            track.copy(clips = updated)
        }

        // Captions that were generated from this source can no longer be trusted
        // for the removed span, so they are dropped rather than left desynced.
        val text = timeline.textTracks.map { track ->
            track.copy(items = track.items.filterNot { it.lockedToSource == op.sourceId && it.range.overlaps(range) })
        }

        return ReduceResult.Success(
            timeline.copy(videoTracks = videos, audioTracks = audios, textTracks = text),
        )
    }

    private fun restoreRange(
        timeline: Timeline,
        op: RestoreRange,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        val metadata = sources[op.sourceId] ?: return failure(op, "Unknown source ${op.sourceId.value}")
        val range = op.range.clampTo(TimeRange(0L, metadata.durationMs))
        if (range.isEmpty) return failure(op, "Restore range is empty")

        var restored = false
        var restoredIndex = 0
        val tracks = if (timeline.videoTracks.isEmpty()) {
            restored = true
            listOf(
                pack(
                    VideoTrack(
                        id = TrackId(derivedId(op, "track")),
                        clips = listOf(
                            Clip(
                                id = ClipId(derivedId(op, "restore-${restoredIndex++}")),
                                sourceId = op.sourceId,
                                sourceRange = range,
                            ),
                        ),
                    ),
                ),
            )
        } else {
            val primaryId = timeline.primaryVideoTrack?.id
            timeline.videoTracks.map { track ->
                val showsSource = track.clips.any { it.sourceId == op.sourceId }
                if (!showsSource && track.id != primaryId) return@map track
                // Re-insert in source order: straight after the last clip of the same
                // source that ends before the restored range.
                val insertAfter = track.clips.indexOfLast {
                    it.sourceId == op.sourceId && it.sourceRange.endMsExclusive <= range.startMs
                }
                val clips = track.clips.toMutableList()
                val restoredClip = Clip(
                    id = ClipId(derivedId(op, "restore-${restoredIndex++}")),
                    sourceId = op.sourceId,
                    sourceRange = range,
                )
                clips.add(if (insertAfter < 0) 0 else insertAfter + 1, restoredClip)
                restored = true
                pack(track.copy(clips = clips))
            }
        }

        if (!restored) return failure(op, "Track for source ${op.sourceId.value} was not found")
        return ReduceResult.Success(timeline.copy(videoTracks = tracks))
    }

    private fun move(timeline: Timeline, op: MoveClip): ReduceResult {
        val fromTrack = trackContaining(timeline, op.clipId)
            ?: return failure(op, "No clip with id ${op.clipId.value}")
        val clip = fromTrack.clips.first { it.id == op.clipId }
        val toTrack = timeline.videoTrack(op.toTrackId) ?: fromTrack

        val withoutClip = fromTrack.copy(clips = fromTrack.clips.filterNot { it.id == op.clipId })
        val targetClips = if (toTrack.id == fromTrack.id) withoutClip.clips else toTrack.clips
        val insertIndex = targetClips.indexOfFirst { it.timelineStartMs >= op.newTimelineStartMs }
            .let { if (it < 0) targetClips.size else it }
        val inserted = targetClips.toMutableList().also { it.add(insertIndex, clip) }

        val updated = timeline.videoTracks.map { track ->
            when (track.id) {
                fromTrack.id -> pack(withoutClip)
                toTrack.id -> pack(track.copy(clips = inserted))
                else -> track
            }
        }
        return ReduceResult.Success(timeline.copy(videoTracks = updated))
    }

    private fun duplicate(timeline: Timeline, op: DuplicateClip): ReduceResult {
        val track = trackContaining(timeline, op.clipId)
            ?: return failure(op, "No clip with id ${op.clipId.value}")
        val clip = track.clips.first { it.id == op.clipId }
        val copy = clip.copy(id = ClipId(derivedId(op, "duplicate")))
        val insertIndex = track.clips.indexOfFirst { it.timelineStartMs >= op.newTimelineStartMs }
            .let { if (it < 0) track.clips.size else it }
        val clips = track.clips.toMutableList().also { it.add(insertIndex, copy) }
        return ReduceResult.Success(timeline.withTrack(track.id, pack(track.copy(clips = clips))))
    }

    // =============================================================================
    // Speed and audio
    // =============================================================================

    private fun setSpeed(
        timeline: Timeline,
        op: SetSpeed,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        if (sources[op.sourceId] == null) return failure(op, "Unknown source ${op.sourceId.value}")
        return mapClipsInRange(timeline, op.sourceId, op.range, op) { clip, pieceRange ->
            clip.copy(sourceRange = pieceRange, speed = op.speed)
        }
    }

    private fun setVolume(
        timeline: Timeline,
        op: SetVolume,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        if (sources[op.sourceId] == null) return failure(op, "Unknown source ${op.sourceId.value}")
        return mapClipsInRange(timeline, op.sourceId, op.range, op) { clip, pieceRange ->
            clip.copy(sourceRange = pieceRange, volume = op.volume)
        }
    }

    // =============================================================================
    // Framing
    // =============================================================================

    private fun crop(timeline: Timeline, op: CropClip): ReduceResult {
        val track = trackContaining(timeline, op.clipId)
            ?: return failure(op, "No clip with id ${op.clipId.value}")
        val clips = track.clips.map { if (it.id == op.clipId) it.copy(crop = op.crop) else it }
        return ReduceResult.Success(timeline.withTrack(track.id, pack(track.copy(clips = clips))))
    }

    private fun reframe(
        timeline: Timeline,
        op: ReframeClip,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        val track = trackContaining(timeline, op.clipId)
            ?: return failure(op, "No clip with id ${op.clipId.value}")
        val clip = track.clips.first { it.id == op.clipId }
        val metadata = sources[clip.sourceId]
            ?: return failure(op, "Unknown source ${clip.sourceId.value}")
        val cropRect = CropMath.cropForAspect(
            sourceWidth = metadata.displayWidth,
            sourceHeight = metadata.displayHeight,
            targetAspect = op.targetAspect.ratio,
            focusX = op.focusX,
            focusY = op.focusY,
            zoomScale = op.zoomScale,
        )
        val clips = track.clips.map { if (it.id == op.clipId) it.copy(crop = cropRect) else it }
        return ReduceResult.Success(timeline.withTrack(track.id, pack(track.copy(clips = clips))))
    }

    private fun addZoom(
        timeline: Timeline,
        op: AddZoom,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        if (sources[op.sourceId] == null) return failure(op, "Unknown source ${op.sourceId.value}")
        return mapClipsInRange(timeline, op.sourceId, op.range, op) { clip, pieceRange ->
            val pieceDuration = clip.timelineDurationMs
            clip.copy(
                sourceRange = pieceRange,
                effects = clip.effects + ZoomEffect(
                    id = derivedId(op, "zoom"),
                    range = TimeRange(0L, pieceDuration),
                    scale = op.scale,
                    focusX = op.focusX,
                    focusY = op.focusY,
                    easeInMs = op.easeInMs,
                    easeOutMs = op.easeOutMs,
                ),
            )
        }
    }

    private fun setColor(
        timeline: Timeline,
        op: SetColorAdjust,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        if (sources[op.sourceId] == null) return failure(op, "Unknown source ${op.sourceId.value}")
        return mapClipsInRange(timeline, op.sourceId, op.range, op) { clip, pieceRange ->
            val pieceDuration = clip.timelineDurationMs
            clip.copy(
                sourceRange = pieceRange,
                effects = clip.effects + ColorAdjustEffect(
                    id = derivedId(op, "color"),
                    range = TimeRange(0L, pieceDuration),
                    brightness = op.brightness,
                    contrast = op.contrast,
                    saturation = op.saturation,
                    warmth = op.warmth,
                    vignette = op.vignette,
                ),
            )
        }
    }

    private fun addBlur(
        timeline: Timeline,
        op: BlurRegion,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        if (sources[op.sourceId] == null) return failure(op, "Unknown source ${op.sourceId.value}")
        return mapClipsInRange(timeline, op.sourceId, op.range, op) { clip, pieceRange ->
            val pieceDuration = clip.timelineDurationMs
            clip.copy(
                sourceRange = pieceRange,
                effects = clip.effects + BlurEffect(
                    id = derivedId(op, "blur"),
                    range = TimeRange(0L, pieceDuration),
                    region = op.region,
                    featherPx = op.featherPx,
                    trackSubject = op.trackSubject,
                ),
            )
        }
    }

    // =============================================================================
    // Text, captions, overlays
    // =============================================================================

    private fun addText(timeline: Timeline, op: AddText): ReduceResult {
        if (op.text.isBlank()) return failure(op, "Text is blank")
        if (op.durationMs <= 0L) return failure(op, "Text duration must be positive")
        val range = TimeRange(op.timelineStartMs, op.timelineStartMs + op.durationMs)
        val item = TextItem(
            id = TextItemId(derivedId(op, "text")),
            range = range,
            text = op.text,
            style = op.style,
        )
        return ReduceResult.Success(timeline.withTextTrack(op.trackId) { it.copy(items = it.items + item) })
    }

    private fun addCaption(
        timeline: Timeline,
        op: AddCaption,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        if (sources[op.sourceId] == null) return failure(op, "Unknown source ${op.sourceId.value}")
        if (op.cues.isEmpty()) return failure(op, "Caption request has no cues")

        val items = mutableListOf<TextItem>()
        timeline.videoTracks.forEach { track ->
            track.clips.filter { it.sourceId == op.sourceId }.forEach { clip ->
                op.cues.forEach { cue ->
                    val overlap = cue.range.intersect(clip.sourceRange) ?: return@forEach
                    val start = clip.timelineStartMs +
                        clip.sourceOffsetToTimelineOffset(overlap.startMs - clip.sourceRange.startMs)
                    val end = clip.timelineStartMs +
                        clip.sourceOffsetToTimelineOffset(overlap.endMsExclusive - clip.sourceRange.startMs)
                    if (end <= start) return@forEach
                    val words = cue.words.mapNotNull { word ->
                        val wordOverlap = word.range.intersect(clip.sourceRange) ?: return@mapNotNull null
                        val wordStart = clip.timelineStartMs +
                            clip.sourceOffsetToTimelineOffset(wordOverlap.startMs - clip.sourceRange.startMs)
                        val wordEnd = clip.timelineStartMs +
                            clip.sourceOffsetToTimelineOffset(wordOverlap.endMsExclusive - clip.sourceRange.startMs)
                        if (wordEnd <= wordStart) null else WordTiming(word.text, TimeRange(wordStart, wordEnd))
                    }
                    items += TextItem(
                        id = TextItemId(derivedId(op, "cue-${items.size}")),
                        range = TimeRange(start, end),
                        text = cue.text,
                        style = op.style,
                        words = words,
                        isCaption = true,
                        lockedToSource = op.sourceId,
                    )
                }
            }
        }
        if (items.isEmpty()) return failure(op, "No clip shows source ${op.sourceId.value}")

        return ReduceResult.Success(
            timeline.withTextTrack(op.trackId) { it.copy(items = it.items + items) },
        )
    }

    private fun removeCaption(timeline: Timeline, op: RemoveCaption): ReduceResult {
        val needle = op.containingText?.trim()?.lowercase()
        if (op.textItemIds.isEmpty() && needle == null && op.sourceId == null) {
            return failure(op, "remove_caption needs ids, text or a source")
        }
        return ReduceResult.Success(
            timeline.copy(
                textTracks = timeline.textTracks.map { track ->
                    track.copy(
                        items = track.items.filterNot { item ->
                            val byId = item.id in op.textItemIds
                            val byText = needle != null && item.text.lowercase().contains(needle)
                            val bySource = op.sourceId != null && item.lockedToSource == op.sourceId
                            byId || (needle != null && byText) || (op.sourceId != null && bySource)
                        },
                    )
                },
            ),
        )
    }

    private fun addOverlay(timeline: Timeline, op: AddImageOverlay): ReduceResult {
        if (op.imageUri.isBlank()) return failure(op, "Overlay image URI is blank")
        if (op.durationMs <= 0L) return failure(op, "Overlay duration must be positive")
        val item = OverlayItem(
            id = OverlayItemId(derivedId(op, "overlay")),
            imageUri = op.imageUri,
            range = TimeRange(op.timelineStartMs, op.timelineStartMs + op.durationMs),
            position = op.position,
            scale = op.scale,
            opacity = op.opacity,
            label = op.label,
        )
        val tracks = timeline.overlayTracks.ifEmpty {
            listOf(OverlayTrack(id = TrackId(derivedId(op, "overlay-track"))))
        }
        return ReduceResult.Success(
            timeline.copy(
                overlayTracks = tracks.mapIndexed { index, track ->
                    if (index == 0) track.copy(items = track.items + item) else track
                },
            ),
        )
    }

    private fun freeze(
        timeline: Timeline,
        op: FreezeFrame,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        val metadata = sources[op.sourceId] ?: return failure(op, "Unknown source ${op.sourceId.value}")
        if (op.atSourceMs <= 0L || op.atSourceMs >= metadata.durationMs) {
            return failure(op, "Freeze point ${op.atSourceMs}ms is outside the source")
        }
        if (op.holdMs <= 0L) return failure(op, "Freeze hold must be positive")

        val track = timeline.videoTracks.firstOrNull { t ->
            t.clips.any { it.sourceId == op.sourceId && it.sourceRange.contains(op.atSourceMs) }
        } ?: return failure(op, "No clip shows source ${op.sourceId.value} at ${op.atSourceMs}ms")

        val clip = track.clips.first { it.sourceId == op.sourceId && it.sourceRange.contains(op.atSourceMs) }
        val splitPoint = if (op.atSourceMs > clip.sourceRange.startMs) {
            RangeMath.splitAt(clip.sourceRange, listOf(op.atSourceMs))
        } else {
            listOf(clip.sourceRange)
        }
        val pieces = reslice(clip, splitPoint)
        val frozenIndex = pieces.indexOfLast { it.sourceRange.contains(op.atSourceMs) }.coerceAtLeast(0)
        val frozen = pieces[frozenIndex]
        val withFreeze = frozen.copy(
            freeze = FreezeSpec(atSourceOffsetMs = 0L, holdMs = op.holdMs, overlayText = null),
        )
        val newPieces = pieces.toMutableList().also { it[frozenIndex] = withFreeze }
        val packed = pack(track.copy(clips = replaceIn(track.clips, clip.id, newPieces)))

        // "Freeze when X and add 'RIP'" also creates a real text item spanning the hold.
        val freezeStartMs = packed.clips.first { it.id == frozen.id }.timelineStartMs
        val resultTimeline = timeline.withTrack(track.id, packed)
        // Copied to a local so the nullability is known from this module's type
        // information rather than the public API of `:core:model`.
        val overlayText = op.overlayText
        val textTimeline = if (overlayText.isNullOrBlank()) resultTimeline else {
            val item = TextItem(
                id = TextItemId(derivedId(op, "freeze-label")),
                range = TimeRange(freezeStartMs, freezeStartMs + op.holdMs),
                text = overlayText,
                style = com.sekhar.helium.core.model.TextStyle.TITLE,
            )
            resultTimeline.withTextTrack(null) { it.copy(items = it.items + item) }
        }
        return ReduceResult.Success(textTimeline)
    }

    private fun addTransition(timeline: Timeline, op: AddTransition): ReduceResult {
        val track = trackContaining(timeline, op.clipId)
            ?: return failure(op, "No clip with id ${op.clipId.value}")
        val clip = track.clips.first { it.id == op.clipId }
        if (op.spec.durationMs <= 0L) return failure(op, "Transition duration must be positive")
        if (op.spec.durationMs > clip.timelineDurationMs) {
            return failure(op, "Transition is longer than the clip it belongs to")
        }
        val range = when (op.boundary) {
            TransitionBoundary.START -> TimeRange(0L, op.spec.durationMs)
            TransitionBoundary.END -> TimeRange(clip.timelineDurationMs - op.spec.durationMs, clip.timelineDurationMs)
        }
        val effect = TransitionEffect(derivedId(op, "transition"), range, op.boundary, op.spec)
        val clips = track.clips.map { if (it.id == clip.id) it.copy(effects = it.effects + effect) else it }
        return ReduceResult.Success(timeline.withTrack(track.id, pack(track.copy(clips = clips))))
    }

    // =============================================================================
    // Music
    // =============================================================================

    private fun addMusic(
        timeline: Timeline,
        op: AddMusic,
        sources: Map<SourceId, VideoMetadata>,
    ): ReduceResult {
        val metadata = sources[op.musicSourceId]
            ?: return failure(op, "Unknown music source ${op.musicSourceId.value}")
        val duration = (op.durationMs ?: metadata.durationMs).coerceAtMost(metadata.durationMs)
        if (duration <= 0L) return failure(op, "Music duration must be positive")

        val clip = AudioClip(
            id = ClipId(derivedId(op, "music")),
            sourceId = op.musicSourceId,
            sourceRange = TimeRange(0L, duration),
            timelineStartMs = op.timelineStartMs,
            volume = op.volume,
            fadeInMs = op.fadeInMs,
            fadeOutMs = op.fadeOutMs,
            duckUnderSpeech = op.duckUnderSpeech,
            label = "Music",
        )
        val musicTrackId = timeline.audioTracks.firstOrNull { it.isMusic }?.id
        val tracks = if (musicTrackId != null) {
            timeline.audioTracks.map { if (it.id == musicTrackId) it.copy(clips = it.clips + clip) else it }
        } else {
            timeline.audioTracks + AudioTrack(
                id = TrackId(derivedId(op, "music-track")),
                name = "Music",
                clips = listOf(clip),
                isMusic = true,
            )
        }
        return ReduceResult.Success(timeline.copy(audioTracks = tracks))
    }

    private fun duckMusic(timeline: Timeline, op: DuckMusic): ReduceResult {
        var found = false
        val tracks = timeline.audioTracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == op.audioClipId) {
                    found = true
                    clip.copy(duckUnderSpeech = op.enabled, duckAmountDb = op.duckAmountDb)
                } else {
                    clip
                }
            })
        }
        if (!found) return failure(op, "No audio clip with id ${op.audioClipId.value}")
        return ReduceResult.Success(timeline.copy(audioTracks = tracks))
    }

    private fun fadeAudio(timeline: Timeline, op: FadeAudio): ReduceResult {
        if (op.fadeInMs < 0L || op.fadeOutMs < 0L) return failure(op, "Fades cannot be negative")
        var found = false
        val tracks = timeline.audioTracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == op.audioClipId) {
                    found = true
                    clip.copy(fadeInMs = op.fadeInMs, fadeOutMs = op.fadeOutMs)
                } else {
                    clip
                }
            })
        }
        if (!found) return failure(op, "No audio clip with id ${op.audioClipId.value}")
        return ReduceResult.Success(timeline.copy(audioTracks = tracks))
    }

    // =============================================================================
    // Shared helpers
    // =============================================================================

    /**
     * Splits every clip showing [sourceRange] of [sourceId] at the range edges,
     * applies [transform] to the centre piece and re-packs the track.
     *
     * This is the shared implementation behind speed, volume, zoom, blur and
     * colour operations, which all need to affect exactly part of a clip.
     */
    private fun mapClipsInRange(
        timeline: Timeline,
        sourceId: SourceId,
        sourceRange: TimeRange,
        op: EditOperation,
        transform: (Clip, TimeRange) -> Clip,
    ): ReduceResult {
        if (sourceRange.isEmpty) return failure(op, "Requested range is empty")
        var touched = 0

        val tracks = timeline.videoTracks.map { track ->
            val updated = track.clips.flatMap { clip ->
                if (clip.sourceId != sourceId) return@flatMap listOf(clip)
                val overlap = clip.sourceRange.intersect(sourceRange) ?: return@flatMap listOf(clip)

                val pieces = reslice(clip, RangeMath.splitAt(clip.sourceRange, listOf(overlap.startMs, overlap.endMsExclusive)))
                pieces.map { piece ->
                    if (piece.sourceRange.startMs >= overlap.startMs && piece.sourceRange.endMsExclusive <= overlap.endMsExclusive) {
                        touched++
                        transform(piece, piece.sourceRange)
                    } else {
                        piece
                    }
                }
            }
            pack(track.copy(clips = updated))
        }

        if (touched == 0) {
            return failure(op, "No clip shows source ${sourceId.value} between ${sourceRange.startMs}ms and ${sourceRange.endMsExclusive}ms")
        }
        return ReduceResult.Success(timeline.copy(videoTracks = tracks))
    }

    /**
     * Rebuilds a clip as one or more clips covering exactly [keepRanges] of the
     * same source, redistributing effects and freeze state.
     */
    private fun reslice(clip: Clip, keepRanges: List<TimeRange>): List<Clip> {
        if (keepRanges.isEmpty()) return emptyList()
        if (keepRanges.size == 1 && keepRanges.first() == clip.sourceRange) return listOf(clip)

        val sourceStart = clip.sourceRange.startMs
        var relativeCursor = 0L
        return keepRanges.mapIndexed { index, range ->
            val offsetInSource = range.startMs - sourceStart
            val relativeStart = clip.sourceOffsetToTimelineOffset(offsetInSource)
            val relativeEnd = clip.sourceOffsetToTimelineOffset(range.endMsExclusive - sourceStart)
            val baseDuration = (relativeEnd - relativeStart).coerceAtLeast(1L)
            val freezeInRange = clip.freeze?.takeIf {
                it.atSourceOffsetMs >= offsetInSource && it.atSourceOffsetMs < offsetInSource + range.durationMs
            }?.let { it.copy(atSourceOffsetMs = it.atSourceOffsetMs - offsetInSource) }
            val duration = baseDuration + (freezeInRange?.holdMs ?: 0L)

            val effects = clip.effects.mapNotNull { effect ->
                val overlap = effect.range.intersect(TimeRange(relativeStart, relativeEnd)) ?: return@mapNotNull null
                effect.withRange(overlap.shifted(-relativeStart))
            }

            val piece = clip.copy(
                id = if (index == 0) clip.id else ClipId("${clip.id.value}#$index"),
                sourceRange = range,
                timelineStartMs = relativeCursor,
                freeze = freezeInRange,
                effects = effects,
            )
            relativeCursor += duration
            piece
        }
    }

    /** Audio clips only consume media, so re-slicing is a simple range swap. */
    private fun resliceAudio(clip: AudioClip, keepRanges: List<TimeRange>): List<AudioClip> =
        keepRanges.mapIndexed { index, range ->
            clip.copy(
                id = if (index == 0) clip.id else ClipId("${clip.id.value}#a$index"),
                sourceRange = range,
            )
        }

    /** Lays clips out back-to-back starting at the track's original start. */
    private fun pack(track: VideoTrack): VideoTrack {
        var cursor = track.clips.firstOrNull()?.timelineStartMs ?: 0L
        val packed = track.clips.map { clip ->
            val placed = clip.copy(timelineStartMs = cursor)
            cursor += placed.timelineDurationMs
            placed
        }
        return track.copy(clips = packed)
    }

    private fun Effect.withRange(newRange: TimeRange): Effect = when (this) {
        is ZoomEffect -> copy(range = newRange)
        is BlurEffect -> copy(range = newRange)
        is TransitionEffect -> copy(range = newRange)
        is ColorAdjustEffect -> copy(range = newRange)
    }

    private fun trackContaining(timeline: Timeline, clipId: ClipId): VideoTrack? =
        timeline.videoTracks.firstOrNull { track -> track.clips.any { it.id == clipId } }

    private fun replaceIn(clips: List<Clip>, target: ClipId, replacements: List<Clip>): List<Clip> =
        clips.flatMap { if (it.id == target) replacements else listOf(it) }

    private fun Timeline.withTrack(trackId: TrackId, track: VideoTrack): Timeline =
        copy(videoTracks = videoTracks.map { if (it.id == trackId) track else it })

    /** Appends to the requested text track, or the first one, creating one if needed. */
    private fun Timeline.withTextTrack(
        trackId: TrackId?,
        transform: (TextTrack) -> TextTrack,
    ): Timeline {
        if (textTracks.isEmpty()) {
            return copy(textTracks = listOf(transform(TextTrack(id = DEFAULT_TEXT_TRACK_ID))))
        }
        val resolved = trackId ?: textTracks.first().id
        var applied = false
        val updated = textTracks.map { track ->
            if (track.id == resolved) {
                applied = true
                transform(track)
            } else {
                track
            }
        }
        return if (applied) copy(textTracks = updated) else copy(textTracks = listOf(transform(textTracks.first())) + textTracks.drop(1))
    }

    private fun scaleDuration(durationMs: Long, speed: Float): Long =
        if (speed == 1f) durationMs else (durationMs / speed).roundToLong()

    /**
     * Stable id for an entity created while reducing [op].
     *
     * Deterministic on purpose: `suffix` distinguishes the entities one operation
     * produces, and the operation id never changes once it is in the history.
     */
    private fun derivedId(op: EditOperation, suffix: String): String = "${op.operationId}#$suffix"

    private fun failure(op: EditOperation, message: String): ReduceResult.Failure =
        ReduceResult.Failure(op, listOf(message))

    private companion object {
        /** Stable id for the text track Helium creates when none exists yet. */
        val DEFAULT_TEXT_TRACK_ID = TrackId("text-main")
    }
}
