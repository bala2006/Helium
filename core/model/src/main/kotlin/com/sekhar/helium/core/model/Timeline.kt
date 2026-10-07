package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToLong

/** Anything that occupies an interval on the timeline. */
interface TimelineEntry {
    val timelineStartMs: Long
    val timelineDurationMs: Long
    val timelineEndMs: Long get() = timelineStartMs + timelineDurationMs
}

/** a video clip on a video track. */
@Serializable
data class Clip(
    val id: ClipId,
    val sourceId: SourceId,
    /** Region of the source media that this clip plays. */
    val sourceRange: TimeRange,
    override val timelineStartMs: Long = 0L,
    /** Playback speed; `2.0` plays twice as fast and halves the duration. */
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** Optional freeze/hold inserted inside this clip. */
    val freeze: FreezeSpec? = null,
    val crop: CropRect = CropRect.FULL,
    val effects: List<Effect> = emptyList(),
) : TimelineEntry {

    init {
        require(speed > 0f) { "speed must be > 0 but was $speed" }
    }

    /** Duration of media consumed from the source. */
    val sourceDurationMs: Long get() = sourceRange.durationMs

    /** Duration of media played back at [speed], before any freeze hold. */
    val scaledDurationMs: Long
        get() = if (speed == 1f) sourceDurationMs else (sourceDurationMs / speed).roundToLong()

    /** Total time this clip occupies on the timeline, including any freeze hold. */
    override val timelineDurationMs: Long
        get() = scaledDurationMs + (freeze?.holdMs ?: 0L)

    /** Maps an offset inside the clip's timeline span back to a source offset. */
    fun timelineOffsetToSourceOffset(timelineOffsetMs: Long): Long {
        val scaledSpan = scaledDurationMs
        val clamped = timelineOffsetMs.coerceIn(0L, max(0L, timelineDurationMs))
        return if (clamped >= scaledSpan) {
            sourceDurationMs
        } else {
            (clamped * speed).roundToLong().coerceIn(0L, sourceDurationMs)
        }
    }

    /**
     * Maps an offset inside [sourceRange] to a clip-relative timeline offset.
     *
     * Offsets after an embedded freeze are pushed later by the hold duration,
     * which is what keeps the timeline length consistent with the freeze.
     */
    fun sourceOffsetToTimelineOffset(sourceOffsetMs: Long): Long {
        val clamped = sourceOffsetMs.coerceIn(0L, sourceDurationMs)
        val scaled = (clamped / speed).roundToLong()
        val freeze = this.freeze ?: return scaled
        return if (clamped > freeze.atSourceOffsetMs) scaled + freeze.holdMs else scaled
    }
}

/**
 * A freeze frame ("hold this moment") embedded in a clip.
 *
 * [atSourceOffsetMs] is an offset inside the clip's [Clip.sourceRange]; the frame
 * at that offset is held for [holdMs].
 */
@Serializable
data class FreezeSpec(
    val atSourceOffsetMs: Long,
    val holdMs: Long = 1500L,
    val overlayText: String? = null,
)

/** An audio clip: either the original audio of a source, or an added music bed. */
@Serializable
data class AudioClip(
    val id: ClipId,
    val sourceId: SourceId,
    val sourceRange: TimeRange,
    override val timelineStartMs: Long = 0L,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    /** Music beds duck automatically while speech is detected. */
    val duckUnderSpeech: Boolean = false,
    val duckAmountDb: Float = -12f,
    val label: String? = null,
) : TimelineEntry {

    init {
        require(speed > 0f) { "speed must be > 0 but was $speed" }
    }

    override val timelineDurationMs: Long
        get() = if (speed == 1f) sourceRange.durationMs else (sourceRange.durationMs / speed).roundToLong()
}

/** A text or caption item on a text track. */
@Serializable
data class TextItem(
    val id: TextItemId,
    val range: TimeRange,
    val text: String,
    val style: TextStyle = TextStyle(),
    /** Word-level timings, used for karaoke-style active word highlighting. */
    val words: List<WordTiming> = emptyList(),
    val isCaption: Boolean = false,
    val lockedToSource: SourceId? = null,
) : TimelineEntry {
    override val timelineStartMs: Long get() = range.startMs
    override val timelineDurationMs: Long get() = range.durationMs

    /** Index of the word active at [timelineMs], or `-1` when none is active. */
    fun activeWordIndex(timelineMs: Long): Int =
        words.indexOfFirst { it.range.contains(timelineMs) }
}

/** A still image or sticker composited on top of the video. */
@Serializable
data class OverlayItem(
    val id: OverlayItemId,
    val imageUri: String,
    val range: TimeRange,
    val position: NormalizedPoint = NormalizedPoint(0.5f, 0.5f),
    val scale: Float = 0.4f,
    val opacity: Float = 1f,
    val rotationDegrees: Float = 0f,
    /** Optional text used by features such as "freeze and add 'RIP'". */
    val label: String? = null,
) : TimelineEntry {
    override val timelineStartMs: Long get() = range.startMs
    override val timelineDurationMs: Long get() = range.durationMs
}

/** Video track. Clips are stored ordered by [Clip.timelineStartMs]. */
@Serializable
data class VideoTrack(
    val id: TrackId,
    val name: String = "Video",
    val clips: List<Clip> = emptyList(),
    val hidden: Boolean = false,
) {
    val durationMs: Long get() = clips.maxOfOrNull { it.timelineEndMs } ?: 0L

    fun clipAt(timelineMs: Long): Clip? = clips.firstOrNull {
        timelineMs >= it.timelineStartMs && timelineMs < it.timelineEndMs
    }

    /** Returns this track with clips ordered by timeline position. */
    fun normalized(): VideoTrack =
        if (clips.zipWithNext().all { (a, b) -> a.timelineStartMs <= b.timelineStartMs }) this
        else copy(clips = clips.sortedBy { it.timelineStartMs })
}

/** Audio track. */
@Serializable
data class AudioTrack(
    val id: TrackId,
    val name: String = "Audio",
    val clips: List<AudioClip> = emptyList(),
    val muted: Boolean = false,
    /** Marks tracks that hold added music rather than original media audio. */
    val isMusic: Boolean = false,
) {
    val durationMs: Long get() = clips.maxOfOrNull { it.timelineEndMs } ?: 0L

    fun normalized(): AudioTrack =
        if (clips.zipWithNext().all { (a, b) -> a.timelineStartMs <= b.timelineStartMs }) this
        else copy(clips = clips.sortedBy { it.timelineStartMs })
}

/** Text / caption track. */
@Serializable
data class TextTrack(
    val id: TrackId,
    val name: String = "Text",
    val items: List<TextItem> = emptyList(),
    val hidden: Boolean = false,
) {
    val durationMs: Long get() = items.maxOfOrNull { it.timelineEndMs } ?: 0L

    fun normalized(): TextTrack =
        if (items.zipWithNext().all { (a, b) -> a.timelineStartMs <= b.timelineStartMs }) this
        else copy(items = items.sortedBy { it.timelineStartMs })
}

/** Overlay track (images, stickers, badges). */
@Serializable
data class OverlayTrack(
    val id: TrackId,
    val name: String = "Overlays",
    val items: List<OverlayItem> = emptyList(),
) {
    val durationMs: Long get() = items.maxOfOrNull { it.timelineEndMs } ?: 0L

    fun normalized(): OverlayTrack =
        if (items.zipWithNext().all { (a, b) -> a.timelineStartMs <= b.timelineStartMs }) this
        else copy(items = items.sortedBy { it.timelineStartMs })
}

/**
 * The complete, non-destructive edit decision list.
 *
 * Nothing here references a mutated source file: the timeline is a pure
 * description that the preview and export engines interpret.
 */
@Serializable
data class Timeline(
    val videoTracks: List<VideoTrack> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val textTracks: List<TextTrack> = emptyList(),
    val overlayTracks: List<OverlayTrack> = emptyList(),
) {

    val isEmpty: Boolean
        get() = videoTracks.all { it.clips.isEmpty() } &&
            audioTracks.all { it.clips.isEmpty() } &&
            textTracks.all { it.items.isEmpty() } &&
            overlayTracks.all { it.items.isEmpty() }

    /** Total timeline duration, including trailing audio and text. */
    val durationMs: Long
        get() {
            var max = 0L
            videoTracks.forEach { max = maxOf(max, it.durationMs) }
            audioTracks.forEach { max = maxOf(max, it.durationMs) }
            textTracks.forEach { max = maxOf(max, it.durationMs) }
            overlayTracks.forEach { max = maxOf(max, it.durationMs) }
            return max
        }

    val primaryVideoTrack: VideoTrack? get() = videoTracks.firstOrNull()

    fun allClips(): List<Clip> = videoTracks.flatMap { it.clips }

    fun allAudioClips(): List<AudioClip> = audioTracks.flatMap { it.clips }

    fun allTextItems(): List<TextItem> = textTracks.flatMap { it.items }

    fun allOverlayItems(): List<OverlayItem> = overlayTracks.flatMap { it.items }

    fun clip(clipId: ClipId): Clip? = allClips().firstOrNull { it.id == clipId }

    fun audioClip(clipId: ClipId): AudioClip? = allAudioClips().firstOrNull { it.id == clipId }

    fun textItem(id: TextItemId): TextItem? = allTextItems().firstOrNull { it.id == id }

    /** Every clip that displays any part of [sourceRange] of [sourceId]. */
    fun clipsTouching(sourceId: SourceId, sourceRange: TimeRange): List<Clip> =
        allClips().filter {
            it.sourceId == sourceId && it.sourceRange.overlaps(sourceRange)
        }

    /** Clips still referencing [sourceId]; used before removing a source. */
    fun referencesSource(sourceId: SourceId): Boolean =
        allClips().any { it.sourceId == sourceId } || allAudioClips().any { it.sourceId == sourceId }

    fun videoTrack(trackId: TrackId): VideoTrack? = videoTracks.firstOrNull { it.id == trackId }

    fun audioTrack(trackId: TrackId): AudioTrack? = audioTracks.firstOrNull { it.id == trackId }

    fun textTrack(trackId: TrackId): TextTrack? = textTracks.firstOrNull { it.id == trackId }

    fun overlayTrack(trackId: TrackId): OverlayTrack? = overlayTracks.firstOrNull { it.id == trackId }

    /** Sorts every track's contents and returns the canonical representation. */
    fun normalized(): Timeline = copy(
        videoTracks = videoTracks.map { it.normalized() },
        audioTracks = audioTracks.map { it.normalized() },
        textTracks = textTracks.map { it.normalized() },
        overlayTracks = overlayTracks.map { it.normalized() },
    )
}
