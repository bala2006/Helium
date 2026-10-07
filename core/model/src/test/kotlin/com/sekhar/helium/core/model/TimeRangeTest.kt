package com.sekhar.helium.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeRangeTest {

    @Test
    fun durationAndEmptiness() {
        val range = TimeRange(1_000L, 3_500L)
        assertEquals(2_500L, range.durationMs)
        assertFalse(range.isEmpty)
        assertTrue(TimeRange(10L, 10L).isEmpty)
    }

    @Test
    fun halfOpenContainsIsExclusiveAtTheEnd() {
        val range = TimeRange(0L, 1_000L)
        assertTrue(range.contains(0L))
        assertTrue(range.contains(999L))
        assertFalse(range.contains(1_000L))
    }

    @Test
    fun intersectReturnsNullWhenDisjoint() {
        assertNull(TimeRange(0L, 100L).intersect(TimeRange(100L, 200L)))
        assertEquals(TimeRange(50L, 100L), TimeRange(0L, 100L).intersect(TimeRange(50L, 200L)))
    }

    @Test
    fun clampToBounds() {
        val bounds = TimeRange(0L, 1_000L)
        assertEquals(TimeRange(200L, 800L), TimeRange(200L, 800L).clampTo(bounds))
        assertEquals(TimeRange(1_000L, 1_000L), TimeRange(2_000L, 3_000L).clampTo(bounds))
    }

    @Test
    fun invalidRangesAreRejected() {
        assertFailsWith<IllegalArgumentException> { TimeRange(-1L, 10L) }
        assertFailsWith<IllegalArgumentException> { TimeRange(10L, 5L) }
    }

    @Test
    fun ofDurationBuildsHalfOpenRange() {
        val range = TimeRange.ofDuration(2_000L, 500L)
        assertEquals(2_000L, range.startMs)
        assertEquals(2_500L, range.endMsExclusive)
    }
}
