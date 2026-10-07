package com.sekhar.helium.ui.editor

import com.sekhar.helium.core.model.Clip
import com.sekhar.helium.core.model.ClipId
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.Timeline

/**
 * One playable stretch of the *edited* timeline.
 *
 * The preview is a playlist of these, one per video clip, so what the user sees
 * while editing is the real edit decision list rather than the source videos.
 * [sourceStartMs] / [sourceEndMsExclusive] always address the original media.
 */
data class PreviewSegment(
    val clipId: ClipId,
    val sourceId: SourceId,
    val trackIndex: Int,
    val segmentIndex: Int,
    val timelineStartMs: Long,
    val timelineEndMs: Long,
    val sourceStartMs: Long,
    val sourceEndMsExclusive: Long,
    val speed: Float,
    val volume: Float,
    val muted: Boolean,
) {
    val timelineDurationMs: Long get() = timelineEndMs - timelineStartMs

    fun contains(timelineMs: Long): Boolean =
        timelineMs >= timelineStartMs && timelineMs < timelineEndMs

    /** Maps a timeline position to an offset inside [sourceStartMs]..[sourceEndMsExclusive]. */
    fun toSourceOffsetMs(timelineMs: Long): Long {
        val offset = (timelineMs - timelineStartMs).coerceIn(0L, timelineDurationMs)
        return (sourceStartMs + (offset * speed).toLong())
            .coerceIn(sourceStartMs, sourceEndMsExclusive)
    }

    /** Progress `0..1` through this segment at [timelineMs]. */
    fun progressAt(timelineMs: Long): Float {
        val duration = timelineDurationMs
        if (duration <= 0L) return 0f
        return ((timelineMs - timelineStartMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
}

/** Where a timeline position lands in the preview playlist. */
data class PreviewLocation(
    val segmentIndex: Int,
    val segment: PreviewSegment,
    val sourceOffsetMs: Long,
) {
    val windowMs: Long get() = segment.timelineStartMs
}

/**
 * Builds the preview playlist from a [Timeline].
 *
 * Deliberately a pure function of the timeline: the same mapping answers "what
 * plays now?", "where do I seek to?", and "what does the playhead point at?"
 * during an AI edit, so preview, export and the AI context can never disagree.
 */
object TimelinePreviewModel {

    /**
     * Flattens the timeline into play order.
     *
     * Tracks are laid end to end in video-track order, matching how Helium's
     * reducer keeps the primary video track packed and contiguous.
     */
    fun segments(timeline: Timeline): List<PreviewSegment> {
        val segments = mutableListOf<PreviewSegment>()
        var cursor = 0L
        var index = 0
        timeline.videoTracks.forEachIndexed { trackIndex, track ->
            track.clips
                .sortedBy { it.timelineStartMs }
                .forEach { clip ->
                    val duration = clip.timelineDurationMs
                    if (duration <= 0L) return@forEach
                    segments += PreviewSegment(
                        clipId = clip.id,
                        sourceId = clip.sourceId,
                        trackIndex = trackIndex,
                        segmentIndex = index,
                        timelineStartMs = cursor,
                        timelineEndMs = cursor + duration,
                        sourceStartMs = clip.sourceRange.startMs,
                        sourceEndMsExclusive = clip.sourceRange.endMsExclusive,
                        speed = clip.speed,
                        volume = clip.volume,
                        muted = clip.muted,
                    )
                    cursor += duration
                    index++
                }
        }
        return segments
    }

    /** Playlist position for [timelineMs], or `null` when the timeline is empty. */
    fun locate(segments: List<PreviewSegment>, timelineMs: Long): PreviewLocation? {
        if (segments.isEmpty()) return null
        val safe = timelineMs.coerceAtLeast(0L)
        val segment = segments.firstOrNull { it.contains(safe) } ?: segments.last()
        return PreviewLocation(
            segmentIndex = segment.segmentIndex,
            segment = segment,
            sourceOffsetMs = segment.toSourceOffsetMs(safe),
        )
    }

    /** Clip that is visible at [timelineMs]. */
    fun clipAt(timeline: Timeline, timelineMs: Long): Clip? =
        timeline.videoTracks
            .flatMap { it.clips }
            .filter { timelineMs >= it.timelineStartMs && timelineMs < it.timelineEndMs }
            .minByOrNull { it.timelineStartMs }

    /**
     * Audio amplitude envelope (`0..1`) for the timeline, sampled into
     * [buckets] buckets, derived from the locally analysed audio RMS curve.
     *
     * Returns an empty list when the source has not been analysed yet, which the
     * UI renders as "waveform pending" rather than inventing a shape.
     */
    fun waveform(
        timeline: Timeline,
        rmsBySource: Map<String, List<Pair<Long, Float>>>,
        buckets: Int = 160,
    ): List<Float> {
        val segments = segments(timeline)
        val total = segments.lastOrNull()?.timelineEndMs ?: return emptyList()
        if (total <= 0L || buckets <= 0) return emptyList()
        val envelope = FloatArray(buckets)
        var filled = false
        segments.forEach { segment ->
            val samples = rmsBySource[segment.sourceId.value] ?: return@forEach
            if (samples.isEmpty()) return@forEach
            val bucketFrom = ((segment.timelineStartMs.toDouble() / total) * buckets).toInt()
                .coerceIn(0, buckets - 1)
            val bucketTo = ((segment.timelineEndMs.toDouble() / total) * buckets).toInt()
                .coerceIn(bucketFrom, buckets - 1)
            for (bucket in bucketFrom..bucketTo) {
                val ratio = if (bucketTo == bucketFrom) {
                    0f
                } else {
                    (bucket - bucketFrom).toFloat() / (bucketTo - bucketFrom).toFloat()
                }
                val sourceMs = (segment.sourceStartMs +
                    ((segment.sourceEndMsExclusive - segment.sourceStartMs) * ratio)).toLong()
                val sample = samples.minByOrNull { kotlin.math.abs(it.first - sourceMs) } ?: continue
                envelope[bucket] = maxOf(envelope[bucket], sample.second.coerceIn(0f, 1f))
                filled = true
            }
        }
        if (!filled) return emptyList()
        // Smooth so the drawn envelope reads as a waveform, not a bar chart.
        return List(buckets) { index ->
            val previous = envelope[(index - 1).coerceAtLeast(0)]
            val next = envelope[(index + 1).coerceAtMost(buckets - 1)]
            (previous + envelope[index] * 2f + next) / 4f
        }
    }
}
