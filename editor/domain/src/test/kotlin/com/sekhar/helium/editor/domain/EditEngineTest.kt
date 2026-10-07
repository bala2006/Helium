package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.AddZoom
import com.sekhar.helium.core.model.DeleteEdit
import com.sekhar.helium.core.model.RemoveRange
import com.sekhar.helium.core.model.SetAspectRatioOp
import com.sekhar.helium.core.model.AspectRatio
import com.sekhar.helium.core.model.SetSpeed
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.TimeRange
import com.sekhar.helium.core.model.ZoomEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditEngineTest {

    private val sourceId = SourceId("src-1")

    @Test
    fun aFailedOperationLeavesTheProjectCompletelyUntouched() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)

        val result = engine.applyTransaction(
            project = project,
            userPrompt = "remove the pauses",
            operations = listOf(
                RemoveRange("op-1", sourceId, TimeRange(0L, 10_000L)),
                // Second operation targets a source that does not exist.
                RemoveRange("op-2", SourceId("nope"), TimeRange(0L, 1_000L)),
            ),
        )

        assertFalse(result.outcome.success)
        assertEquals(project, result.project)
        assertEquals(60_000L, result.project.timeline.durationMs)
        assertEquals(0, result.project.history.cursor)
        assertTrue(result.outcome.errors.isNotEmpty())
    }

    @Test
    fun aSuccessfulTransactionUpdatesTimelineHistoryAndSummary() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)

        val result = engine.applyTransaction(
            project = project,
            userPrompt = "cut the intro",
            operations = listOf(RemoveRange("op-1", sourceId, TimeRange(0L, 10_000L))),
        )

        assertTrue(result.outcome.success)
        assertEquals(50_000L, result.project.timeline.durationMs)
        assertEquals(1, result.project.history.cursor)
        assertEquals(1, result.project.history.transactions.size)
        assertEquals(1, result.project.revision)
        assertTrue(result.outcome.summary.isNotBlank())
        assertEquals("cut the intro", result.project.history.transactions.first().userPrompt)
    }

    @Test
    fun undoRestoresThePreviousTimelineAndRedoReappliesIt() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)

        val edited = engine.applyTransaction(
            project,
            "cut the intro",
            listOf(RemoveRange("op-1", sourceId, TimeRange(0L, 10_000L))),
        ).project

        val undone = engine.undo(edited).project
        assertEquals(60_000L, undone.timeline.durationMs)
        assertEquals(0, undone.history.cursor)

        val redone = engine.redo(undone).project
        assertEquals(50_000L, redone.timeline.durationMs)
        assertEquals(1, redone.history.cursor)
    }

    @Test
    fun undoAtTheStartReportsFailureWithoutChangingAnything() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)
        val result = engine.undo(project)
        assertFalse(result.outcome.success)
        assertEquals(project, result.project)
    }

    @Test
    fun aNewEditAfterUndoDropsTheRedoStack() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)

        val first = engine.applyTransaction(
            project,
            "cut the intro",
            listOf(RemoveRange("op-1", sourceId, TimeRange(0L, 10_000L))),
        ).project
        val second = engine.applyTransaction(
            first,
            "speed it up",
            listOf(SetSpeed("op-2", sourceId, TimeRange(20_000L, 30_000L), 2f)),
        ).project
        assertEquals(2, second.history.transactions.size)

        val undone = engine.undo(second).project
        assertTrue(undone.history.canRedo)

        val reedited = engine.applyTransaction(
            undone,
            "something else",
            listOf(RemoveRange("op-3", sourceId, TimeRange(40_000L, 45_000L))),
        ).project

        assertEquals(2, reedited.history.transactions.size)
        assertFalse(reedited.history.canRedo, "a new edit must discard the redo stack")
    }

    @Test
    fun deleteEditRevertsOneSpecificEarlierTransaction() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)

        val first = engine.applyTransaction(
            project,
            "add a zoom",
            listOf(AddZoom("op-1", sourceId, TimeRange(0L, 5_000L), 2f)),
        ).project
        val second = engine.applyTransaction(
            first,
            "speed up",
            listOf(SetSpeed("op-2", sourceId, TimeRange(20_000L, 30_000L), 2f)),
        ).project
        assertTrue(second.timeline.allClips().any { clip -> clip.effects.any { it is ZoomEffect } })

        val targetId = first.history.transactions.first().id
        val reverted = engine.applyTransaction(
            second,
            "undo the zoom you just added",
            listOf(DeleteEdit("op-3", targetId)),
        ).project

        assertTrue(
            reverted.timeline.allClips().none { clip -> clip.effects.any { it is ZoomEffect } },
            "the reverted zoom must be gone",
        )
        assertTrue(reverted.history.transactions.none { it.id == targetId })
        // The speed change from the later transaction is still in effect.
        assertTrue(reverted.timeline.allClips().any { it.speed == 2f })
    }

    @Test
    fun aspectRatioChangesAreRecordedOnTheProject() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)
        val result = engine.applyTransaction(
            project,
            "make it 9:16",
            listOf(SetAspectRatioOp("op-1", AspectRatio.PORTRAIT_9_16)),
        )
        assertTrue(result.outcome.success)
        assertEquals(AspectRatio.PORTRAIT_9_16, result.project.aspectRatio)
    }

    @Test
    fun replayIsStableAcrossRepeatedCalls() {
        val (project, ids) = Fixtures.project()
        val engine = Fixtures.engine(ids)
        val edited = engine.applyTransaction(
            project,
            "cut",
            listOf(
                RemoveRange("op-1", sourceId, TimeRange(0L, 5_000L)),
                SetSpeed("op-2", sourceId, TimeRange(10_000L, 20_000L), 1.5f),
            ),
        ).project

        val once = engine.replay(edited)
        val twice = engine.replay(edited)
        assertEquals(once.timeline, twice.timeline)
        assertEquals(edited.timeline, once.timeline)
    }
}
