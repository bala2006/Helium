package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.AddCaption
import com.sekhar.helium.core.model.AddImageOverlay
import com.sekhar.helium.core.model.AddMusic
import com.sekhar.helium.core.model.AddText
import com.sekhar.helium.core.model.AddTransition
import com.sekhar.helium.core.model.AddZoom
import com.sekhar.helium.core.model.BlurRegion
import com.sekhar.helium.core.model.CropClip
import com.sekhar.helium.core.model.DeleteEdit
import com.sekhar.helium.core.model.DuckMusic
import com.sekhar.helium.core.model.DuplicateClip
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.FadeAudio
import com.sekhar.helium.core.model.FreezeFrame
import com.sekhar.helium.core.model.MoveClip
import com.sekhar.helium.core.model.MuteRange
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.ReframeClip
import com.sekhar.helium.core.model.RemoveCaption
import com.sekhar.helium.core.model.RemoveRange
import com.sekhar.helium.core.model.RestoreRange
import com.sekhar.helium.core.model.SetAspectRatioOp
import com.sekhar.helium.core.model.SetColorAdjust
import com.sekhar.helium.core.model.SetSpeed
import com.sekhar.helium.core.model.SetVolume
import com.sekhar.helium.core.model.SplitClip
import com.sekhar.helium.core.model.Timeline
import com.sekhar.helium.core.model.TrimClip
import com.sekhar.helium.core.model.formatDurationMs
import com.sekhar.helium.core.model.formatTimestampMs

/**
 * Turns operations into short, user-facing English.
 *
 * The AI panel shows these instead of any model reasoning: the user should see
 * what changed, not how the model decided.
 */
object OperationSummary {

    /** One line describing a single operation. */
    fun describe(operation: EditOperation): String = when (operation) {
        is SplitClip -> "Split a clip at ${formatTimestampMs(operation.atSourceMs)}"
        is TrimClip ->
            "Trimmed a clip to ${formatDurationMs(operation.newSourceRange.durationMs)}"
        is RemoveRange ->
            "Removed ${rangeLabel(operation.range)}"
        is RestoreRange ->
            "Restored ${rangeLabel(operation.range)}"
        is MoveClip -> "Moved a clip"
        is DuplicateClip -> "Duplicated a clip"
        is SetSpeed -> "Set ${operation.speed}× speed on ${rangeLabel(operation.range)}"
        is SetVolume -> "Set volume to ${(operation.volume * 100).toInt()}% on ${rangeLabel(operation.range)}"
        is MuteRange -> "Muted ${rangeLabel(operation.range)}"
        is SetAspectRatioOp -> "Changed aspect ratio to ${operation.aspectRatio.widthUnits}:${operation.aspectRatio.heightUnits}"
        is CropClip -> "Cropped a clip"
        is ReframeClip -> "Re-framed a clip to ${operation.targetAspect.widthUnits}:${operation.targetAspect.heightUnits}"
        is AddZoom -> "Added a ${operation.scale}× zoom at ${formatTimestampMs(operation.range.startMs)}"
        is BlurRegion -> "Blurred a region at ${formatTimestampMs(operation.range.startMs)}"
        is SetColorAdjust -> "Adjusted colour at ${formatTimestampMs(operation.range.startMs)}"
        is AddText -> "Added text \u201C${operation.text.take(40)}\u201D"
        is AddCaption -> "Added ${operation.cues.size} caption cue${if (operation.cues.size == 1) "" else "s"}"
        is RemoveCaption -> when {
            operation.containingText != null -> "Removed captions matching \u201C${operation.containingText}\u201D"
            operation.textItemIds.isNotEmpty() -> "Removed ${operation.textItemIds.size} text item(s)"
            else -> "Removed captions"
        }
        is AddImageOverlay -> "Added an image overlay"
        is FreezeFrame ->
            "Froze at ${formatTimestampMs(operation.atSourceMs)} for ${formatDurationMs(operation.holdMs)}"
        is AddTransition -> "Added a ${operation.spec.kind.name.lowercase().replace('_', ' ')} transition"
        is AddMusic -> "Added music"
        is DuckMusic -> if (operation.enabled) "Enabled music ducking" else "Disabled music ducking"
        is FadeAudio -> "Faded audio"
        is DeleteEdit -> "Reverted an earlier edit"
    }

    /**
     * Summary line for a whole transaction, e.g.
     * `Removed 0:01.8–0:03.1 · Set 1.25× speed · 4 edits in 4:12.0`.
     */
    fun summarize(
        operations: List<EditOperation>,
        project: Project,
        timeline: Timeline,
    ): String {
        if (operations.isEmpty()) return "No changes"

        val parts = operations.map { describe(it) }.distinct()
        val head = when {
            parts.size <= 3 -> parts.joinToString(" · ")
            else -> parts.take(3).joinToString(" · ") + " · +${parts.size - 3} more"
        }
        val count = "${operations.size} edit${if (operations.size == 1) "" else "s"}"
        val duration = formatDurationMs(timeline.durationMs)
        val base = if (timeline.durationMs == project.durationMs && timeline.durationMs > 0L) {
            "$head ($count)"
        } else {
            "$head ($count, now $duration)"
        }
        return base
    }

    private fun rangeLabel(range: com.sekhar.helium.core.model.TimeRange): String =
        "${formatTimestampMs(range.startMs)}\u2013${formatTimestampMs(range.endMsExclusive)}"
}
