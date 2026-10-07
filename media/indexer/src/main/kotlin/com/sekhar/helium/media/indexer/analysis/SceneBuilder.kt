package com.sekhar.helium.media.indexer.analysis

import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.model.AudioEvent
import com.sekhar.helium.core.model.AudioEventType
import com.sekhar.helium.core.model.EventId
import com.sekhar.helium.core.model.EventPacket
import com.sekhar.helium.core.model.MotionLevel
import com.sekhar.helium.core.model.MotionSegment
import com.sekhar.helium.core.model.OcrText
import com.sekhar.helium.core.model.Scene
import com.sekhar.helium.core.model.SceneId
import com.sekhar.helium.core.model.ShotBoundary
import com.sekhar.helium.core.model.ShotId
import com.sekhar.helium.core.model.TimeRange
import com.sekhar.helium.core.model.TranscriptSegment
import com.sekhar.helium.core.model.VisualEvent
import com.sekhar.helium.core.model.VisualEventType
import com.sekhar.helium.core.model.formatTimestampMs

/**
 * Groups per-frame measurements into the hierarchical structures the model
 * reasons over: shots, scenes, motion segments, visual events and the
 * `BEFORE → ACTION → AFTER` event packets.
 *
 * Everything here is derived from measurements, never invented. The text that
 * ends up in an [EventPacket] is a label for measured behaviour ("sudden motion
 * with an impact", "still"), which is what lets the model reason about a moment
 * without seeing every frame.
 */
object SceneBuilder {

    private const val SHOT_CHANGE_THRESHOLD = 0.55f
    private const val SCENE_MERGE_THRESHOLD = 0.35f
    private const val MIN_SCENE_MS = 700L

    fun buildShots(signals: List<FrameSignal>): List<ShotBoundary> {
        if (signals.isEmpty()) return emptyList()
        val boundaries = mutableListOf<ShotBoundary>()
        if (signals.first().timestampMs > 0L) {
            boundaries += ShotBoundary(ShotId("shot-0"), 0L, 1f)
        }
        signals.forEachIndexed { index, signal ->
            val change = maxOf(signal.lumaDelta, signal.histogramDelta)
            val previous = signals.getOrNull(index - 1)
            val spike = previous == null || (change > SHOT_CHANGE_THRESHOLD && change > maxOf(previous.lumaDelta, previous.histogramDelta))
            if (signal.isShotBoundary || (index > 0 && spike)) {
                boundaries += ShotBoundary(
                    id = ShotId("shot-${signal.timestampMs}"),
                    timestampMs = signal.timestampMs,
                    confidence = change.coerceIn(0f, 1f),
                )
            }
        }
        return boundaries.sortedBy { it.timestampMs }
    }

    fun buildScenes(
        signals: List<FrameSignal>,
        shots: List<ShotBoundary>,
        transcript: List<TranscriptSegment>,
        ocr: List<OcrText>,
        ids: IdGenerator,
        durationMs: Long,
    ): List<Scene> {
        if (signals.isEmpty()) return emptyList()

        val cutPoints = mutableListOf<Long>()
        cutPoints += signals.first().timestampMs
        signals.forEachIndexed { index, signal ->
            if (index == 0) return@forEachIndexed
            val previous = signals[index - 1]
            val change = maxOf(signal.lumaDelta, signal.histogramDelta)
            val previousChange = maxOf(previous.lumaDelta, previous.histogramDelta)
            val abrupt = change > SHOT_CHANGE_THRESHOLD && change > previousChange + 0.15f
            if (abrupt) cutPoints += signal.timestampMs
        }
        cutPoints += durationMs

        val scenes = mutableListOf<Scene>()
        cutPoints.distinct().sorted().zipWithNext { start, end ->
            if (end - start < MIN_SCENE_MS) return@zipWithNext
            val range = TimeRange(start, end)
            val windowSignals = signals.filter { it.timestampMs in start until end }
            val peakChange = windowSignals.maxOfOrNull { maxOf(it.lumaDelta, it.histogramDelta) } ?: 0f
            val averageMotion = if (windowSignals.isEmpty()) 0f else windowSignals.map { it.motion }.average().toFloat()
            val sceneTranscript = transcript.filter { it.range.overlaps(range) }
            val sceneOcr = ocr.filter { it.range.overlaps(range) }

            scenes += Scene(
                id = SceneId(ids.newId()),
                range = range,
                label = sceneTranscript.firstOrNull()?.text?.take(60)
                    ?: sceneOcr.firstOrNull()?.text?.take(60),
                summary = if (sceneTranscript.isEmpty()) null else sceneTranscript.joinToString(" ") { it.text },
                motionScore = averageMotion,
                changeScore = peakChange.coerceIn(0f, 1f),
                transcriptText = sceneTranscript.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.text },
                visibleText = sceneOcr.map { it.text }.distinct().take(5),
            )
        }

        // A scene cut inside a long shot is still a scene if the change was abrupt.
        val merged = mutableListOf<Scene>()
        scenes.forEach { scene ->
            val last = merged.lastOrNull()
            if (last != null &&
                scene.changeScore < SCENE_MERGE_THRESHOLD &&
                last.changeScore < SCENE_MERGE_THRESHOLD &&
                last.range.endMsExclusive == scene.range.startMs
            ) {
                merged[merged.lastIndex] = last.copy(
                    range = TimeRange(last.range.startMs, scene.range.endMsExclusive),
                    changeScore = maxOf(last.changeScore, scene.changeScore),
                    motionScore = (last.motionScore + scene.motionScore) / 2f,
                    summary = listOfNotNull(last.summary, scene.summary).joinToString(" ").ifBlank { null },
                    transcriptText = listOfNotNull(last.transcriptText, scene.transcriptText).joinToString(" ").ifBlank { null },
                    visibleText = (last.visibleText + scene.visibleText).distinct().take(5),
                )
            } else {
                merged += scene
            }
        }

        // Shots that no scene covers (short but distinct) are added back so the
        // model can still find them.
        shots.forEach { shot ->
            if (merged.none { it.range.contains(shot.timestampMs) }) {
                merged += Scene(
                    id = SceneId(ids.newId()),
                    range = TimeRange(shot.timestampMs, minOf(durationMs, shot.timestampMs + MIN_SCENE_MS)),
                    label = "Shot at ${formatTimestampMs(shot.timestampMs)}",
                    changeScore = shot.confidence,
                )
            }
        }

        return merged.sortedBy { it.range.startMs }
    }

    fun buildMotionSegments(signals: List<FrameSignal>, minDurationMs: Long = 300L): List<MotionSegment> {
        if (signals.isEmpty()) return emptyList()
        val segments = mutableListOf<MotionSegment>()
        var level = levelOf(signals.first().motion)
        var startMs = signals.first().timestampMs
        var values = mutableListOf(signals.first().motion)

        signals.drop(1).forEach { signal ->
            val next = levelOf(signal.motion)
            if (next != level) {
                segments += segment(startMs, signal.timestampMs, level, values)
                level = next
                startMs = signal.timestampMs
                values = mutableListOf(signal.motion)
            } else {
                values += signal.motion
            }
        }
        val lastTimestamp = signals.last().timestampMs + 1L
        segments += segment(startMs, lastTimestamp, level, values)

        return segments.filter { it.range.durationMs >= minDurationMs }.sortedBy { it.range.startMs }
    }

    fun buildVisualEvents(
        signals: List<FrameSignal>,
        shots: List<ShotBoundary>,
        ocr: List<OcrText>,
        ids: IdGenerator,
    ): List<VisualEvent> {
        val events = mutableListOf<VisualEvent>()

        shots.forEach { shot ->
            events += VisualEvent(
                id = EventId(ids.newId()),
                type = VisualEventType.SHOT_BOUNDARY,
                range = TimeRange(shot.timestampMs, shot.timestampMs + 1L),
                score = shot.confidence,
                label = "shot boundary",
            )
        }

        buildMotionSegments(signals, minDurationMs = 250L).forEach { segment ->
            val type = when (segment.level) {
                MotionLevel.HIGH -> VisualEventType.HIGH_MOTION
                MotionLevel.STATIC -> VisualEventType.STATIC_HOLD
                else -> null
            }
            if (type != null) {
                events += VisualEvent(
                    id = EventId(ids.newId()),
                    type = type,
                    range = segment.range,
                    score = segment.peakMotion,
                    label = segment.level.name.lowercase(),
                )
            }
        }

        // Motion onset: a sharp rise in motion, which is usually the start of an action.
        signals.zipWithNext { previous, current ->
            if (current.motion - previous.motion > 0.35f && current.motion > 0.45f) {
                events += VisualEvent(
                    id = EventId(ids.newId()),
                    type = VisualEventType.MOTION_ONSET,
                    range = TimeRange(previous.timestampMs, current.timestampMs + 1L),
                    score = current.motion,
                    label = "motion onset",
                )
            }
        }

        ocr.sortedBy { it.range.startMs }.forEach { text ->
            events += VisualEvent(
                id = EventId(ids.newId()),
                type = VisualEventType.TEXT_APPEARED,
                range = TimeRange(text.range.startMs, text.range.startMs + 1L),
                score = text.confidence,
                label = "text appeared",
            )
            events += VisualEvent(
                id = EventId(ids.newId()),
                type = VisualEventType.TEXT_DISAPPEARED,
                range = TimeRange(text.range.endMsExclusive, text.range.endMsExclusive + 1L),
                score = text.confidence,
                label = "text disappeared",
            )
        }

        // Brightness change between consecutive samples.
        signals.zipWithNext { previous, current ->
            if (kotlin.math.abs(current.lumaDelta - previous.lumaDelta) > 0.4f) {
                events += VisualEvent(
                    id = EventId(ids.newId()),
                    type = VisualEventType.BRIGHTNESS_CHANGE,
                    range = TimeRange(current.timestampMs, current.timestampMs + 1L),
                    score = current.lumaDelta,
                    label = "brightness change",
                )
            }
        }

        return events.sortedBy { it.range.startMs }
    }

    /**
     * Builds `BEFORE → ACTION → AFTER` packets around audio impacts and motion
     * onsets — the moments users actually ask for ("freeze when the phone hits
     * the floor").
     */
    fun buildEventPackets(
        signals: List<FrameSignal>,
        audioEvents: List<AudioEvent>,
        transcript: List<TranscriptSegment>,
        ids: IdGenerator,
        limit: Int = 24,
        windowBeforeMs: Long = 900L,
        windowAfterMs: Long = 700L,
    ): List<EventPacket> {
        val anchors = mutableListOf<Pair<Long, Float>>()
        audioEvents.filter { it.type == AudioEventType.IMPACT || it.type == AudioEventType.PEAK }
            .forEach { anchors += it.timestampMs to it.level }
        buildMotionSegments(signals, minDurationMs = 250L)
            .filter { it.level == MotionLevel.HIGH }
            .forEach { anchors += it.range.startMs + it.range.durationMs / 2 to it.peakMotion }

        val sorted = anchors.sortedByDescending { it.second }
        val used = mutableListOf<Long>()

        return sorted.mapNotNull { (apexMs, score) ->
            if (used.any { kotlin.math.abs(it - apexMs) < windowBeforeMs }) return@mapNotNull null
            used += apexMs

            val beforeStart = (apexMs - windowBeforeMs).coerceAtLeast(0L)
            val afterEnd = apexMs + windowAfterMs

            val beforeMotion = motionBetween(signals, beforeStart, apexMs)
            val afterMotion = motionBetween(signals, apexMs, afterEnd)
            val speech = transcript
                .filter { it.range.overlaps(TimeRange(beforeStart, afterEnd)) }
                .joinToString(" ") { it.text.trim() }
                .takeIf { it.isNotBlank() }
            val impact = audioEvents
                .filter { it.type == AudioEventType.IMPACT && kotlin.math.abs(it.timestampMs - apexMs) < windowAfterMs }
                .maxByOrNull { it.level }

            EventPacket(
                id = EventId(ids.newId()),
                startMs = beforeStart,
                apexMs = apexMs,
                endMs = afterEnd,
                before = describe(beforeMotion, speech, before = true),
                action = describeImpact(impact, beforeMotion, afterMotion),
                after = describe(afterMotion, speech, before = false),
                audio = impact,
                speech = speech,
                score = score.coerceIn(0f, 1f),
                confidence = if (impact != null) 0.8f else 0.5f,
            )
        }.take(limit).sortedBy { it.apexMs }
    }

    private fun describeImpact(impact: AudioEvent?, beforeMotion: Float, afterMotion: Float): String = when {
        impact != null && afterMotion > beforeMotion -> "sudden loud impact with accelerating motion"
        impact != null -> "sudden loud impact"
        afterMotion > beforeMotion + 0.25f -> "rapid increase in visual motion"
        else -> "visual change"
    }

    private fun describe(motion: Float, speech: String?, before: Boolean): String = when {
        motion >= 0.6f -> if (before) "fast motion leading in" else "fast motion continuing"
        motion >= 0.25f -> if (before) "moderate movement" else "movement settling"
        speech != null -> if (before) "talking, little movement" else "talking resumes"
        else -> if (before) "still and quiet" else "quiet again"
    }

    private fun motionBetween(signals: List<FrameSignal>, startMs: Long, endMs: Long): Float {
        val inWindow = signals.filter { it.timestampMs in startMs..endMs }
        return if (inWindow.isEmpty()) 0f else inWindow.map { it.motion }.average().toFloat()
    }

    private fun segment(startMs: Long, endMs: Long, level: MotionLevel, values: List<Float>): MotionSegment =
        MotionSegment(
            range = TimeRange(startMs, maxOf(endMs, startMs + 1L)),
            level = level,
            averageMotion = if (values.isEmpty()) 0f else values.average().toFloat(),
            peakMotion = values.maxOrNull() ?: 0f,
        )

    private fun levelOf(motion: Float): MotionLevel = when {
        motion < 0.08f -> MotionLevel.STATIC
        motion < 0.25f -> MotionLevel.LOW
        motion < 0.55f -> MotionLevel.MEDIUM
        else -> MotionLevel.HIGH
    }
}
