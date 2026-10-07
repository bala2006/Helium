package com.sekhar.helium.core.database

import com.sekhar.helium.core.common.SequentialIdGenerator
import com.sekhar.helium.core.model.AnalysisStage
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.ProjectId
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SourceMedia
import com.sekhar.helium.core.model.VideoMetadata
import com.sekhar.helium.core.model.buildBaseTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectDocumentsTest {

    private fun source(id: String = "src-1"): SourceMedia = SourceMedia(
        id = SourceId(id),
        uri = "content://media/video/$id",
        displayName = "clip.mp4",
        fingerprint = "fp-$id",
        metadata = VideoMetadata(durationMs = 30_000L, width = 1080, height = 1920, hasAudio = true),
        sizeBytes = 1_000L,
        importedAtEpochMs = 0L,
    )

    private fun project(durationMs: Long = 30_000L, sourceId: String = "src-1"): Project {
        val ids = SequentialIdGenerator("s")
        val media = source(sourceId)
        return Project(
            id = ProjectId("p-1"),
            name = "Reel",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            sources = listOf(media.copy(metadata = media.metadata.copy(durationMs = durationMs))),
            baseTimeline = buildBaseTimeline(listOf(media), ids),
        ).let { it.copy(timeline = it.baseTimeline) }
    }

    @Test
    fun roundTripsThroughTheDocumentFormat() {
        val project = project()
        val encoded = ProjectDocuments.encode(project)
        val decoded = assertNotNull(ProjectDocuments.decodeOrNull(encoded))
        assertEquals(project, decoded)
    }

    @Test
    fun corruptDocumentsDecodeToNullInsteadOfThrowing() {
        assertNull(ProjectDocuments.decodeOrNull("{not json"))
        assertNull(ProjectDocuments.decodeOrNull("[]"))
    }

    @Test
    fun repairRebuildsAMissingBaseTimeline() {
        val ids = SequentialIdGenerator("s")
        val media = source()
        val broken = Project(
            id = ProjectId("p-1"),
            name = "Old document",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            sources = listOf(media),
            baseTimeline = com.sekhar.helium.core.model.Timeline(),
        )
        assertTrue(broken.baseTimeline.isEmpty)

        val repaired = ProjectDocuments.repair(broken, ids)
        assertTrue(!repaired.baseTimeline.isEmpty)
        assertEquals(30_000L, repaired.baseTimeline.durationMs)
    }

    @Test
    fun repairLeavesAHealthyProjectUntouched() {
        val ids = SequentialIdGenerator("s")
        val project = project()
        assertEquals(project, ProjectDocuments.repair(project, ids))
    }

    @Test
    fun entityCarriesTheFlatListColumns() {
        val project = project()
        val entity = ProjectDocuments.toEntity(project, thumbnailPath = "/tmp/thumb.jpg")
        assertEquals("p-1", entity.id)
        assertEquals(30_000L, entity.durationMs)
        assertEquals(1, entity.sourceCount)
        assertEquals(1, entity.clipCount)
        assertEquals("/tmp/thumb.jpg", entity.thumbnailPath)
    }

    @Test
    fun summaryIsBuiltFromTheEntity() {
        val summary = ProjectDocuments.toSummary(
            ProjectDocuments.toEntity(project(), thumbnailPath = null),
        )
        assertEquals(ProjectId("p-1"), summary.id)
        assertEquals("Reel", summary.name)
        assertEquals(30_000L, summary.durationMs)
        assertNull(summary.thumbnailPath)
    }

    @Test
    fun analysisStagesRoundTripThroughStorage() {
        val stages = setOf(AnalysisStage.METADATA, AnalysisStage.SHOTS, AnalysisStage.COMPLETE)
        val raw = ProjectDocuments.stagesToStorage(stages)
        assertEquals(stages, ProjectDocuments.stagesFromStorage(raw))
        assertTrue(ProjectDocuments.stagesFromStorage("").isEmpty())
        assertTrue(ProjectDocuments.stagesFromStorage("NOT_A_STAGE").isEmpty())
    }
}
