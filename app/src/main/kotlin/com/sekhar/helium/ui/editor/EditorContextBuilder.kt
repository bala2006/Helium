package com.sekhar.helium.ui.editor

import com.sekhar.helium.core.model.AnalysisStage
import com.sekhar.helium.core.model.AiModelConfig
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.SemanticVideoMemory
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.formatDurationMs
import com.sekhar.helium.core.model.formatTimestampMs

/**
 * Builds the level-1 context that accompanies every natural-language instruction.
 *
 * This is the cheapest possible representation that is still enough for the model
 * to decide *what to inspect next*: project state, the current timeline, the last
 * few edits, and one compact overview per source. It deliberately contains no
 * images and no full transcript — the model pulls those through tools only when
 * it needs them, which is what keeps the cost of an instruction bounded.
 *
 * Pure and dependency-free so it is unit tested directly (see
 * `EditorContextBuilderTest`).
 */
object EditorContextBuilder {

    /** Rough ceiling on the context block, so one huge project cannot blow the budget. */
    const val MAX_CHARS = 6_000

    fun build(
        project: Project,
        memories: Map<String, SemanticVideoMemory>,
        indexing: Map<SourceId, IndexingProgress> = emptyMap(),
        config: AiModelConfig = AiModelConfig(),
        maxHistory: Int = 6,
    ): String = buildString {
        appendLine("### Project")
        appendLine(
            "id=${project.id.value} name=\"${project.name}\" " +
                "duration=${formatDurationMs(project.timeline.durationMs)} " +
                "aspect=${project.aspectRatio.widthUnits}:${project.aspectRatio.heightUnits} " +
                "clips=${project.timeline.allClips().size} " +
                "appliedEdits=${project.history.cursor} " +
                "canUndo=${project.history.canUndo} canRedo=${project.history.canRedo}",
        )
        appendLine("cloudAnalysis=${config.cloudAnalysisMode.name} maxToolIterations=${config.maxToolIterations}")

        appendLine()
        appendLine("### Current timeline (play order, milliseconds in source time)")
        if (project.timeline.allClips().isEmpty()) {
            appendLine("(empty)")
        } else {
            project.timeline.allClips().sortedBy { it.timelineStartMs }.take(MAX_CLIP_LINES).forEach { clip ->
                val source = project.source(clip.sourceId)
                val extras = buildList {
                    if (clip.speed != 1f) add("speed=${clip.speed}")
                    if (clip.muted) add("muted")
                    clip.freeze?.let { add("freeze+${it.holdMs}ms") }
                    if (clip.effects.isNotEmpty()) add("effects=${clip.effects.map { e -> e::class.simpleName }}")
                }
                appendLine(
                    "clipId=${clip.id.value} videoId=${clip.sourceId.value} " +
                        "timeline=${clip.timelineStartMs}..${clip.timelineEndMs} " +
                        "source=${clip.sourceRange.startMs}..${clip.sourceRange.endMsExclusive}" +
                        (if (extras.isEmpty()) "" else " " + extras.joinToString(" ")) +
                        (source?.let { " (${it.displayName})" } ?: ""),
                )
            }
            if (project.timeline.allClips().size > MAX_CLIP_LINES) {
                appendLine("… ${project.timeline.allClips().size - MAX_CLIP_LINES} more clips")
            }
        }

        val textItems = project.timeline.allTextItems()
        if (textItems.isNotEmpty()) {
            appendLine()
            appendLine("### Text and captions")
            textItems.sortedBy { it.range.startMs }.take(MAX_TEXT_LINES).forEach { item ->
                appendLine(
                    "id=${item.id.value} timeline=${item.range.startMs}..${item.range.endMsExclusive} " +
                        "caption=${item.isCaption} text=\"${item.text.take(80)}\"",
                )
            }
        }

        val history = project.history.appliedTransactions
        if (history.isNotEmpty()) {
            appendLine()
            appendLine("### Recent edits (newest last)")
            history.takeLast(maxHistory).forEach { transaction ->
                appendLine(
                    "id=${transaction.id} prompt=\"${transaction.userPrompt.orEmpty().take(80)}\" " +
                        "-> ${transaction.summary}",
                )
            }
        }

        appendLine()
        appendLine("### Sources")
        if (project.sources.isEmpty()) {
            appendLine("(no media imported yet)")
        } else {
            project.sources.forEach { source ->
                val memory = memories[source.id.value]
                val progress = indexing[source.id]
                appendLine(
                    "videoId=${source.id.value} name=\"${source.displayName}\" " +
                        "duration=${formatDurationMs(source.metadata.durationMs)} " +
                        "${source.metadata.displayWidth}x${source.metadata.displayHeight} " +
                        "${source.metadata.orientation.name.lowercase()} " +
                        "audio=${if (source.metadata.hasAudio) "yes" else "no"} " +
                        "index=${indexLabel(memory, progress)}",
                )
                if (source.unsupportedReason != null) {
                    appendLine("  unusable: ${source.unsupportedReason}")
                }
                memory?.let { appendOverview(it) }
            }
        }
    }.let { it.truncateTo(MAX_CHARS) }

    private fun StringBuilder.appendOverview(memory: SemanticVideoMemory) {
        val overview = memory.toOverview()
        appendLine(
            "  overview: scenes=${overview.sceneCount} " +
                "transcriptSegments=${memory.transcript.size} " +
                "onscreenText=${memory.ocr.size} " +
                "audioEvents=${memory.audioEvents.size} " +
                "eventPackets=${memory.eventPackets.size} " +
                "keyframes=${memory.keyframes.size} strips=${memory.strips.size}",
        )
        memory.scenes.take(MAX_SCENE_LINES).forEach { scene ->
            val label = scene.label ?: scene.summary ?: scene.transcriptText?.take(60).orEmpty()
            appendLine(
                "  scene sceneId=${scene.id.value} " +
                    "${formatTimestampMs(scene.range.startMs)}..${formatTimestampMs(scene.range.endMsExclusive)} " +
                    "motion=${"%.2f".format(scene.motionScore)} change=${"%.2f".format(scene.changeScore)}" +
                    (if (label.isBlank()) "" else " \"$label\""),
            )
        }
        if (memory.scenes.size > MAX_SCENE_LINES) {
            appendLine("  … ${memory.scenes.size - MAX_SCENE_LINES} more scenes (use search_video or get_scene)")
        }
        memory.eventPackets.sortedByDescending { it.score }.take(MAX_EVENT_LINES).forEach { packet ->
            appendLine(
                "  event eventId=${packet.id.value} " +
                    "start=${packet.startMs} apex=${packet.apexMs} end=${packet.endMs} " +
                    "action=\"${packet.action.take(60)}\"" +
                    (packet.speech?.let { " speech=\"${it.take(40)}\"" } ?: ""),
            )
        }
        val silences = memory.silences(500L).take(MAX_SILENCE_LINES)
        if (silences.isNotEmpty()) {
            appendLine(
                "  silences(>=500ms, first ${silences.size}): " +
                    silences.joinToString(", ") { "${it.startMs}..${it.endMsExclusive}" },
            )
        }
        val moments = memory.mostInterestingMoments(3)
        if (moments.isNotEmpty()) {
            appendLine(
                "  high-information windows: " +
                    moments.joinToString(", ") { "${it.startMs}..${it.endMsExclusive}" },
            )
        }
    }

    private fun indexLabel(memory: SemanticVideoMemory?, progress: IndexingProgress?): String = when {
        memory != null && memory.stages.contains(AnalysisStage.COMPLETE) -> "complete"
        memory != null -> memory.stages.joinToString("+") { it.name.lowercase() }
        progress != null -> "in progress (${progress.stage.displayName})"
        else -> "not started"
    }

    private fun String.truncateTo(limit: Int): String =
        if (length <= limit) this else substring(0, limit) + "\n… (context truncated)"
}

private const val MAX_CLIP_LINES = 40
private const val MAX_TEXT_LINES = 20
private const val MAX_SCENE_LINES = 12
private const val MAX_EVENT_LINES = 6
private const val MAX_SILENCE_LINES = 8
