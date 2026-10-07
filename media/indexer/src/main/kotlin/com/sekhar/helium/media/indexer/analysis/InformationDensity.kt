package com.sekhar.helium.media.indexer.analysis

import com.sekhar.helium.core.common.MathUtils
import com.sekhar.helium.core.model.InformationSample
import kotlin.math.abs

/**
 * Cheap per-frame signals used to reason about visual change.
 *
 * All values are already normalised to `0..1` by the extractor, so the density
 * model stays independent of how the numbers were produced.
 */
data class FrameSignal(
    val timestampMs: Long,
    /** Mean absolute luma difference against the previous sample. */
    val lumaDelta: Float,
    /** Histogram distance against the previous sample. */
    val histogramDelta: Float,
    /** Motion estimate against the previous sample. */
    val motion: Float,
    /** Audio RMS in the same window. */
    val audioRms: Float = 0f,
    /** 64-bit average hash, used to spot visually identical frames. */
    val perceptualHash: Long = 0L,
    /** True when the extractor is confident this is a shot boundary. */
    val isShotBoundary: Boolean = false,
)

/**
 * Turns frame-to-frame change into an information-density curve.
 *
 * The product never samples at a fixed frame rate: a static talking head needs
 * almost no visual samples, while a fast action beat needs dense coverage. This
 * class is the whole reason the semantic codec stays cheap — it decides where
 * looking is worth the cost.
 */
object InformationDensity {

    private const val WEIGHT_LUMA = 0.32f
    private const val WEIGHT_HISTOGRAM = 0.24f
    private const val WEIGHT_MOTION = 0.32f
    private const val WEIGHT_AUDIO = 0.12f

    /** Weighted score for one frame. */
    fun score(signal: FrameSignal): Float {
        val raw = WEIGHT_LUMA * signal.lumaDelta +
            WEIGHT_HISTOGRAM * signal.histogramDelta +
            WEIGHT_MOTION * signal.motion +
            WEIGHT_AUDIO * signal.audioRms
        return raw.coerceIn(0f, 1f)
    }

    /**
     * Builds the normalised curve.
     *
     * Scores are rescaled against the clip's own 95th percentile so a video that
     * simply has a low-contrast look is not treated as static.
     */
    fun computeCurve(signals: List<FrameSignal>): List<InformationSample> {
        if (signals.isEmpty()) return emptyList()
        val raw = signals.map { signal ->
            InformationSample(
                timestampMs = signal.timestampMs,
                score = score(signal),
                lumaDelta = signal.lumaDelta,
                histogramDelta = signal.histogramDelta,
                motion = signal.motion,
                audioRms = signal.audioRms,
            )
        }
        val peak = MathUtils.percentile(DoubleArray(raw.size) { raw[it].score.toDouble() }, 0.95)
        val scale = if (peak <= 1e-6) 1.0 else (1.0 / peak).coerceAtMost(4.0)
        return raw.map { it.copy(score = (it.score * scale).toFloat().coerceIn(0f, 1f)) }
    }

    /**
     * Selects the timestamps worth looking at.
     *
     * Always keeps the first and last sample, always keeps shot boundaries, then
     * adds the strongest local maxima subject to a minimum gap, finally filling
     * up to [maxSamples] by descending score. The result is ordered
     * chronologically.
     */
    fun selectSampleTimestamps(
        signals: List<FrameSignal>,
        maxSamples: Int = 12,
        minGapMs: Long = 350L,
        localMaxThreshold: Float = 0.35f,
    ): List<Long> {
        if (signals.isEmpty()) return emptyList()
        if (signals.size <= 2) return signals.map { it.timestampMs }
        val limited = maxSamples.coerceAtLeast(2)

        val curve = computeCurve(signals)
        val chosen = linkedSetOf<Long>()
        chosen += signals.first().timestampMs
        chosen += signals.last().timestampMs

        signals.filter { it.isShotBoundary }.forEach { chosen += it.timestampMs }

        // Local maxima of the information curve.
        curve.forEachIndexed { index, sample ->
            if (index == 0 || index == curve.lastIndex) return@forEachIndexed
            val previous = curve[index - 1].score
            val next = curve[index + 1].score
            if (sample.score >= previous && sample.score >= next && sample.score >= localMaxThreshold) {
                chosen += sample.timestampMs
            }
        }

        // Highest-scoring frames fill any remaining budget.
        curve.sortedByDescending { it.score }.forEach { sample ->
            if (chosen.size < limited) chosen += sample.timestampMs
        }

        return enforceMinGap(chosen.sorted(), minGapMs, limited)
    }

    /**
     * How many temporal states a moment deserves inside a contact sheet.
     *
     * Static shots get a single frame; complex action gets a dense strip; a fast
     * event is sampled more densely still. Sheets stay readable because the count
     * is bounded.
     */
    fun stripDensity(peakScore: Float, recentChange: Float): Int = when {
        recentChange > 0.85f -> 8
        recentChange > 0.6f -> 6
        peakScore > 0.55f -> 5
        peakScore > 0.3f -> 3
        else -> 1
    }

    /** True when two frames are visually identical enough to drop one. */
    fun isRedundant(previousHash: Long, currentHash: Long, tolerance: Int = 2): Boolean =
        previousHash != 0L && currentHash != 0L &&
            com.sekhar.helium.core.common.Hashing.hammingDistance(previousHash, currentHash) <= tolerance

    /** Groups consecutive samples whose score stays below [threshold]. */
    fun staticRuns(curve: List<InformationSample>, threshold: Float = 0.08f): List<Pair<Long, Long>> {
        if (curve.isEmpty()) return emptyList()
        val runs = mutableListOf<Pair<Long, Long>>()
        var runStart: Long? = null
        curve.forEach { sample ->
            if (sample.score <= threshold) {
                if (runStart == null) runStart = sample.timestampMs
            } else {
                runStart?.let { runs += it to sample.timestampMs }
                runStart = null
            }
        }
        runStart?.let { runs += it to curve.last().timestampMs }
        return runs
    }

    private fun enforceMinGap(timestamps: List<Long>, minGapMs: Long, maxSamples: Int): List<Long> {
        if (timestamps.size <= 1) return timestamps
        val result = mutableListOf<Long>()
        timestamps.forEach { timestamp ->
            val last = result.lastOrNull()
            if (last == null || abs(timestamp - last) >= minGapMs) {
                result += timestamp
            }
        }
        // Never drop the final frame: it is the end of the range being inspected.
        val lastInput = timestamps.last()
        if (result.last() != lastInput) {
            if (result.size >= maxSamples) result[result.lastIndex] = lastInput else result += lastInput
        }
        return result.sorted()
    }
}
