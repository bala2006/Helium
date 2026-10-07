package com.sekhar.helium.media.indexer.analysis

import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.common.MathUtils
import com.sekhar.helium.core.model.AudioEvent
import com.sekhar.helium.core.model.AudioEventType
import com.sekhar.helium.core.model.EventId
import com.sekhar.helium.core.model.TimeRange
import kotlin.math.abs
import kotlin.math.log10

/** Level and character of one analysis window. */
data class AudioWindow(
    val startMs: Long,
    val endMs: Long,
    val rms: Float,
    val dbfs: Float,
    val zeroCrossingRate: Float,
)

/** Everything the local audio pass produces. */
data class AudioAnalysis(
    val windows: List<AudioWindow> = emptyList(),
    val silences: List<TimeRange> = emptyList(),
    val speechRegions: List<TimeRange> = emptyList(),
    val musicRegions: List<TimeRange> = emptyList(),
    val events: List<AudioEvent> = emptyList(),
) {
    fun rmsAt(timestampMs: Long): Float =
        windows.firstOrNull { timestampMs >= it.startMs && timestampMs < it.endMs }?.rms ?: 0f
}

/**
 * Cheap, on-device audio understanding.
 *
 * Silence, speech presence, peaks and impacts are all derived from PCM the
 * decoder already produced, so the full audio never leaves the device and the
 * model is never asked to "listen" for something the phone can measure.
 *
 * The speech/music split here is deliberately rough: it exists to avoid cutting
 * deliberately held dramatic pauses and to duck music under talking, not to do
 * speaker diarisation.
 */
object AudioAnalyzer {

    fun analyse(
        samples: FloatArray,
        sampleRateHz: Int,
        ids: IdGenerator,
        windowMs: Long = 50L,
        silenceDbfs: Float = -42f,
        minSilenceMs: Long = 400L,
        minSpeechMs: Long = 150L,
        minMusicMs: Long = 1_000L,
    ): AudioAnalysis {
        if (samples.isEmpty() || sampleRateHz <= 0) return AudioAnalysis()

        val windowSamples = ((sampleRateHz * windowMs) / 1000L).toInt().coerceAtLeast(32)
        val windows = mutableListOf<AudioWindow>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + windowSamples, samples.size)
            val slice = samples.copyOfRange(offset, end)
            val rms = MathUtils.rms(slice).toFloat()
            windows += AudioWindow(
                startMs = (offset.toLong() * 1000L) / sampleRateHz,
                endMs = (end.toLong() * 1000L) / sampleRateHz,
                rms = rms,
                dbfs = if (rms <= 1e-6f) -100f else (20f * log10(rms.toDouble())).toFloat(),
                zeroCrossingRate = zeroCrossingRate(slice),
            )
            offset = end
        }

        val silences = mergeWindows(
            windows.filter { it.dbfs < silenceDbfs },
            minDurationMs = minSilenceMs,
        )
        val speech = mergeWindows(
            windows.filter { it.dbfs >= silenceDbfs && it.zeroCrossingRate in 0.02f..0.40f },
            minDurationMs = minSpeechMs,
        )
        val music = mergeWindows(
            windows.filter { it.dbfs >= silenceDbfs && it.zeroCrossingRate < 0.02f },
            minDurationMs = minMusicMs,
        )

        val peakRms = windows.maxOfOrNull { it.rms } ?: 0f
        val events = buildEvents(windows, silences, speech, peakRms, ids)

        return AudioAnalysis(
            windows = windows,
            silences = silences,
            speechRegions = speech,
            musicRegions = music,
            events = events,
        )
    }

    /** Merges consecutive windows into ranges of at least [minDurationMs]. */
    internal fun mergeWindows(windows: List<AudioWindow>, minDurationMs: Long): List<TimeRange> {
        if (windows.isEmpty()) return emptyList()
        val ranges = mutableListOf<TimeRange>()
        var startMs = windows.first().startMs
        var endMs = windows.first().endMs
        windows.drop(1).forEach { window ->
            if (window.startMs <= endMs + 1L) {
                endMs = maxOf(endMs, window.endMs)
            } else {
                if (endMs - startMs >= minDurationMs) ranges += TimeRange(startMs, endMs)
                startMs = window.startMs
                endMs = window.endMs
            }
        }
        if (endMs - startMs >= minDurationMs) ranges += TimeRange(startMs, endMs)
        return ranges
    }

    private fun buildEvents(
        windows: List<AudioWindow>,
        silences: List<TimeRange>,
        speech: List<TimeRange>,
        peakRms: Float,
        ids: IdGenerator,
    ): List<AudioEvent> {
        val events = mutableListOf<AudioEvent>()

        silences.forEach { range ->
            events += AudioEvent(
                id = EventId(ids.newId()),
                type = AudioEventType.SILENCE,
                timestampMs = range.startMs,
                level = 0f,
                durationMs = range.durationMs,
                label = "silence",
            )
        }

        speech.forEach { range ->
            events += AudioEvent(
                id = EventId(ids.newId()),
                type = AudioEventType.SPEECH_START,
                timestampMs = range.startMs,
                level = 0.6f,
                label = "speech",
            )
            events += AudioEvent(
                id = EventId(ids.newId()),
                type = AudioEventType.SPEECH_END,
                timestampMs = range.endMsExclusive,
                level = 0.6f,
                label = "speech",
            )
        }

        // Peaks: local maxima that are loud relative to the clip's own peak.
        if (peakRms > 0.02f) {
            windows.forEachIndexed { index, window ->
                val previous = windows.getOrNull(index - 1)?.rms ?: window.rms
                val next = windows.getOrNull(index + 1)?.rms ?: window.rms
                val isLocalMax = window.rms >= previous && window.rms >= next
                if (isLocalMax && window.rms >= peakRms * 0.72f) {
                    events += AudioEvent(
                        id = EventId(ids.newId()),
                        type = AudioEventType.PEAK,
                        timestampMs = window.endMs,
                        level = (window.rms / peakRms).coerceIn(0f, 1f),
                        label = "peak",
                    )
                }
            }
        }

        // Impacts: a sharp onset, which is what makes a crash, a slam or a clap
        // stand out from a gradual swell.
        windows.zipWithNext { previous, current ->
            val rise = current.rms - previous.rms
            if (rise >= 0.28f && current.dbfs > -30f) {
                events += AudioEvent(
                    id = EventId(ids.newId()),
                    type = AudioEventType.IMPACT,
                    timestampMs = current.startMs,
                    level = rise.coerceIn(0f, 1f),
                    label = "impact",
                )
            } else if (abs(previous.rms - current.rms) >= 0.22f) {
                val rising = current.rms > previous.rms
                events += AudioEvent(
                    id = EventId(ids.newId()),
                    type = if (rising) AudioEventType.LOUDNESS_RISE else AudioEventType.LOUDNESS_FALL,
                    timestampMs = current.startMs,
                    level = abs(current.rms - previous.rms).coerceIn(0f, 1f),
                )
            }
        }

        return events.sortedBy { it.timestampMs }
    }

    private fun zeroCrossingRate(samples: FloatArray): Float {
        if (samples.size < 2) return 0f
        var crossings = 0
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            if ((previous >= 0f && current < 0f) || (previous < 0f && current >= 0f)) crossings++
        }
        return crossings.toFloat() / (samples.size - 1)
    }
}
