package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.AddCaption
import com.sekhar.helium.core.model.AddZoom
import com.sekhar.helium.core.model.AspectRatio
import com.sekhar.helium.core.model.CaptionCueSpec
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.FreezeFrame
import com.sekhar.helium.core.model.MuteRange
import com.sekhar.helium.core.model.RemoveRange
import com.sekhar.helium.core.model.RestoreRange
import com.sekhar.helium.core.model.SetAspectRatioOp
import com.sekhar.helium.core.model.SetSpeed
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SplitClip
import com.sekhar.helium.core.model.TimeRange
import com.sekhar.helium.core.model.Timeline
import com.sekhar.helium.core.model.ZoomEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TimelineReducerTest {

    private val fixture = Fixtures.project()
    private val base: Timeline = fixture.first.baseTimeline

    // The reducer takes no id generator on purpose: every id it creates is derived
    // from the operation, so replaying the history reproduces the same timeline.
    private val reducer = TimelineReducer()
    private val sourceId = SourceId("src-1")
    private val sources = mapOf(sourceId to Fixtures.metadata())

    private fun reduce(
        op: EditOperation,
        from: Timeline = base,
        catalog: Map<SourceId, com.sekhar.helium.core.model.VideoMetadata> = sources,
    ): ReduceResult = reducer.apply(from, op, catalog)

    private fun success(op: EditOperation, from: Timeline = base): Timeline =
        when (val result = reduce(op, from)) {
            is ReduceResult.Success -> result.timeline
            is ReduceResult.Failure -> fail("expected success but got ${result.errors}")
        }

    @Test
    fun baseTimelineIsOneContiguousClipPerSource() {
        assertEquals(1, base.allClips().size)
        assertEquals(TimeRange(0L, 60_000L), base.allClips().first().sourceRange)
        assertEquals(60_000L, base.durationMs)
    }

    @Test
    fun splitProducesTwoContiguousClips() {
        val clip = base.allClips().first()
        val result = success(SplitClip("op", clip.id, 30_000L))
        assertEquals(2, result.allClips().size)
        assertEquals(0L, result.allClips()[0].timelineStartMs)
        assertEquals(30_000L, result.allClips()[1].timelineStartMs)
        assertEquals(60_000L, result.durationMs)
        assertEquals(TimeRange(0L, 30_000L), result.allClips()[0].sourceRange)
        assertEquals(TimeRange(30_000L, 60_000L), result.allClips()[1].sourceRange)
    }

    @Test
    fun splitOutsideTheClipFails() {
        val clip = base.allClips().first()
        assertTrue(reduce(SplitClip("op", clip.id, 0L)) is ReduceResult.Failure)
        assertTrue(reduce(SplitClip("op", clip.id, 60_000L)) is ReduceResult.Failure)
    }

    @Test
    fun trimReplacesTheSourceRangeAndRepacks() {
        val clip = base.allClips().first()
        val result = success(
            com.sekhar.helium.core.model.TrimClip("op", clip.id, TimeRange(5_000L, 25_000L)),
        )
        assertEquals(1, result.allClips().size)
        assertEquals(20_000L, result.durationMs)
        assertEquals(TimeRange(5_000L, 25_000L), result.allClips().first().sourceRange)
    }

    @Test
    fun removingTheIntroClosesTheGap() {
        val result = success(RemoveRange("op", sourceId, TimeRange(0L, 10_000L)))
        assertEquals(1, result.allClips().size)
        assertEquals(50_000L, result.durationMs)
        assertEquals(TimeRange(10_000L, 60_000L), result.allClips().first().sourceRange)
    }

    @Test
    fun removingAMiddleRangeSplitsTheClipAndTightensIt() {
        val result = success(RemoveRange("op", sourceId, TimeRange(10_000L, 20_000L)))
        assertEquals(2, result.allClips().size)
        assertEquals(50_000L, result.durationMs)
        assertEquals(TimeRange(0L, 10_000L), result.allClips()[0].sourceRange)
        assertEquals(TimeRange(20_000L, 60_000L), result.allClips()[1].sourceRange)
        assertEquals(10_000L, result.allClips()[1].timelineStartMs)
    }

    @Test
    fun restoreBringsRemovedMediaBack() {
        val removed = success(RemoveRange("op1", sourceId, TimeRange(10_000L, 20_000L)))
        assertEquals(50_000L, removed.durationMs)

        val restored = success(RestoreRange("op2", sourceId, TimeRange(10_000L, 20_000L)), from = removed)
        assertEquals(60_000L, restored.durationMs)

        var cursor = 0L
        restored.allClips().forEach { clip ->
            assertEquals(cursor, clip.timelineStartMs, "clips must stay contiguous")
            cursor += clip.timelineDurationMs
        }
        assertEquals(60_000L, cursor)
    }

    @Test
    fun setSpeedHalvesTheAffectedSpanAndPacksTheRest() {
        val result = success(SetSpeed("op", sourceId, TimeRange(0L, 10_000L), 2f))
        assertEquals(2, result.allClips().size)
        assertEquals(2f, result.allClips()[0].speed)
        assertEquals(1f, result.allClips()[1].speed)
        assertEquals(55_000L, result.durationMs)
    }

    @Test
    fun muteRangeSetsVolumeToZeroForTheExactSpanOnly() {
        val result = success(MuteRange("op", sourceId, TimeRange(0L, 5_000L)))
        assertEquals(2, result.allClips().size)
        assertEquals(0f, result.allClips()[0].volume)
        assertEquals(1f, result.allClips()[1].volume)
    }

    @Test
    fun addZoomSplitsAroundTheRangeAndAttachesTheEffect() {
        val result = success(AddZoom("op", sourceId, TimeRange(10_000L, 20_000L), scale = 2f))
        assertEquals(3, result.allClips().size)
        val middle = result.allClips()[1]
        assertEquals(TimeRange(10_000L, 20_000L), middle.sourceRange)
        assertEquals(10_000L, middle.timelineStartMs)
        assertEquals(1, middle.effects.size)
        val effect = middle.effects.first()
        assertTrue(effect is ZoomEffect)
        assertEquals(0L, effect.range.startMs)
        assertEquals(10_000L, effect.range.endMsExclusive)
        // Zoom does not change duration.
        assertEquals(60_000L, result.durationMs)
    }

    @Test
    fun freezeExtendsTheTimelineAndAddsTheLabel() {
        val result = success(FreezeFrame("op", sourceId, 30_000L, holdMs = 1_500L, overlayText = "RIP"))
        assertEquals(61_500L, result.durationMs)

        val frozen = result.allClips().first { it.freeze != null }
        assertEquals(30_000L, frozen.timelineStartMs)
        assertEquals(1_500L, frozen.freeze?.holdMs)

        val texts = result.allTextItems()
        assertEquals(1, texts.size)
        assertEquals("RIP", texts.first().text)
        assertEquals(TimeRange(30_000L, 31_500L), texts.first().range)
    }

    @Test
    fun captionsMapSourceTimeOntoTimelineTime() {
        val result = success(
            AddCaption("op", sourceId, listOf(CaptionCueSpec(TimeRange(4_000L, 6_000L), "hello there"))),
        )
        val items = result.allTextItems()
        assertEquals(1, items.size)
        assertEquals(TimeRange(4_000L, 6_000L), items.first().range)
        assertTrue(items.first().isCaption)
        assertEquals(sourceId, items.first().lockedToSource)
    }

    @Test
    fun captionsFollowTheTimelineAfterARippleDelete() {
        val removed = success(RemoveRange("op1", sourceId, TimeRange(0L, 10_000L)))
        val captioned = success(
            AddCaption("op2", sourceId, listOf(CaptionCueSpec(TimeRange(12_000L, 14_000L), "here"))),
            from = removed,
        )
        // Source 12s sits 2s into the post-cut timeline.
        assertEquals(2_000L, captioned.allTextItems().first().range.startMs)
    }

    @Test
    fun operationsOnUnknownSourcesFail() {
        val result = reduce(RemoveRange("op", SourceId("missing"), TimeRange(0L, 1_000L)))
        assertTrue(result is ReduceResult.Failure)
    }

    @Test
    fun aspectRatioIsNotATimelineChange() {
        val result = success(SetAspectRatioOp("op", AspectRatio.PORTRAIT_9_16))
        assertEquals(base, result)
    }

    @Test
    fun removeCaptionCanTargetText() {
        val captioned = success(
            AddCaption("op1", sourceId, listOf(CaptionCueSpec(TimeRange(1_000L, 2_000L), "buy now"))),
        )
        val result = success(
            com.sekhar.helium.core.model.RemoveCaption("op2", containingText = "buy now"),
            from = captioned,
        )
        assertTrue(result.allTextItems().isEmpty())
    }
}
