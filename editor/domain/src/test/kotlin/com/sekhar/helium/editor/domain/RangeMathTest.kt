package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeMathTest {

    @Test
    fun normalizeMergesOverlappingAndAdjacentRanges() {
        val merged = RangeMath.normalize(
            listOf(TimeRange(0L, 10L), TimeRange(10L, 20L), TimeRange(50L, 60L)),
        )
        assertEquals(listOf(TimeRange(0L, 20L), TimeRange(50L, 60L)), merged)
    }

    @Test
    fun normalizeDropsEmptyRanges() {
        assertTrue(RangeMath.normalize(listOf(TimeRange(5L, 5L))).isEmpty())
    }

    @Test
    fun subtractLeavesTheGapOut() {
        val pieces = RangeMath.subtract(TimeRange(0L, 100L), listOf(TimeRange(30L, 60L)))
        assertEquals(listOf(TimeRange(0L, 30L), TimeRange(60L, 100L)), pieces)
    }

    @Test
    fun subtractAtAnEdgeYieldsOnePiece() {
        assertEquals(
            listOf(TimeRange(10L, 100L)),
            RangeMath.subtract(TimeRange(0L, 100L), listOf(TimeRange(0L, 10L))),
        )
        assertEquals(
            listOf(TimeRange(0L, 90L)),
            RangeMath.subtract(TimeRange(0L, 100L), listOf(TimeRange(90L, 100L))),
        )
    }

    @Test
    fun subtractEverythingYieldsNothing() {
        assertTrue(RangeMath.subtract(TimeRange(0L, 100L), listOf(TimeRange(0L, 100L))).isEmpty())
    }

    @Test
    fun subtractHandlesMultipleDisjointCuts() {
        val pieces = RangeMath.subtract(
            TimeRange(0L, 100L),
            listOf(TimeRange(20L, 30L), TimeRange(60L, 70L)),
        )
        assertEquals(
            listOf(TimeRange(0L, 20L), TimeRange(30L, 60L), TimeRange(70L, 100L)),
            pieces,
        )
    }

    @Test
    fun subtractIgnoresNonOverlappingCuts() {
        assertEquals(
            listOf(TimeRange(0L, 100L)),
            RangeMath.subtract(TimeRange(0L, 100L), listOf(TimeRange(200L, 300L))),
        )
    }

    @Test
    fun splitAtProducesOrderedPiecesAndIgnoresBadPoints() {
        val pieces = RangeMath.splitAt(TimeRange(0L, 100L), listOf(50L, 0L, 100L, 500L))
        assertEquals(listOf(TimeRange(0L, 50L), TimeRange(50L, 100L)), pieces)
    }

    @Test
    fun splitAtWithNoInteriorPointsReturnsTheOriginal() {
        assertEquals(
            listOf(TimeRange(0L, 100L)),
            RangeMath.splitAt(TimeRange(0L, 100L), listOf(0L, 100L)),
        )
    }

    @Test
    fun totalDurationSumsMergedRanges() {
        assertEquals(
            50L,
            RangeMath.totalDuration(listOf(TimeRange(0L, 20L), TimeRange(10L, 30L), TimeRange(40L, 60L))),
        )
    }
}
