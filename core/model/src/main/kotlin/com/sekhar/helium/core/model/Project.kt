package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/**
 * The aggregate root of a Helium project.
 *
 * A project is a pure value object: every edit produces a new [Project]. It is
 * serialised as a single JSON document so that persisted state, the edit history
 * and the timeline can never drift out of sync, and so older documents keep
 * loading when new fields are added.
 */
@Serializable
data class Project(
    val id: ProjectId,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val aspectRatio: AspectRatio = AspectRatio.ORIGINAL,
    val sources: List<SourceMedia> = emptyList(),
    val timeline: Timeline = Timeline(),
    val history: EditHistory = EditHistory(),
    val exportSettings: ExportSettings = ExportSettings(),
    /** Monotonic counter bumped on every successful edit; used for autosave. */
    val revision: Long = 0L,
) {
    val durationMs: Long get() = timeline.durationMs

    val isEmpty: Boolean get() = timeline.isEmpty

    fun source(sourceId: SourceId): SourceMedia? = sources.firstOrNull { it.id == sourceId }

    fun addSource(source: SourceMedia): Project = copy(sources = sources + source)

    /**
     * Removes a source and every timeline entry that referenced it.
     *
     * This only ever drops *derived* data. The user's original file is untouched
     * and remains on the device — deleting it requires an explicit user action.
     */
    fun removeSource(sourceId: SourceId): Project {
        val trimmedTimeline = Timeline(
            videoTracks = timeline.videoTracks.map { track ->
                track.copy(clips = track.clips.filterNot { it.sourceId == sourceId })
            },
            audioTracks = timeline.audioTracks.map { track ->
                if (track.isMusic) track
                else track.copy(clips = track.clips.filterNot { it.sourceId == sourceId })
            },
            textTracks = timeline.textTracks.map { track ->
                track.copy(items = track.items.filterNot { it.lockedToSource == sourceId })
            },
            overlayTracks = timeline.overlayTracks,
        )
        return copy(
            sources = sources.filterNot { it.id == sourceId },
            timeline = trimmedTimeline,
        )
    }

    /** A short, human-readable description of the current edit state. */
    fun describeState(): String = buildString {
        append("${sources.size} source")
        if (sources.size != 1) append('s')
        append(", ")
        append("${timeline.allClips().size} clip")
        if (timeline.allClips().size != 1) append('s')
        append(", ")
        append(formatDurationMs(durationMs))
    }

    companion object {
        const val MAX_NAME_LENGTH = 80

        fun newProject(
            id: ProjectId,
            name: String,
            nowEpochMs: Long,
        ): Project = Project(
            id = id,
            name = name.take(MAX_NAME_LENGTH).ifBlank { "Untitled" },
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
    }
}

/** Formats milliseconds as `m:ss.d`, the format used in the editor UI. */
fun formatDurationMs(durationMs: Long): String {
    val safe = if (durationMs < 0) 0 else durationMs
    val totalSeconds = safe / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (safe % 1000) / 100
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}

/** Formats milliseconds as a precise `mm:ss.SSS` timestamp for tool output. */
fun formatTimestampMs(timestampMs: Long): String {
    val safe = if (timestampMs < 0) 0 else timestampMs
    val totalSeconds = safe / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = safe % 1000
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}

/** Summary row used by the projects list, cheap to query from the database. */
@Serializable
data class ProjectSummary(
    val id: ProjectId,
    val name: String,
    val updatedAtEpochMs: Long,
    val durationMs: Long,
    val sourceCount: Int,
    val clipCount: Int,
    val thumbnailPath: String? = null,
)
