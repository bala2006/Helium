package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/**
 * Half-open time interval `[startMs, endMsExclusive)`.
 *
 * Every timestamp in Helium is a `Long` millisecond value. Floating-point
 * seconds are never stored in the timeline, which removes accumulation drift
 * across hundreds of edits and exports.
 */
@Serializable
data class TimeRange(
    val startMs: Long,
    val endMsExclusive: Long,
) : Comparable<TimeRange> {

    init {
        require(startMs >= 0) { "startMs must be >= 0 but was $startMs" }
        require(endMsExclusive >= startMs) {
            "endMsExclusive ($endMsExclusive) must be >= startMs ($startMs)"
        }
    }

    val durationMs: Long get() = endMsExclusive - startMs

    val isEmpty: Boolean get() = endMsExclusive <= startMs

    fun contains(timestampMs: Long): Boolean =
        timestampMs >= startMs && timestampMs < endMsExclusive

    fun containsRange(other: TimeRange): Boolean =
        other.startMs >= startMs && other.endMsExclusive <= endMsExclusive

    fun overlaps(other: TimeRange): Boolean =
        startMs < other.endMsExclusive && other.startMs < endMsExclusive

    /** Overlap between this range and [other], or `null` when they are disjoint. */
    fun intersect(other: TimeRange): TimeRange? {
        val start = maxOf(startMs, other.startMs)
        val end = minOf(endMsExclusive, other.endMsExclusive)
        return if (end <= start) null else TimeRange(start, end)
    }

    /** The part of this range that lies inside [bounds]. */
    fun clampTo(bounds: TimeRange): TimeRange {
        val start = startMs.coerceIn(bounds.startMs, bounds.endMsExclusive)
        val end = endMsExclusive.coerceIn(bounds.startMs, bounds.endMsExclusive)
        return if (end <= start) TimeRange(start, start) else TimeRange(start, end)
    }

    fun shifted(deltaMs: Long): TimeRange = TimeRange(startMs + deltaMs, endMsExclusive + deltaMs)

    override fun compareTo(other: TimeRange): Int =
        compareValuesBy(this, other, { it.startMs }, { it.endMsExclusive })

    companion object {
        val ZERO = TimeRange(0L, 0L)

        fun ofDuration(startMs: Long, durationMs: Long): TimeRange =
            TimeRange(startMs, startMs + maxOf(0L, durationMs))
    }
}

/** Normalised `0..1` rectangle, used for OCR regions, blur masks and crop. */
@Serializable
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right >= left) { "right must be >= left" }
        require(bottom >= top) { "bottom must be >= top" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

/** Normalised point, `0..1` in each axis, origin at the top-left. */
@Serializable
data class NormalizedPoint(val x: Float, val y: Float) {
    companion object {
        val CENTER = NormalizedPoint(0.5f, 0.5f)
    }
}
