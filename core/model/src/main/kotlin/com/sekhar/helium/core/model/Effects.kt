package com.sekhar.helium.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An effect attached to a clip.
 *
 * [range] is expressed in *clip-relative milliseconds*, i.e. offset from the
 * clip's `timelineStartMs`. Keeping effects clip-relative means moving a clip
 * never invalidates its effects.
 */
@Serializable
sealed interface Effect {
    val id: String

    /** Clip-relative interval the effect is active for. */
    val range: TimeRange
}

/**
 * Ken-Burns style zoom towards a normalised focus point.
 *
 * `focusX`/`focusY` default to the frame centre; `scale` is the linear zoom
 * factor (1.0 = no zoom, 2.0 = 200%).
 */
@Serializable
@SerialName("zoom")
data class ZoomEffect(
    override val id: String,
    override val range: TimeRange,
    val scale: Float,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val easeInMs: Long = 250L,
    val easeOutMs: Long = 250L,
) : Effect

/**
 * Blurs a normalised region of the frame, used for privacy redaction such as
 * hiding a phone number or a price tag.
 *
 * [trackSubject] asks the exporter to keep the mask attached to the detected
 * subject (used for face/person tracks); when no track data is available the
 * static [region] is used.
 */
@Serializable
@SerialName("blur")
data class BlurEffect(
    override val id: String,
    override val range: TimeRange,
    val region: NormalizedRect,
    val featherPx: Float = 24f,
    val strength: Float = 1f,
    val trackSubject: Boolean = false,
) : Effect

/** Kinds of transition Helium can render between two adjacent clips. */
@Serializable
enum class TransitionKind { CUT, FADE, CROSS_DISSOLVE, SLIDE_LEFT, SLIDE_UP, WIPE, ZOOM_BLUR }

/** A transition request plus its duration. */
@Serializable
data class TransitionSpec(
    val kind: TransitionKind,
    val durationMs: Long = 400L,
)

/** Which side of a clip a transition is attached to. */
@Serializable
enum class TransitionBoundary { START, END }

/** Attaches a transition to the start or end boundary of a clip. */
@Serializable
@SerialName("transition")
data class TransitionEffect(
    override val id: String,
    override val range: TimeRange,
    val boundary: TransitionBoundary,
    val spec: TransitionSpec,
) : Effect

/** Simple colour adjustments, used for commands like "make it warmer". */
@Serializable
@SerialName("color")
data class ColorAdjustEffect(
    override val id: String,
    override val range: TimeRange,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val vignette: Float = 0f,
) : Effect
