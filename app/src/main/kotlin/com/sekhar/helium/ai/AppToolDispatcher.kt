package com.sekhar.helium.ai

import android.graphics.BitmapFactory
import android.util.Base64
import com.sekhar.helium.ai.agent.AgentPrompts
import com.sekhar.helium.ai.agent.AgentToolDispatcher
import com.sekhar.helium.ai.tools.EvidenceImage
import com.sekhar.helium.ai.tools.ToolCallRequest
import com.sekhar.helium.ai.tools.ToolNames
import com.sekhar.helium.ai.tools.ToolResultPayload
import com.sekhar.helium.core.common.HeliumJson
import com.sekhar.helium.core.common.HeliumLog
import com.sekhar.helium.core.model.AiModelConfig
import com.sekhar.helium.core.model.CloudAnalysisMode
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.SemanticVideoMemory
import com.sekhar.helium.core.model.formatDurationMs
import com.sekhar.helium.core.model.formatTimestampMs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Answers the model's evidence questions from data that is already on the device.
 *
 * This is the retrieval half of the semantic codec: level 1 is the cached
 * overview, level 2 is scene detail, level 3 is a narrow set of proxy frames and
 * level 4 is a full-resolution frame. Each level is only reachable if the model
 * explicitly asks for it, and level 4 is additionally blocked unless the user
 * chose `FULL` cloud analysis.
 *
 * Only text leaves the device by default; images are attached only for the
 * tools that return them, and never in `MINIMIZE` mode.
 */
class AppToolDispatcher(
    private val project: Project,
    private val memories: Map<String, SemanticVideoMemory>,
    private val config: AiModelConfig,
    private val log: HeliumLog,
) : AgentToolDispatcher {

    override fun describe(call: ToolCallRequest): String = AgentPrompts.inspectionLabel(call.name)

    override suspend fun execute(call: ToolCallRequest): ToolResultPayload {
        val args = runCatching { HeliumJson.parseToJsonElement(call.argumentsJson) as JsonObject }
            .getOrElse { JsonObject(emptyMap()) }

        return try {
            when (call.name) {
                ToolNames.GET_PROJECT_STATE -> text(call, projectStateJson())
                ToolNames.GET_VIDEO_OVERVIEW -> withMemory(call, args) { text(call, overviewJson(it)) }
                ToolNames.SEARCH_VIDEO -> withMemory(call, args) { memory ->
                    val query = args.stringOrNull("semanticQuery").orEmpty()
                    text(call, searchJson(memory, query, args.intOrNull("limit") ?: 6))
                }
                ToolNames.GET_SCENE -> sceneTool(call, args)
                ToolNames.GET_TRANSCRIPT -> withMemory(call, args) { memory ->
                    val range = range(args, memory.metadata.durationMs)
                    text(call, transcriptJson(memory, range.startMs, range.endMsExclusive))
                }
                ToolNames.FIND_SPOKEN_TEXT -> withMemory(call, args) { memory ->
                    val query = args.stringOrNull("query").orEmpty()
                    text(call, matchesJson("speech", memory.findSpokenText(query)))
                }
                ToolNames.FIND_VISIBLE_TEXT -> withMemory(call, args) { memory ->
                    val query = args.stringOrNull("query").orEmpty()
                    text(call, matchesJson("on_screen", memory.findVisibleText(query)))
                }
                ToolNames.GET_AUDIO_EVENTS -> withMemory(call, args) { memory ->
                    val range = range(args, memory.metadata.durationMs)
                    text(call, audioEventsJson(memory, range.startMs, range.endMsExclusive))
                }
                ToolNames.GET_VISUAL_EVENTS -> withMemory(call, args) { memory ->
                    val range = range(args, memory.metadata.durationMs)
                    text(call, visualEventsJson(memory, range.startMs, range.endMsExclusive))
                }
                ToolNames.FIND_HIGH_MOTION_SEGMENTS -> withMemory(call, args) { memory ->
                    val limit = args.intOrNull("limit") ?: 10
                    text(call, motionJson(memory, limit))
                }
                ToolNames.FIND_SILENCE -> withMemory(call, args) { memory ->
                    val minDuration = args.longOrNull("minDurationMs") ?: 400L
                    text(call, silenceJson(memory, minDuration))
                }
                ToolNames.GET_TEMPORAL_STRIP -> stripTool(call, args)
                ToolNames.INSPECT_SEGMENT -> inspectTool(call, args)
                ToolNames.GET_FRAME -> frameTool(call, args, original = false)
                ToolNames.GET_ORIGINAL_FRAME -> frameTool(call, args, original = true)
                else -> ToolResultPayload.failure(call.callId, call.name, "Unknown tool ${call.name}")
            }
        } catch (failure: Throwable) {
            log.w(TAG, "Tool ${call.name} failed: ${failure::class.simpleName}")
            ToolResultPayload.failure(call.callId, call.name, "The tool could not complete: ${failure.message}")
        }
    }

    // -----------------------------------------------------------------------------
    // Text-level tools
    // -----------------------------------------------------------------------------

    private fun projectStateJson(): String {
        val state = ProjectStateDto(
            projectId = project.id.value,
            name = project.name,
            durationMs = project.timeline.durationMs,
            durationLabel = formatDurationMs(project.timeline.durationMs),
            aspectRatio = "${project.aspectRatio.widthUnits}:${project.aspectRatio.heightUnits}",
            appliedEdits = project.history.cursor,
            canUndo = project.history.canUndo,
            canRedo = project.history.canRedo,
            sources = project.sources.map { source ->
                SourceDto(
                    videoId = source.id.value,
                    name = source.displayName,
                    durationMs = source.metadata.durationMs,
                    durationLabel = formatDurationMs(source.metadata.durationMs),
                    width = source.metadata.displayWidth,
                    height = source.metadata.displayHeight,
                    orientation = source.metadata.orientation.name.lowercase(),
                    hasAudio = source.metadata.hasAudio,
                    indexed = memories[source.id.value] != null,
                )
            },
            clips = project.timeline.videoTracks.flatMap { track ->
                track.clips.map { clip ->
                    ClipDto(
                        clipId = clip.id.value,
                        trackId = track.id.value,
                        videoId = clip.sourceId.value,
                        timelineStartMs = clip.timelineStartMs,
                        timelineEndMs = clip.timelineEndMs,
                        sourceStartMs = clip.sourceRange.startMs,
                        sourceEndMs = clip.sourceRange.endMsExclusive,
                        speed = clip.speed,
                        effectCount = clip.effects.size,
                    )
                }
            },
            textItems = project.timeline.allTextItems().map {
                TextItemDto(
                    textItemId = it.id.value,
                    startMs = it.range.startMs,
                    endMs = it.range.endMsExclusive,
                    text = it.text,
                    isCaption = it.isCaption,
                )
            },
            appliedTransactions = project.history.appliedTransactions.map {
                TransactionDto(id = it.id, summary = it.summary, prompt = it.userPrompt)
            },
        )
        return HeliumJson.encodeToString(state)
    }

    private fun overviewJson(memory: SemanticVideoMemory): String {
        val overview = memory.toOverview()
        val dto = OverviewDto(
            videoId = overview.sourceId.value,
            durationMs = overview.durationMs,
            durationLabel = formatDurationMs(overview.durationMs),
            width = overview.width,
            height = overview.height,
            orientation = overview.orientation.name.lowercase(),
            hasAudio = overview.hasAudio,
            indexedStages = memory.stages.map { it.name },
            transcriptAvailable = memory.transcript.isNotEmpty(),
            speechSummary = overview.speechSummary,
            chapters = memory.scenes.take(24).map {
                ChapterDto(
                    sceneId = it.id.value,
                    startMs = it.range.startMs,
                    endMs = it.range.endMsExclusive,
                    label = it.label,
                    summary = it.summary,
                    motionScore = it.motionScore,
                    changeScore = it.changeScore,
                )
            },
            majorEvents = memory.eventPackets.sortedByDescending { it.score }.take(12).map {
                EventDto(
                    eventId = it.id.value,
                    startMs = it.startMs,
                    apexMs = it.apexMs,
                    endMs = it.endMs,
                    before = it.before,
                    action = it.action,
                    after = it.after,
                    speech = it.speech,
                    score = it.score,
                )
            },
        )
        return HeliumJson.encodeToString(dto)
    }

    private fun searchJson(memory: SemanticVideoMemory, query: String, limit: Int): String {
        val spoken = memory.findSpokenText(query)
        val visible = memory.findVisibleText(query)
        val keywords = query.lowercase().split(' ', ',', '.').filter { it.length > 3 }
        val scenes = memory.scenes
            .map { scene ->
                val haystack = listOfNotNull(scene.label, scene.summary, scene.transcriptText)
                    .joinToString(" ")
                    .lowercase()
                val keywordHits = keywords.count { haystack.contains(it) }
                val score = keywordHits * 0.5f + scene.changeScore * 0.3f + scene.motionScore * 0.2f
                scene to score
            }
            .filter { it.second > 0.15f }
            .sortedByDescending { it.second }
            .take(limit)

        val dto = SearchDto(
            query = query,
            speechMatches = spoken.take(limit).map { MatchDto(it.range.startMs, it.range.endMsExclusive, it.text, it.score) },
            onScreenMatches = visible.take(limit).map { MatchDto(it.range.startMs, it.range.endMsExclusive, it.text, it.score) },
            sceneCandidates = scenes.map { (scene, score) ->
                CandidateDto(
                    sceneId = scene.id.value,
                    startMs = scene.range.startMs,
                    endMs = scene.range.endMsExclusive,
                    label = scene.label,
                    transcript = scene.transcriptText,
                    visibleText = scene.visibleText,
                    score = score,
                )
            },
            notes = buildList {
                if (memory.transcript.isEmpty()) add("No transcript is available for this video, so speech cannot be searched.")
                if (memory.stages.contains(com.sekhar.helium.core.model.AnalysisStage.EVENTS).not()) {
                    add("Event detection has not finished yet; results may be incomplete.")
                }
            },
        )
        return HeliumJson.encodeToString(dto)
    }

    private fun sceneTool(call: ToolCallRequest, args: JsonObject): ToolResultPayload {
        val memory = memoryFor(args.stringOrNull("videoId")) ?: return unknownSource(call)
        val sceneId = args.stringOrNull("sceneId").orEmpty()
        val scene = memory.scenes.firstOrNull { it.id.value == sceneId }
            ?: return ToolResultPayload.failure(call.callId, call.name, "No scene with id $sceneId")
        val dto = SceneDto(
            sceneId = scene.id.value,
            startMs = scene.range.startMs,
            endMs = scene.range.endMsExclusive,
            label = scene.label,
            summary = scene.summary,
            transcript = memory.transcriptTextIn(scene.range),
            visibleText = scene.visibleText,
            motionScore = scene.motionScore,
            changeScore = scene.changeScore,
            keyframeIds = scene.keyframeIds.map { it.value },
            stripId = scene.stripId?.value,
        )
        return text(call, HeliumJson.encodeToString(dto))
    }

    private fun stripTool(call: ToolCallRequest, args: JsonObject): ToolResultPayload {
        val memory = memoryFor(args.stringOrNull("videoId")) ?: memories.values.firstOrNull()
        ?: return unknownSource(call)
        val sceneId = args.stringOrNull("sceneId")
        val strip = sceneId?.let { id ->
            val scene = memory.scenes.firstOrNull { it.id.value == id }
            scene?.stripId?.let { memory.strip(it) }
        } ?: memory.strips.firstOrNull()
        ?: return ToolResultPayload.failure(
            call.callId,
            call.name,
            "No storyboard strip is available yet. Use inspect_segment instead.",
        )
        val dto = StripDto(
            stripId = strip.id.value,
            startMs = strip.startMs,
            endMs = strip.endMs,
            frameTimestamps = strip.frameTimestamps,
            labels = strip.labels,
            columns = strip.columns,
            rows = strip.rows,
        )
        val image = imageFromFile(strip.path, strip.id.value, null, "proxy", memory.sourceId.value)
        return ToolResultPayload(
            callId = call.callId,
            name = call.name,
            ok = true,
            content = HeliumJson.encodeToString(dto),
            images = listOfNotNull(image),
        )
    }

    private fun inspectTool(call: ToolCallRequest, args: JsonObject): ToolResultPayload {
        val memory = memoryFor(args.stringOrNull("videoId")) ?: return unknownSource(call)
        val start = args.longOrNull("startMs") ?: 0L
        val end = args.longOrNull("endMs") ?: memory.metadata.durationMs
        val sampleCount = (args.intOrNull("sampleCount") ?: 6).coerceIn(2, 16)
        val range = safeRange(start, end, memory)
        val keyframes = memory.keyframes
            .filter { it.timestampMs in range.startMs..range.endMsExclusive }
            .sortedBy { it.timestampMs }
        if (keyframes.isEmpty()) {
            return ToolResultPayload.failure(
                call.callId,
                call.name,
                "No cached frames fall inside ${formatTimestampMs(start)}-${formatTimestampMs(end)}. " +
                    "Try a range that overlaps a scene from get_video_overview.",
            )
        }
        val step = (keyframes.size.toFloat() / sampleCount).coerceAtLeast(1f)
        val selected = (0 until sampleCount).mapNotNull { index ->
            keyframes.getOrNull((index * step).toInt().coerceAtMost(keyframes.lastIndex))
        }.distinctBy { it.id.value }

        val images = if (config.cloudAnalysisMode == CloudAnalysisMode.MINIMIZE) {
            emptyList()
        } else {
            selected.mapNotNull {
                imageFromFile(it.path, it.id.value, it.timestampMs, "proxy", memory.sourceId.value)
            }.take(config.maxImagesPerRequest)
        }
        val dto = InspectDto(
            startMs = range.startMs,
            endMs = range.endMsExclusive,
            frameTimestamps = selected.map { it.timestampMs },
            evidence = memory.transcriptTextIn(range).takeIf { it.isNotBlank() },
            imageCount = images.size,
            privacyNote = if (images.isEmpty() && config.cloudAnalysisMode == CloudAnalysisMode.MINIMIZE) {
                "Minimize cloud analysis is on, so no frames were attached. Rely on text evidence."
            } else {
                null
            },
        )
        return ToolResultPayload(call.callId, call.name, true, HeliumJson.encodeToString(dto), images)
    }

    private fun frameTool(call: ToolCallRequest, args: JsonObject, original: Boolean): ToolResultPayload {
        val memory = memoryFor(args.stringOrNull("videoId")) ?: return unknownSource(call)
        val timestampMs = args.longOrNull("timestampMs") ?: 0L
        if (original && config.cloudAnalysisMode != CloudAnalysisMode.FULL) {
            return ToolResultPayload.failure(
                call.callId,
                call.name,
                "Full-resolution inspection is disabled by the user's privacy setting. " +
                    "Use inspect_segment with proxy quality instead.",
            )
        }
        val keyframe = memory.keyframes.minByOrNull { kotlin.math.abs(it.timestampMs - timestampMs) }
            ?: return ToolResultPayload.failure(call.callId, call.name, "No cached frame is available.")
        val image = imageFromFile(
            path = keyframe.path,
            label = keyframe.id.value,
            timestampMs = timestampMs,
            quality = if (original) "original" else "proxy",
            sourceId = memory.sourceId.value,
        ) ?: return ToolResultPayload.failure(call.callId, call.name, "The frame could not be read.")
        return ToolResultPayload(
            callId = call.callId,
            name = call.name,
            ok = true,
            content = """{"timestampMs":$timestampMs,"quality":"${if (original) "original" else "proxy"}",""" +
                """"note":"Frames served by this build come from the cached proxy keyframe nearest the requested timestamp."}""",
            images = listOf(image),
        )
    }

    // -----------------------------------------------------------------------------
    // Payload helpers
    // -----------------------------------------------------------------------------

    private fun withMemory(
        call: ToolCallRequest,
        args: JsonObject,
        block: (SemanticVideoMemory) -> ToolResultPayload,
    ): ToolResultPayload {
        val memory = memoryFor(args.stringOrNull("videoId")) ?: return unknownSource(call)
        return block(memory)
    }

    private fun unknownSource(call: ToolCallRequest): ToolResultPayload = ToolResultPayload.failure(
        call.callId,
        call.name,
        "Unknown or un-indexed videoId. Call get_project_state to list the available sources.",
    )

    private fun memoryFor(videoId: String?): SemanticVideoMemory? =
        videoId?.let { memories[it] } ?: memories.values.firstOrNull()

    private fun text(call: ToolCallRequest, content: String) =
        ToolResultPayload(callId = call.callId, name = call.name, ok = true, content = content)

    private fun range(args: JsonObject, durationMs: Long) = safeRange(
        start = args.longOrNull("startMs") ?: 0L,
        end = args.longOrNull("endMs") ?: durationMs,
        memoryDurationMs = durationMs,
    )

    private fun safeRange(start: Long, end: Long, memory: SemanticVideoMemory) =
        safeRange(start, end, memory.metadata.durationMs)

    private fun safeRange(start: Long, end: Long, memoryDurationMs: Long): com.sekhar.helium.core.model.TimeRange {
        val clampedStart = start.coerceIn(0L, maxOf(0L, memoryDurationMs))
        val clampedEnd = end.coerceIn(clampedStart, maxOf(clampedStart, memoryDurationMs))
        return com.sekhar.helium.core.model.TimeRange(
            clampedStart,
            if (clampedEnd <= clampedStart) (clampedStart + 1L).coerceAtMost(maxOf(1L, memoryDurationMs)) else clampedEnd,
        )
    }

    private fun imageFromFile(
        path: String,
        label: String,
        timestampMs: Long?,
        quality: String,
        sourceId: String,
    ): EvidenceImage? {
        val file = File(path)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return EvidenceImage(
            label = label,
            timestampMs = timestampMs,
            quality = quality,
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = bounds.outWidth,
            height = bounds.outHeight,
            sourceId = sourceId,
        )
    }

    // -----------------------------------------------------------------------------
    // Wire DTOs — deliberately compact: every token here is a token the user pays for.
    // -----------------------------------------------------------------------------

    @Serializable
    private data class SourceDto(
        val videoId: String,
        val name: String,
        val durationMs: Long,
        val durationLabel: String,
        val width: Int,
        val height: Int,
        val orientation: String,
        val hasAudio: Boolean,
        val indexed: Boolean,
    )

    @Serializable
    private data class ClipDto(
        val clipId: String,
        val trackId: String,
        val videoId: String,
        val timelineStartMs: Long,
        val timelineEndMs: Long,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val speed: Float,
        val effectCount: Int,
    )

    @Serializable
    private data class TextItemDto(
        val textItemId: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val isCaption: Boolean,
    )

    @Serializable
    private data class TransactionDto(val id: String, val summary: String, val prompt: String?)

    @Serializable
    private data class ProjectStateDto(
        val projectId: String,
        val name: String,
        val durationMs: Long,
        val durationLabel: String,
        val aspectRatio: String,
        val appliedEdits: Int,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val sources: List<SourceDto>,
        val clips: List<ClipDto>,
        val textItems: List<TextItemDto>,
        val appliedTransactions: List<TransactionDto>,
    )

    @Serializable
    private data class ChapterDto(
        val sceneId: String,
        val startMs: Long,
        val endMs: Long,
        val label: String?,
        val summary: String?,
        val motionScore: Float,
        val changeScore: Float,
    )

    @Serializable
    private data class EventDto(
        val eventId: String,
        val startMs: Long,
        val apexMs: Long,
        val endMs: Long,
        val before: String,
        val action: String,
        val after: String,
        val speech: String?,
        val score: Float,
    )

    @Serializable
    private data class OverviewDto(
        val videoId: String,
        val durationMs: Long,
        val durationLabel: String,
        val width: Int,
        val height: Int,
        val orientation: String,
        val hasAudio: Boolean,
        val indexedStages: List<String>,
        val transcriptAvailable: Boolean,
        val speechSummary: String?,
        val chapters: List<ChapterDto>,
        val majorEvents: List<EventDto>,
    )

    @Serializable
    private data class MatchDto(val startMs: Long, val endMs: Long, val text: String, val score: Float)

    @Serializable
    private data class CandidateDto(
        val sceneId: String,
        val startMs: Long,
        val endMs: Long,
        val label: String?,
        val transcript: String?,
        val visibleText: List<String>,
        val score: Float,
    )

    @Serializable
    private data class SearchDto(
        val query: String,
        val speechMatches: List<MatchDto>,
        val onScreenMatches: List<MatchDto>,
        val sceneCandidates: List<CandidateDto>,
        val notes: List<String>,
    )

    @Serializable
    private data class SceneDto(
        val sceneId: String,
        val startMs: Long,
        val endMs: Long,
        val label: String?,
        val summary: String?,
        val transcript: String,
        val visibleText: List<String>,
        val motionScore: Float,
        val changeScore: Float,
        val keyframeIds: List<String>,
        val stripId: String?,
    )

    @Serializable
    private data class StripDto(
        val stripId: String,
        val startMs: Long,
        val endMs: Long,
        val frameTimestamps: List<Long>,
        val labels: List<String>,
        val columns: Int,
        val rows: Int,
    )

    @Serializable
    private data class InspectDto(
        val startMs: Long,
        val endMs: Long,
        val frameTimestamps: List<Long>,
        val evidence: String?,
        val imageCount: Int,
        val privacyNote: String?,
    )

    @Serializable
    private data class EventListDto(val startMs: Long, val endMs: Long, val events: List<EventDto2>)

    @Serializable
    private data class EventDto2(
        val type: String,
        val timestampMs: Long,
        val durationMs: Long,
        val level: Float,
        val label: String?,
    )

    @Serializable
    private data class VisualEventListDto(val startMs: Long, val endMs: Long, val events: List<VisualEventDto>)

    @Serializable
    private data class VisualEventDto(
        val type: String,
        val startMs: Long,
        val endMs: Long,
        val score: Float,
    )

    @Serializable
    private data class TranscriptListDto(val segments: List<TranscriptSegmentDto>)

    @Serializable
    private data class TranscriptSegmentDto(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val words: List<MatchDto>,
    )

    @Serializable
    private data class MotionListDto(val segments: List<MotionDto>)

    @Serializable
    private data class MotionDto(
        val startMs: Long,
        val endMs: Long,
        val level: String,
        val averageMotion: Float,
        val peakMotion: Float,
    )

    @Serializable
    private data class SilenceListDto(val silences: List<MatchDto>)

    private fun transcriptJson(memory: SemanticVideoMemory, startMs: Long, endMs: Long): String =
        HeliumJson.encodeToString(
            TranscriptListDto(
                memory.transcriptInRange(startMs, endMs).map { segment ->
                    TranscriptSegmentDto(
                        startMs = segment.range.startMs,
                        endMs = segment.range.endMsExclusive,
                        text = segment.text,
                        words = segment.words.map {
                            MatchDto(it.range.startMs, it.range.endMsExclusive, it.text, 1f)
                        },
                    )
                },
            ),
        )

    private fun matchesJson(kind: String, matches: List<com.sekhar.helium.core.model.TextMatch>): String {
        val payload = buildString {
            append("{\"kind\":\"").append(kind).append("\",\"matches\":[")
            matches.forEachIndexed { index, match ->
                if (index > 0) append(',')
                append("{\"startMs\":").append(match.range.startMs)
                append(",\"endMs\":").append(match.range.endMsExclusive)
                append(",\"text\":").append(HeliumJson.encodeToString(match.text))
                append(",\"score\":").append(match.score)
                append('}')
            }
            append("]}")
        }
        return payload
    }

    private fun audioEventsJson(memory: SemanticVideoMemory, startMs: Long, endMs: Long): String =
        HeliumJson.encodeToString(
            EventListDto(
                startMs = startMs,
                endMs = endMs,
                events = memory.audioEventsIn(
                    com.sekhar.helium.core.model.TimeRange(startMs, endMs),
                ).map {
                    EventDto2(
                        type = it.type.name.lowercase(),
                        timestampMs = it.timestampMs,
                        durationMs = it.durationMs,
                        level = it.level,
                        label = it.label,
                    )
                },
            ),
        )

    private fun visualEventsJson(memory: SemanticVideoMemory, startMs: Long, endMs: Long): String =
        HeliumJson.encodeToString(
            VisualEventListDto(
                startMs = startMs,
                endMs = endMs,
                events = memory.visualEventsIn(
                    com.sekhar.helium.core.model.TimeRange(startMs, endMs),
                ).map {
                    VisualEventDto(
                        type = it.type.name.lowercase(),
                        startMs = it.range.startMs,
                        endMs = it.range.endMsExclusive,
                        score = it.score,
                    )
                },
            ),
        )

    private fun motionJson(memory: SemanticVideoMemory, limit: Int): String =
        HeliumJson.encodeToString(
            MotionListDto(
                memory.highMotionSegments(limit).map {
                    MotionDto(
                        startMs = it.range.startMs,
                        endMs = it.range.endMsExclusive,
                        level = it.level.name.lowercase(),
                        averageMotion = it.averageMotion,
                        peakMotion = it.peakMotion,
                    )
                },
            ),
        )

    private fun silenceJson(memory: SemanticVideoMemory, minDurationMs: Long): String =
        HeliumJson.encodeToString(
            SilenceListDto(
                memory.silences(minDurationMs).map {
                    MatchDto(
                        startMs = it.startMs,
                        endMs = it.endMsExclusive,
                        text = "silence",
                        score = it.durationMs / 1000f,
                    )
                },
            ),
        )

    private companion object {
        const val TAG = "ToolDispatcher"
    }
}

// --- small JSON accessors ---------------------------------------------------------

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.longOrNull(key: String): Long? =
    this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: this[key]?.jsonPrimitive?.intOrNull?.toLong()

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.content?.toIntOrNull()
