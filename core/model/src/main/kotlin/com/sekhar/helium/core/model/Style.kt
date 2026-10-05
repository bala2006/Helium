package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/** Output aspect ratio presets, expressed as exact integer ratios to avoid drift. */
@Serializable
enum class AspectRatio(val widthUnits: Int, val heightUnits: Int) {
    PORTRAIT_9_16(9, 16),
    LANDSCAPE_16_9(16, 9),
    SQUARE_1_1(1, 1),
    VERTICAL_4_5(4, 5),
    ORIGINAL(0, 0),
    ;

    val isDefined: Boolean get() = widthUnits > 0 && heightUnits > 0

    val ratio: Float
        get() = if (!isDefined) 1f else widthUnits.toFloat() / heightUnits.toFloat()
}

/** Crop expressed as a normalised rectangle of the source frame. */
@Serializable
data class CropRect(val rect: NormalizedRect) {
    companion object {
        val FULL = CropRect(NormalizedRect.FULL)
        val CENTERED_9_16 = CropRect(NormalizedRect(0.21875f, 0f, 0.78125f, 1f))
    }
}

/** Text horizontal alignment. */
@Serializable
enum class TextAlignment { START, CENTER, END }

/**
 * Visual style of a text/caption item.
 *
 * Colours are ARGB packed into a `Long` so the model stays platform-free while
 * still round-tripping through JSON deterministically.
 */
@Serializable
data class TextStyle(
    val fontSizeSp: Float = 34f,
    val colorArgb: Long = 0xFFFFFFFFL,
    val backgroundArgb: Long? = 0x99000000L,
    val strokeWidthPx: Float = 0f,
    val strokeColorArgb: Long = 0xFF000000L,
    val shadow: Boolean = true,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val position: NormalizedPoint = NormalizedPoint(0.5f, 0.72f),
    val maxWidthFraction: Float = 0.86f,
    val highlightActiveWord: Boolean = false,
    val highlightColorArgb: Long = 0xFFFECDBEL,
    val uppercase: Boolean = false,
    val bold: Boolean = true,
    val safeZoneAware: Boolean = true,
) {
    companion object {
        /** Preset tuned for 9:16 short-form captions. */
        val REEL_CAPTION = TextStyle(
            fontSizeSp = 40f,
            position = NormalizedPoint(0.5f, 0.7f),
            highlightActiveWord = true,
        )

        /** Preset for lower-third titles. */
        val TITLE = TextStyle(
            fontSizeSp = 52f,
            position = NormalizedPoint(0.5f, 0.2f),
            backgroundArgb = null,
        )
    }
}

/** One word inside a caption item, with its own timing, enabling word highlighting. */
@Serializable
data class WordTiming(
    val text: String,
    val range: TimeRange,
)
