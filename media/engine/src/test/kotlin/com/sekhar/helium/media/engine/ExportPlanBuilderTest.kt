package com.sekhar.helium.media.engine

import com.sekhar.helium.core.common.SequentialIdGenerator
import com.sekhar.helium.core.model.AspectRatio
import com.sekhar.helium.core.model.ExportPreset
import com.sekhar.helium.core.model.ExportSettings
import com.sekhar.helium.core.model.NormalizedRect
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.ProjectId
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SourceMedia
import com.sekhar.helium.core.model.VideoMetadata
import com.sekhar.helium.core.model.buildBaseTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportPlanBuilderTest {

    private val ids = SequentialIdGenerator("x")

    private fun source(
        id: String = "src-1",
        durationMs: Long = 30_000L,
        width: Int = 1920,
        height: Int = 1080,
        hasAudio: Boolean = true,
    ) = SourceMedia(
        id = SourceId(id),
        uri = "content://media/video/$id",
        displayName = "clip.mp4",
        fingerprint = "fp-$id",
        metadata = VideoMetadata(durationMs, width, height, hasAudio = hasAudio),
        sizeBytes = 1_000L,
        importedAtEpochMs = 0L,
    )

    private fun project(vararg sources: SourceMedia): Project {
        val list = sources.toList()
        return Project(
            id = ProjectId("p-1"),
            name = "Reel",
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
            sources = list,
            baseTimeline = buildBaseTimeline(list, ids),
        ).let { it.copy(timeline = it.baseTimeline) }
    }

    @Test
    fun originalPresetKeepsTheSourceResolutionAndUsesEvenDimensions() {
        val plan = ExportPlanBuilder.build(
            project(source(width = 1921, height = 1081)),
            ExportSettings(preset = ExportPreset.ORIGINAL),
        )
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
    }

    @Test
    fun verticalPresetProducesNineBySixteen() {
        val plan = ExportPlanBuilder.build(
            project(source()),
            ExportSettings(preset = ExportPreset.VERTICAL_1080P),
        )
        assertEquals(1080, plan.width)
        assertEquals(1920, plan.height)
    }

    @Test
    fun explicitAspectRatioIsHonouredForOriginalPreset() {
        val base = project(source(width = 1920, height = 1080))
        val plan = ExportPlanBuilder.build(
            base.copy(aspectRatio = AspectRatio.PORTRAIT_9_16),
            ExportSettings(preset = ExportPreset.ORIGINAL),
        )
        assertEquals(1080, plan.width)
        assertEquals(1920, plan.height)
    }

    @Test
    fun planAlwaysPointsAtTheOriginalMediaNotAProxy() {
        val media = source().copy(proxyPath = "/cache/proxy.mp4")
        val plan = ExportPlanBuilder.build(project(media), ExportSettings())
        assertEquals(1, plan.clips.size)
        assertEquals("content://media/video/src-1", plan.clips.first().uri)
        assertTrue(!plan.clips.first().uri.contains("proxy"))
    }

    @Test
    fun concatenatedSourcesBecomeOrderedClips() {
        val plan = ExportPlanBuilder.build(
            project(source("a", durationMs = 5_000L), source("b", durationMs = 7_000L)),
            ExportSettings(),
        )
        assertEquals(2, plan.clips.size)
        assertEquals(0L, plan.clips[0].timelineStartMs)
        assertEquals(5_000L, plan.clips[1].timelineStartMs)
        assertEquals(12_000L, plan.durationMs)
    }

    @Test
    fun cropIsCarriedIntoThePlan() {
        val base = project(source())
        val clip = base.timeline.allClips().first()
        val cropped = base.copy(
            timeline = base.timeline.copy(
                videoTracks = base.timeline.videoTracks.map { track ->
                    track.copy(clips = track.clips.map { it.copy(crop = com.sekhar.helium.core.model.CropRect(NormalizedRect(0.2f, 0f, 0.8f, 1f))) })
                },
            ),
        )
        val plan = ExportPlanBuilder.build(cropped, ExportSettings())
        assertEquals(NormalizedRect(0.2f, 0f, 0.8f, 1f), plan.clips.first().crop)
        assertEquals(clip.sourceId, plan.clips.first().sourceId)
    }

    @Test
    fun unsupportedFeaturesAreReportedRatherThanDropped() {
        val base = project(source())
        val withText = base.copy(
            timeline = com.sekhar.helium.core.model.Timeline(
                videoTracks = base.timeline.videoTracks,
                audioTracks = base.timeline.audioTracks,
                textTracks = listOf(
                    com.sekhar.helium.core.model.TextTrack(
                        id = com.sekhar.helium.core.model.TrackId("t1"),
                        items = listOf(
                            com.sekhar.helium.core.model.TextItem(
                                id = com.sekhar.helium.core.model.TextItemId("i1"),
                                range = com.sekhar.helium.core.model.TimeRange(0L, 1_000L),
                                text = "hello",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val plan = ExportPlanBuilder.build(withText, ExportSettings())
        assertTrue(
            plan.unsupportedFeatures.any { it.contains("Text") },
            "expected text to be reported, got ${plan.unsupportedFeatures}",
        )
    }

    @Test
    fun centeredCropMatchesTheRequestedAspect() {
        val crop = ExportPlanBuilder.centeredCrop(sourceAspect = 16f / 9f, targetAspect = 9f / 16f)
        val windowAspect = (crop.width * (16f / 9f)) / crop.height
        assertEquals(9f / 16f, windowAspect, 1e-3f)
    }

    @Test
    fun emptyTimelineProducesAnEmptyPlan() {
        val empty = Project(
            id = ProjectId("p"),
            name = "Empty",
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )
        assertTrue(ExportPlanBuilder.build(empty, ExportSettings()).isEmpty)
    }
}
