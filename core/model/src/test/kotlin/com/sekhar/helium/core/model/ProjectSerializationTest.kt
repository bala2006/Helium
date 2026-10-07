package com.sekhar.helium.core.model

import com.sekhar.helium.core.common.HeliumJson
import com.sekhar.helium.core.common.SequentialIdGenerator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectSerializationTest {

    private fun sampleProject(): Project {
        val ids = SequentialIdGenerator("s")
        val source = SourceMedia(
            id = SourceId("src-1"),
            uri = "content://media/video/1",
            displayName = "clip.mp4",
            fingerprint = "fp",
            metadata = VideoMetadata(
                durationMs = 30_000L,
                width = 1080,
                height = 1920,
                frameRate = 30f,
                hasAudio = true,
            ),
            sizeBytes = 1_000L,
            importedAtEpochMs = 5L,
        )
        val project = Project(
            id = ProjectId("project-1"),
            name = "Reel",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            sources = listOf(source),
            baseTimeline = buildBaseTimeline(listOf(source), ids),
        )
        val clip = project.baseTimeline.allClips().first()
        val transaction = EditTransaction(
            id = "tx-1",
            index = 0L,
            userPrompt = "turn this into a reel",
            summary = "Removed 00:00.000-00:02.000",
            operations = listOf(
                RemoveRange("op-1", SourceId("src-1"), TimeRange(0L, 2_000L)),
                AddZoom("op-2", SourceId("src-1"), TimeRange(4_000L, 6_000L), scale = 1.5f),
                AddCaption(
                    "op-3",
                    SourceId("src-1"),
                    listOf(CaptionCueSpec(TimeRange(4_000L, 6_000L), "hello there")),
                ),
                AddText("op-4", 1_000L, 2_000L, "RIP"),
                SetSpeed("op-5", SourceId("src-1"), TimeRange(8_000L, 10_000L), 1.25f),
                BlurRegion("op-6", SourceId("src-1"), TimeRange(1_000L, 2_000L), NormalizedRect(0.1f, 0.1f, 0.4f, 0.3f)),
                FreezeFrame("op-7", SourceId("src-1"), 12_000L, 1_500L),
                SplitClip("op-8", clip.id, 5_000L),
                TrimClip("op-9", clip.id, TimeRange(1_000L, 9_000L)),
                AddMusic("op-10", SourceId("src-1"), durationMs = 5_000L),
                AddTransition("op-11", clip.id, TransitionBoundary.END, TransitionSpec(TransitionKind.FADE, 400L)),
                DeleteEdit("op-12", "tx-0"),
            ),
            createdAtEpochMs = 3L,
        )
        return project.copy(
            timeline = project.baseTimeline,
            history = EditHistory(listOf(transaction), 1),
        )
    }

    @Test
    fun projectRoundTripsThroughJson() {
        val project = sampleProject()
        val json = HeliumJson.encodeToString(project)
        val restored = HeliumJson.decodeFromString<Project>(json)
        assertEquals(project, restored)
        assertTrue(json.contains("\"kind\""), "polymorphic discriminator should be present")
    }

    @Test
    fun everyOperationKindSurvivesRoundTrip() {
        val project = sampleProject()
        val operations = project.history.transactions.first().operations
        val json = HeliumJson.encodeToString(operations)
        val restored = HeliumJson.decodeFromString<List<EditOperation>>(json)
        assertEquals(operations, restored)
    }

    @Test
    fun unknownFieldsAreIgnoredSoOlderClientsKeepWorking() {
        val json = """
            {
              "id": "project-1",
              "name": "Reel",
              "createdAtEpochMs": 1,
              "updatedAtEpochMs": 2,
              "someFutureField": {"nested": true}
            }
        """.trimIndent()
        val restored = HeliumJson.decodeFromString<Project>(json)
        assertEquals(ProjectId("project-1"), restored.id)
        assertEquals("Reel", restored.name)
    }
}
