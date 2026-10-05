package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/** Bumped whenever the semantic index layout changes, invalidating old caches. */
const val SEMANTIC_INDEX_VERSION: Int = 1

/**
 * Progressive analysis stages.
 *
 * The editor is usable before indexing finishes: each stage stores its results
 * as soon as it completes, and the retrieval tools simply report what is
 * available. This keeps "video playable quickly" ahead of "richer semantic
 * analysis".
 */
@Serializable
enum class AnalysisStage(val displayName: String) {
    METADATA("Reading media info"),
    PROXY("Building editing proxy"),
    STORYBOARD("Generating storyboard"),
    SHOTS("Detecting shots and scenes"),
    AUDIO("Analysing audio"),
    TRANSCRIPT("Transcribing speech"),
    OCR("Reading on-screen text"),
    EVENTS("Detecting events"),
    COMPLETE("Ready"),
}

/** A detected shot boundary. */
@Serializable
data class ShotBoundary(
    val id: ShotId,
    val timestampMs: Long,
    val confidence: Float,
)

/** A semantic scene: a coherent run of shots the user would describe as one moment. */
@Serializable
data class Scene(
    val id: SceneId,
    val range: TimeRange,
    val label: String? = null,
    val summary: String? = null,
    val motionScore: Float = 0f,
    val changeScore: Float = 0f,
    val keyframeIds: List<KeyframeId> = emptyList(),
    val stripId: StripId? = null,
    val transcriptText: String? = null,
    val visibleText: List<String> = emptyList(),
)

/** A transcript segment with optional word-level timings. */
@Serializable
data class TranscriptSegment(
    val range: TimeRange,
    val text: String,
    val words: List<WordTiming> = emptyList(),
    val confidence: Float = 1f,
    val speaker: String? = null,
)

/** Text recognised on screen. */
@Serializable
data class OcrText(
    val id: String,
    val range: TimeRange,
    val text: String,
    val confidence: Float = 1f,
    val region: NormalizedRect? = null,
)

/** Kinds of audio event the local analyser produces. */
@Serializable
enum class AudioEventType {
    SILENCE,
    SPEECH_START,
    SPEECH_END,
    MUSIC,
    IMPACT,
    PEAK,
    LOUDNESS_RISE,
    LOUDNESS_FALL,
}

/** A timestamped audio observation. */
@Serializable
data class AudioEvent(
    val id: EventId,
    val type: AudioEventType,
    val timestampMs: Long,
    val level: Float = 0f,
    val durationMs: Long = 0L,
    val label: String? = null,
)

/** Kinds of visual event the local analyser produces. */
@Serializable
enum class VisualEventType {
    SCENE_CHANGE,
    SHOT_BOUNDARY,
    HIGH_MOTION,
    MOTION_ONSET,
    STATIC_HOLD,
    TEXT_APPEARED,
    TEXT_DISAPPEARED,
    FACE_PRESENT,
    BRIGHTNESS_CHANGE,
}

/** A timestamped visual observation. */
@Serializable
data class VisualEvent(
    val id: EventId,
    val type: VisualEventType,
    val range: TimeRange,
    val score: Float,
    val label: String? = null,
)

/**
 * `BEFORE → ACTION → AFTER` packet.
 *
 * This is the representation that makes temporal reasoning cheap for the LLM:
 * instead of guessing a timestamp from raw frames, the model reads a described
 * event with an apex timestamp and supporting evidence references, then drills
 * down with `inspect_segment` / `get_frame` only when it needs more confidence.
 */
@Serializable
data class EventPacket(
    val id: EventId,
    val startMs: Long,
    val apexMs: Long,
    val endMs: Long,
    val before: String,
    val action: String,
    val after: String,
    val audio: AudioEvent? = null,
    val speech: String? = null,
    val frameRefs: List<KeyframeId> = emptyList(),
    val score: Float = 0f,
    val confidence: Float = 0.5f,
) {
    val range: TimeRange get() = TimeRange(startMs, maxOf(endMs, startMs))
}

/** Where a cached frame image came from, which drives the privacy decision. */
@Serializable
enum class KeyframeQuality { PROXY, ORIGINAL }

/** A single cached still. */
@Serializable
data class Keyframe(
    val id: KeyframeId,
    val timestampMs: Long,
    val path: String,
    val quality: KeyframeQuality = KeyframeQuality.PROXY,
    val width: Int = 0,
    val height: Int = 0,
    /** 64-bit average hash for cheap visual deduplication. */
    val perceptualHash: Long = 0L,
    val motionScore: Float = 0f,
)

/**
 * A contact sheet containing several temporal states inside one image.
 *
 * [labels] holds the `mm:ss.S` label for each cell in reading order so the model
 * can reference exact timestamps without any extra round trip.
 */
@Serializable
data class TemporalStrip(
    val id: StripId,
    val sceneId: SceneId?,
    val startMs: Long,
    val endMs: Long,
    val frameTimestamps: List<Long>,
    val path: String,
    val columns: Int,
    val rows: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val labels: List<String> = emptyList(),
)

/** One sample of the information-density curve. */
@Serializable
data class InformationSample(
    val timestampMs: Long,
    val score: Float,
    val lumaDelta: Float = 0f,
    val histogramDelta: Float = 0f,
    val motion: Float = 0f,
    val audioRms: Float = 0f,
)

/** Coarse motion classification for a range. */
@Serializable
enum class MotionLevel { STATIC, LOW, MEDIUM, HIGH }

/** A range classified by how much visual motion it contains. */
@Serializable
data class MotionSegment(
    val range: TimeRange,
    val level: MotionLevel,
    val averageMotion: Float,
    val peakMotion: Float,
)

/** Extensible person track (populated by optional on-device face analysis). */
@Serializable
data class FaceSample(
    val timestampMs: Long,
    val region: NormalizedRect,
    val confidence: Float,
)

@Serializable
data class FaceTrack(
    val id: String,
    val range: TimeRange,
    val label: String? = null,
    val confidence: Float = 0.5f,
    val samples: List<FaceSample> = emptyList(),
)

/** Extensible object track. */
@Serializable
data class ObjectSample(
    val timestampMs: Long,
    val region: NormalizedRect,
    val confidence: Float,
)

@Serializable
data class ObjectTrack(
    val id: String,
    val label: String,
    val range: TimeRange,
    val confidence: Float = 0.5f,
    val samples: List<ObjectSample> = emptyList(),
)

/** Level 1 summary handed to the model for every request. */
@Serializable
data class Chapter(
    val range: TimeRange,
    val title: String,
    val summary: String,
)

/** A high-salience moment, ranked for the overview. */
@Serializable
data class EventSummary(
    val id: EventId,
    val timestampMs: Long,
    val label: String,
    val score: Float,
)

/** Compact, always-sent description of a source. */
@Serializable
data class VideoOverview(
    val sourceId: SourceId,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val orientation: Orientation,
    val hasAudio: Boolean,
    val sceneCount: Int,
    val speechSummary: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val majorEvents: List<EventSummary> = emptyList(),
    val storyboardPaths: List<String> = emptyList(),
)

/** A text search hit with an exact location. */
@Serializable
data class TextMatch(
    val range: TimeRange,
    val text: String,
    val score: Float,
    val source: TextMatchSource,
)

@Serializable
enum class TextMatchSource { SPEECH, ON_SCREEN }

/**
 * The local Semantic Video Memory for one source.
 *
 * Everything here is produced on-device from cheap signals (luma deltas,
 * histograms, audio RMS, MediaCodec frame pulls). No cloud vision preprocessing
 * is required and the original video is never modified.
 */
@Serializable
data class SemanticVideoMemory(
    val sourceId: SourceId,
    val fingerprint: String,
    val metadata: VideoMetadata,
    val stages: Set<AnalysisStage> = emptySet(),
    val shots: List<ShotBoundary> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val transcript: List<TranscriptSegment> = emptyList(),
    val ocr: List<OcrText> = emptyList(),
    val audioEvents: List<AudioEvent> = emptyList(),
    val visualEvents: List<VisualEvent> = emptyList(),
    val eventPackets: List<EventPacket> = emptyList(),
    val keyframes: List<Keyframe> = emptyList(),
    val strips: List<TemporalStrip> = emptyList(),
    val informationCurve: List<InformationSample> = emptyList(),
    val motionSegments: List<MotionSegment> = emptyList(),
    val silenceRegions: List<TimeRange> = emptyList(),
    val speechRegions: List<TimeRange> = emptyList(),
    val musicRegions: List<TimeRange> = emptyList(),
    val faceTracks: List<FaceTrack> = emptyList(),
    val objectTracks: List<ObjectTrack> = emptyList(),
    val builtAtEpochMs: Long = 0L,
    val indexVersion: Int = SEMANTIC_INDEX_VERSION,
) {

    fun isReady(stage: AnalysisStage): Boolean = stage in stages

    val fullTranscript: String
        get() = transcript.joinToString(" ") { it.text.trim() }.trim()

    fun scene(sceneId: SceneId): Scene? = scenes.firstOrNull { it.id == sceneId }

    fun sceneAt(timestampMs: Long): Scene? = scenes.firstOrNull { it.range.contains(timestampMs) }

    fun keyframe(keyframeId: KeyframeId): Keyframe? = keyframes.firstOrNull { it.id == keyframeId }

    fun strip(stripId: StripId): TemporalStrip? = strips.firstOrNull { it.id == stripId }

    /** Transcript segments overlapping [range], in chronological order. */
    fun transcriptIn(range: TimeRange): List<TranscriptSegment> =
        transcript.filter { it.range.overlaps(range) }.sortedBy { it.range.startMs }

    /** Concatenated transcript text for [range]. */
    fun transcriptTextIn(range: TimeRange): String =
        transcriptIn(range).joinToString(" ") { it.text.trim() }.trim()

    fun transcriptInRange(startMs: Long, endMs: Long): List<TranscriptSegment> =
        transcriptIn(TimeRange(startMs, maxOf(endMs, startMs)))

    fun audioEventsIn(range: TimeRange): List<AudioEvent> =
        audioEvents.filter { range.contains(it.timestampMs) }.sortedBy { it.timestampMs }

    fun visualEventsIn(range: TimeRange): List<VisualEvent> =
        visualEvents.filter { it.range.overlaps(range) }.sortedBy { it.range.startMs }

    /** Case-insensitive search over spoken words. */
    fun findSpokenText(query: String): List<TextMatch> =
        searchText(query, transcript.map { Triple(it.range, it.text, it.confidence) }, TextMatchSource.SPEECH)

    /** Case-insensitive search over recognised on-screen text. */
    fun findVisibleText(query: String): List<TextMatch> =
        searchText(query, ocr.map { Triple(it.range, it.text, it.confidence) }, TextMatchSource.ON_SCREEN)

    private fun searchText(
        query: String,
        entries: List<Triple<TimeRange, String, Float>>,
        source: TextMatchSource,
    ): List<TextMatch> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return entries.mapNotNull { (range, text, confidence) ->
            val haystack = text.lowercase()
            val index = haystack.indexOf(needle)
            if (index < 0) return@mapNotNull null
            // Prefer whole-word matches, and matches that start early in the segment.
            val wholeWord = haystack == needle || haystack.contains(" $needle ") ||
                haystack.startsWith("$needle ") || haystack.endsWith(" $needle")
            val score = (if (wholeWord) 1f else 0.7f) * confidence
            TextMatch(range = range, text = text, score = score, source = source)
        }.sortedByDescending { it.score }
    }

    /** Silence regions at least [minDurationMs] long. */
    fun silences(minDurationMs: Long = 400L): List<TimeRange> =
        silenceRegions.filter { it.durationMs >= minDurationMs }.sortedBy { it.startMs }

    fun speechSegments(): List<TimeRange> = speechRegions.sortedBy { it.startMs }

    /** Motion segments at or above [level]. */
    fun motionAtLeast(level: MotionLevel): List<MotionSegment> =
        motionSegments.filter { it.level.ordinal >= level.ordinal && it.level != MotionLevel.STATIC }

    /** Highest-motion segments, most motion first. */
    fun highMotionSegments(limit: Int = 10): List<MotionSegment> =
        motionSegments.sortedByDescending { it.peakMotion }.take(limit)

    /** Interpolated information score at [timestampMs]. */
    fun informationScoreAt(timestampMs: Long): Float {
        if (informationCurve.isEmpty()) return 0f
        val first = informationCurve.first()
        if (timestampMs <= first.timestampMs) return first.score
        val last = informationCurve.last()
        if (timestampMs >= last.timestampMs) return last.score
        var previous = first
        for (sample in informationCurve) {
            if (sample.timestampMs >= timestampMs) {
                val span = (sample.timestampMs - previous.timestampMs).toFloat()
                if (span <= 0f) return sample.score
                val t = (timestampMs - previous.timestampMs).toFloat() / span
                return previous.score + (sample.score - previous.score) * t
            }
            previous = sample
        }
        return last.score
    }

    /**
     * Ranks the most "interesting" moments: information density, motion peaks and
     * audio impacts. Used to propose a hook for "start with the most interesting
     * moment" without any extra model round trip.
     */
    fun mostInterestingMoments(limit: Int = 5, windowMs: Long = 1200L): List<TimeRange> {
        if (informationCurve.isEmpty() && motionSegments.isEmpty()) return emptyList()
        val candidates = mutableListOf<Pair<Double, TimeRange>>()
        motionSegments.forEach { seg ->
            val score = seg.peakMotion * 1.0
            candidates += score to seg.range
        }
        audioEvents.filter { it.type == AudioEventType.IMPACT || it.type == AudioEventType.PEAK }
            .forEach { event ->
                val start = maxOf(0L, event.timestampMs - windowMs / 2)
                candidates += (1.0 + event.level) to TimeRange(start, start + windowMs)
            }
        informationCurve.sortedByDescending { it.score }.take(limit * 3).forEach { sample ->
            val start = maxOf(0L, sample.timestampMs - windowMs / 2)
            candidates += (sample.score.toDouble() * 1.2) to TimeRange(start, start + windowMs)
        }
        return candidates.sortedByDescending { it.first }
            .map { it.second.clampTo(TimeRange(0L, maxOf(metadata.durationMs, 1L))) }
            .filter { it.durationMs > 0 }
            .distinctBy { it.startMs / windowMs }
            .take(limit)
    }

    /** Level 1 overview used for the first model turn. */
    fun toOverview(): VideoOverview = VideoOverview(
        sourceId = sourceId,
        durationMs = metadata.durationMs,
        width = metadata.displayWidth,
        height = metadata.displayHeight,
        orientation = metadata.orientation,
        hasAudio = metadata.hasAudio,
        sceneCount = scenes.size,
        speechSummary = fullTranscript.takeIf { it.isNotEmpty() }?.take(600),
        chapters = scenes.take(24).map { scene ->
            Chapter(
                range = scene.range,
                title = scene.label ?: "Scene at ${formatTimestampMs(scene.range.startMs)}",
                summary = scene.summary ?: scene.transcriptText.orEmpty(),
            )
        },
        majorEvents = eventPackets.sortedByDescending { it.score }.take(12).map {
            EventSummary(
                id = it.id,
                timestampMs = it.apexMs,
                label = it.action,
                score = it.score,
            )
        },
        storyboardPaths = strips.take(6).map { it.path },
    )
}
