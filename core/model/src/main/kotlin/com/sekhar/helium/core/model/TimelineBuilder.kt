package com.sekhar.helium.core.model

import com.sekhar.helium.core.common.IdGenerator

/**
 * Builds the **base timeline** for a project: the timeline before any edit has
 * been applied.
 *
 * The base is deliberately trivial — one video clip per usable source laid end to
 * end — because every later state is derived from it by replaying operations.
 * Keeping it trivial is what makes the whole history auditable and undo exact.
 *
 * ### One lane per audio source
 *
 * A source's original audio is carried by its own [Clip] (`volume`, `muted`,
 * `hasAudio`), *not* by a parallel entry on an audio track. Modelling it twice
 * would mean two owners for the same samples, and would let the timeline length —
 * and therefore the export — be driven by stale audio that no longer matches the
 * picture (a trimmed clip would keep its full-length audio). Audio tracks exist
 * only for audio that is *added*: a music bed, marked with
 * [AudioTrack.isMusic].
 */
fun buildBaseTimeline(
    sources: List<SourceMedia>,
    ids: IdGenerator,
): Timeline {
    val usable = sources.filter { it.isUsable && it.metadata.durationMs > 0L }
    if (usable.isEmpty()) return Timeline()

    var cursor = 0L
    val videoClips = usable.map { source ->
        val clip = Clip(
            id = ClipId(ids.newId()),
            sourceId = source.id,
            sourceRange = TimeRange(0L, source.metadata.durationMs),
            timelineStartMs = cursor,
        )
        cursor += clip.timelineDurationMs
        clip
    }

    return Timeline(
        videoTracks = listOf(VideoTrack(id = TrackId(ids.newId()), name = "Main", clips = videoClips)),
    )
}
