package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.TimeRange

/**
 * Interval arithmetic on source ranges.
 *
 * All of this is pure: given the same inputs the timeline reducer always
 * produces the same output, which is what makes transactions replayable and
 * undo exact.
 */
object RangeMath {

    /** Merges overlapping/adjacent ranges into the smallest covering set. */
    fun normalize(ranges: List<TimeRange>): List<TimeRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.filter { !it.isEmpty }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf(sorted.first())
        for (range in sorted.drop(1)) {
            val last = merged.last()
            if (range.startMs <= last.endMsExclusive) {
                merged[merged.lastIndex] = TimeRange(last.startMs, maxOf(last.endMsExclusive, range.endMsExclusive))
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * Everything in [range] that is not covered by [removed].
     *
     * Returns pieces in ascending order; an empty list means the whole range was
     * removed.
     */
    fun subtract(range: TimeRange, removed: List<TimeRange>): List<TimeRange> {
        if (range.isEmpty) return emptyList()
        val cuts = normalize(removed.mapNotNull { it.intersect(range) })
        if (cuts.isEmpty()) return listOf(range)
        val pieces = mutableListOf<TimeRange>()
        var cursor = range.startMs
        for (cut in cuts) {
            if (cut.startMs > cursor) pieces += TimeRange(cursor, cut.startMs)
            cursor = maxOf(cursor, cut.endMsExclusive)
        }
        if (cursor < range.endMsExclusive) pieces += TimeRange(cursor, range.endMsExclusive)
        return pieces
    }

    /** Union of [ranges] clipped to `[0, upperBoundMs]`. */
    fun clipAll(ranges: List<TimeRange>, upperBoundMs: Long): List<TimeRange> =
        normalize(ranges).mapNotNull { it.intersect(TimeRange(0L, maxOf(0L, upperBoundMs))) }

    /** Splits [range] at every point in [timestampsMs] that falls strictly inside it. */
    fun splitAt(range: TimeRange, timestampsMs: List<Long>): List<TimeRange> {
        val points = timestampsMs
            .filter { it > range.startMs && it < range.endMsExclusive }
            .distinct()
            .sorted()
        if (points.isEmpty()) return listOf(range)
        val boundaries = listOf(range.startMs) + points + listOf(range.endMsExclusive)
        return boundaries.zipWithNext { start, end -> TimeRange(start, end) }
            .filter { !it.isEmpty }
    }

    /** Total length of [ranges] after merging. */
    fun totalDuration(ranges: List<TimeRange>): Long = normalize(ranges).sumOf { it.durationMs }

    /** True when [inner] is fully inside [outer]. */
    fun contains(outer: TimeRange, inner: TimeRange): Boolean = outer.containsRange(inner)
}
