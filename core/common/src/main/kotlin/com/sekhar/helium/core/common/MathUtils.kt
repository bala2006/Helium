package com.sekhar.helium.core.common

/** Small numeric helpers shared by the analysis and timeline code. */
object MathUtils {

    fun clamp(value: Double, min: Double, max: Double): Double =
        if (value < min) min else if (value > max) max else value

    fun clamp(value: Float, min: Float, max: Float): Float =
        if (value < min) min else if (value > max) max else value

    fun clamp(value: Long, min: Long, max: Long): Long =
        if (value < min) min else if (value > max) max else value

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /** Maps [value] from `[fromMin, fromMax]` onto `[0, 1]`, clamped. */
    fun normalize(value: Double, fromMin: Double, fromMax: Double): Double {
        if (fromMax <= fromMin) return 0.0
        return clamp((value - fromMin) / (fromMax - fromMin), 0.0, 1.0)
    }

    /**
     * Linear-interpolated [p] percentile (0..1) of [values].
     * Returns 0.0 for an empty input.
     */
    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf()
        sorted.sort()
        val rank = clamp(p, 0.0, 1.0) * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val frac = rank - lower
        return lerp(sorted[lower], sorted[upper], frac)
    }

    /** Root-mean-square of [samples]. */
    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return kotlin.math.sqrt(sum / samples.size)
    }
}
