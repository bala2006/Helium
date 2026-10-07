package com.sekhar.helium.media.indexer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.sekhar.helium.core.common.AppDispatchers
import com.sekhar.helium.core.common.IdGenerator
import com.sekhar.helium.core.common.TimeProvider
import com.sekhar.helium.core.database.SemanticMemoryStore
import com.sekhar.helium.core.model.AnalysisStage
import com.sekhar.helium.core.model.Keyframe
import com.sekhar.helium.core.model.KeyframeId
import com.sekhar.helium.core.model.KeyframeQuality
import com.sekhar.helium.core.model.SceneId
import com.sekhar.helium.core.model.SemanticVideoMemory
import com.sekhar.helium.core.model.SourceMedia
import com.sekhar.helium.core.model.StripId
import com.sekhar.helium.core.model.TemporalStrip
import com.sekhar.helium.core.model.TimeRange
import com.sekhar.helium.core.model.VideoMetadata
import com.sekhar.helium.core.model.formatTimestampMs
import com.sekhar.helium.media.indexer.analysis.AudioAnalyzer
import com.sekhar.helium.media.indexer.analysis.FrameSignal
import com.sekhar.helium.media.indexer.analysis.InformationDensity
import com.sekhar.helium.media.indexer.analysis.SceneBuilder
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Builds the Semantic Video Memory for a source.
 *
 * Design constraints that come straight from the product requirements:
 *
 * * **Progressive.** Each stage is published and persisted as soon as it
 *   finishes, so the editor is usable while indexing continues and a process
 *   death only loses the stage in flight.
 * * **Cached.** A source is fingerprinted, and a cached index is reused, so the
 *   same video is never analysed twice.
 * * **Bounded.** Sampling is driven by information change with hard caps on
 *   frames, keyframes and strips, so a 30-minute 4K file behaves the same as a
 *   15-second one.
 * * **Local.** The original media is never modified, and nothing here uploads
 *   anything.
 */
class SemanticIndexer(
    private val context: Context,
    private val extractor: MediaSignalExtractor,
    private val store: SemanticMemoryStore,
    private val transcriptionProvider: TranscriptionProvider,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val dispatchers: AppDispatchers,
) {

    /** Returns a cached index for [source], or `null` when it must be built. */
    suspend fun cached(source: SourceMedia): SemanticVideoMemory? = store.load(source.fingerprint)

    /**
     * Indexes [source], reporting each completed stage.
     *
     * @param onStage called with the stage that just finished and overall progress.
     */
    suspend fun index(
        source: SourceMedia,
        onStage: (AnalysisStage, Float) -> Unit = { _, _ -> },
    ): SemanticVideoMemory = withContext(dispatchers.default) {
        store.load(source.fingerprint)?.let { return@withContext it }

        val uri = Uri.parse(source.uri)
        val stages = linkedSetOf<AnalysisStage>()
        var memory = SemanticVideoMemory(
            sourceId = source.id,
            fingerprint = source.fingerprint,
            metadata = source.metadata,
        )

        suspend fun publish(stage: AnalysisStage, progress: Float, updated: SemanticVideoMemory) {
            stages += stage
            memory = updated.copy(stages = stages.toSet(), builtAtEpochMs = time.nowEpochMs())
            store.save(memory)
            onStage(stage, progress)
        }

        // --- metadata -----------------------------------------------------------
        val metadata: VideoMetadata = if (source.metadata.durationMs > 0L) {
            source.metadata
        } else {
            runCatching { extractor.readMetadata(uri) }.getOrElse { source.metadata }
        }
        publish(
            AnalysisStage.METADATA,
            0.05f,
            memory.copy(metadata = metadata),
        )

        if (metadata.durationMs <= 0L) {
            // Unreadable media: publish what we have rather than throwing, so the
            // project still opens and the UI can explain the problem.
            publish(AnalysisStage.COMPLETE, 1f, memory.copy(metadata = metadata))
            return@withContext memory
        }

        // --- storyboard: information-driven sampling ---------------------------
        val proxyDir = proxyDirectory(source)
        val coarseTimestamps = coarseSampleTimestamps(metadata.durationMs)
        val coarseFrames = runCatching { extractor.extractFrames(uri, coarseTimestamps) }
            .getOrDefault(emptyList())
        val coarseCurve = InformationDensity.computeCurve(coarseFrames.map { it.signal })

        val denseTimestamps = denseSampleTimestamps(coarseFrames.map { it.signal }, coarseCurve, metadata.durationMs)
        val denseFrames = runCatching {
            extractor.extractFrames(uri, denseTimestamps.filterNot { it in coarseTimestamps })
        }.getOrDefault(emptyList())

        val frames = (coarseFrames + denseFrames).sortedBy { it.timestampMs }
        val signals = frames.map { it.signal }
        val curve = InformationDensity.computeCurve(signals)

        publish(
            AnalysisStage.STORYBOARD,
            0.35f,
            memory.copy(informationCurve = curve),
        )

        // --- shots, scenes, motion, visual events ------------------------------
        val shots = SceneBuilder.buildShots(signals)
        val transcript = if (transcriptionProvider.isAvailable) {
            runCatching { transcriptionProvider.transcribe(source) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val scenes = SceneBuilder.buildScenes(
            signals = signals,
            shots = shots,
            transcript = transcript,
            ocr = emptyList(),
            ids = ids,
            durationMs = metadata.durationMs,
        )
        val motionSegments = SceneBuilder.buildMotionSegments(signals)
        val visualEvents = SceneBuilder.buildVisualEvents(signals, shots, emptyList(), ids)

        // Keyframes and strips are written once we know which scenes matter.
        val keyframes = writeKeyframes(frames, scenes.map { it.range }, proxyDir, metadata)
        val strips = writeStrips(frames, scenes, proxyDir)
        val scenesWithRefs = scenes.mapIndexed { index, scene ->
            scene.copy(
                id = scene.id.takeIf { it.value.isNotBlank() } ?: SceneId(ids.newId()),
                keyframeIds = keyframes.filter { scene.range.contains(it.timestampMs) }.map { it.id }.take(3),
                stripId = strips.getOrNull(index)?.id,
            )
        }

        publish(
            AnalysisStage.SHOTS,
            0.55f,
            memory.copy(
                shots = shots,
                scenes = scenesWithRefs,
                motionSegments = motionSegments,
                visualEvents = visualEvents,
                keyframes = keyframes,
                strips = strips,
            ),
        )

        // --- audio --------------------------------------------------------------
        val pcm = runCatching { extractor.decodePcm(uri) }.getOrElse { PcmAudio(FloatArray(0), 0) }
        val audio = if (pcm.samples.isNotEmpty()) {
            AudioAnalyzer.analyse(pcm.samples, pcm.sampleRateHz, ids)
        } else {
            com.sekhar.helium.media.indexer.analysis.AudioAnalysis()
        }
        publish(
            AnalysisStage.AUDIO,
            0.7f,
            memory.copy(
                audioEvents = audio.events,
                silenceRegions = audio.silences,
                speechRegions = audio.speechRegions,
                musicRegions = audio.musicRegions,
            ),
        )

        if (transcript.isNotEmpty()) {
            publish(AnalysisStage.TRANSCRIPT, 0.8f, memory.copy(transcript = transcript))
        }

        // --- events -------------------------------------------------------------
        val packets = SceneBuilder.buildEventPackets(
            signals = signals,
            audioEvents = audio.events,
            transcript = transcript,
            ids = ids,
        )

        publish(
            AnalysisStage.EVENTS,
            0.95f,
            memory.copy(eventPackets = packets),
        )

        val complete = memory.copy(stages = (stages + AnalysisStage.COMPLETE).toSet())
        store.save(complete)
        onStage(AnalysisStage.COMPLETE, 1f)

        frames.forEach { runCatching { it.bitmap.recycle() } }
        complete
    }

    // -----------------------------------------------------------------------------

    private fun coarseSampleTimestamps(durationMs: Long): List<Long> {
        val step = maxOf(MIN_COARSE_STEP_MS, durationMs / MAX_COARSE_SAMPLES)
        val timestamps = mutableListOf<Long>()
        var timestamp = 0L
        while (timestamp < durationMs && timestamps.size < MAX_COARSE_SAMPLES) {
            timestamps += timestamp
            timestamp += step
        }
        if (timestamps.lastOrNull() != durationMs - 1L) timestamps += (durationMs - 1L).coerceAtLeast(0L)
        return timestamps.distinct()
    }

    /** Adds dense coverage around the highest-information moments. */
    private fun denseSampleTimestamps(
        signals: List<FrameSignal>,
        curve: List<com.sekhar.helium.core.model.InformationSample>,
        durationMs: Long,
    ): List<Long> {
        if (curve.isEmpty()) return emptyList()
        val hotspots = curve.sortedByDescending { it.score }.take(HOTSPOTS)
        val timestamps = mutableListOf<Long>()
        hotspots.forEach { hotspot ->
            val start = (hotspot.timestampMs - DENSE_WINDOW_MS / 2).coerceAtLeast(0L)
            var offset = 0L
            while (offset < DENSE_WINDOW_MS && timestamps.size < MAX_DENSE_SAMPLES) {
                val candidate = start + offset
                if (candidate < durationMs) timestamps += candidate
                offset += DENSE_STEP_MS
            }
        }
        return timestamps.distinct().sorted()
    }

    private fun writeKeyframes(
        frames: List<ProxyFrame>,
        sceneRanges: List<TimeRange>,
        proxyDir: File,
        metadata: VideoMetadata,
    ): List<Keyframe> {
        if (frames.isEmpty()) return emptyList()
        val selected = linkedSetOf<ProxyFrame>()
        sceneRanges.forEach { range ->
            frames.firstOrNull { range.contains(it.timestampMs) }?.let { selected += it }
        }
        // Highest-change frames still matter, even inside a long scene.
        frames.sortedByDescending { it.signal.motion }.take(KEYFRAME_BUDGET / 2).forEach { selected += it }
        frames.firstOrNull()?.let { selected += it }
        frames.lastOrNull()?.let { selected += it }

        return selected.take(KEYFRAME_BUDGET).mapNotNull { frame ->
            val file = File(proxyDir, "kf-${frame.timestampMs}.jpg")
            if (!writeJpeg(frame.bitmap, file, KEYFRAME_QUALITY)) return@mapNotNull null
            Keyframe(
                id = KeyframeId("kf-${frame.timestampMs}"),
                timestampMs = frame.timestampMs,
                path = file.absolutePath,
                quality = KeyframeQuality.PROXY,
                width = frame.bitmap.width,
                height = frame.bitmap.height,
                perceptualHash = frame.signal.perceptualHash,
                motionScore = frame.signal.motion,
            )
        }
    }

    /**
     * Builds one contact sheet per scene that contains motion.
     *
     * Density follows the scene's own information score: a static shot gets a
     * single frame, a complex scene up to eight, so the sheet stays readable and
     * the model can compare several temporal states in one image.
     */
    private fun writeStrips(
        frames: List<ProxyFrame>,
        scenes: List<com.sekhar.helium.core.model.Scene>,
        proxyDir: File,
    ): List<TemporalStrip> {
        if (frames.isEmpty()) return emptyList()
        val strips = mutableListOf<TemporalStrip>()
        scenes.take(STRIP_BUDGET).forEach { scene ->
            val inScene = frames.filter { scene.range.contains(it.timestampMs) }
            if (inScene.size < 2) return@forEach

            val recentChange = inScene.maxOf { maxOf(it.signal.lumaDelta, it.signal.histogramDelta) }
            val density = InformationDensity.stripDensity(scene.changeScore, recentChange)
            if (density <= 1) return@forEach

            val selected = InformationDensity
                .selectSampleTimestamps(inScene.map { it.signal }, maxSamples = density)
                .mapNotNull { timestamp -> inScene.firstOrNull { it.timestampMs == timestamp } }
            if (selected.size < 2) return@forEach

            val columns = if (selected.size <= 4) selected.size else 4
            val rows = (selected.size + columns - 1) / columns
            val cellWidth = STRIP_CELL_WIDTH
            val cellHeight = (cellWidth * 9) / 16
            val sheet = Bitmap.createBitmap(columns * cellWidth, rows * cellHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val labelPaint = Paint().apply {
                color = Color.WHITE
                textSize = 12f
                isAntiAlias = true
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
            }

            selected.forEachIndexed { index, frame ->
                val column = index % columns
                val row = index / columns
                val destination = android.graphics.Rect(
                    column * cellWidth,
                    row * cellHeight,
                    (column + 1) * cellWidth,
                    (row + 1) * cellHeight,
                )
                canvas.drawBitmap(frame.bitmap, null, destination, paint)
                canvas.drawText(
                    formatTimestampMs(frame.timestampMs),
                    destination.left + 6f,
                    destination.bottom - 6f,
                    labelPaint,
                )
            }

            val file = File(proxyDir, "strip-${scene.range.startMs}.jpg")
            if (writeJpeg(sheet, file, STRIP_QUALITY)) {
                strips += TemporalStrip(
                    id = StripId("strip-${scene.range.startMs}"),
                    sceneId = scene.id,
                    startMs = scene.range.startMs,
                    endMs = scene.range.endMsExclusive,
                    frameTimestamps = selected.map { it.timestampMs },
                    path = file.absolutePath,
                    columns = columns,
                    rows = rows,
                    frameWidth = cellWidth,
                    frameHeight = cellHeight,
                    labels = selected.map { formatTimestampMs(it.timestampMs) },
                )
            }
            sheet.recycle()
        }
        return strips
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int): Boolean = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
    }.isSuccess

    private fun proxyDirectory(source: SourceMedia): File =
        File(context.cacheDir, "semantic/${source.fingerprint.take(16)}").apply { mkdirs() }

    /** Removes every cached artefact for a source; used when a project is deleted. */
    fun clearCache(source: SourceMedia) {
        runCatching { proxyDirectory(source).deleteRecursively() }
    }

    private companion object {
        const val MAX_COARSE_SAMPLES = 300
        const val MIN_COARSE_STEP_MS = 250L
        const val HOTSPOTS = 12
        const val DENSE_WINDOW_MS = 1_200L
        const val DENSE_STEP_MS = 150L
        const val MAX_DENSE_SAMPLES = 200

        const val KEYFRAME_BUDGET = 120
        const val STRIP_BUDGET = 40
        const val STRIP_CELL_WIDTH = 200

        const val KEYFRAME_QUALITY = 78
        const val STRIP_QUALITY = 72
    }
}
