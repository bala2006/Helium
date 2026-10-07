package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.common.SequentialIdGenerator
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.ProjectId
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SourceMedia
import com.sekhar.helium.core.model.VideoMetadata

/** Deterministic fixtures: fixed ids, no clock, no randomness. */
object Fixtures {

    fun metadata(
        durationMs: Long = 60_000L,
        width: Int = 1080,
        height: Int = 1920,
        hasAudio: Boolean = true,
    ): VideoMetadata = VideoMetadata(
        durationMs = durationMs,
        width = width,
        height = height,
        rotationDegrees = 0,
        frameRate = 30f,
        hasAudio = hasAudio,
        videoMimeType = "video/avc",
        audioMimeType = if (hasAudio) "audio/mp4a-latm" else null,
    )

    fun source(
        id: String = "src-1",
        durationMs: Long = 60_000L,
        hasAudio: Boolean = true,
    ): SourceMedia = SourceMedia(
        id = SourceId(id),
        uri = "content://media/video/$id",
        displayName = "clip-$id.mp4",
        fingerprint = "fp-$id",
        metadata = metadata(durationMs = durationMs, hasAudio = hasAudio),
        sizeBytes = 12_000_000L,
        importedAtEpochMs = 0L,
    )

    /** A project whose timeline is the pristine base timeline. */
    fun project(sources: List<SourceMedia> = listOf(source())): Pair<Project, IdGenerator> {
        val ids = SequentialIdGenerator("t")
        var project = Project(
            id = ProjectId("project-1"),
            name = "Test project",
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )
        sources.forEach { project = project.withSource(it, ids) }
        project = project.copy(timeline = project.baseTimeline)
        return project to ids
    }

    fun engine(ids: IdGenerator): EditEngine =
        EditEngine(ids = ids, time = { 1_000L })
}
