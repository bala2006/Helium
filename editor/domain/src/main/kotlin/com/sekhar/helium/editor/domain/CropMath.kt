package com.sekhar.helium.editor.domain

import com.sekhar.helium.core.model.CropRect
import com.sekhar.helium.core.model.NormalizedRect

/**
 * Crop geometry for aspect conversion and subject-aware reframing.
 *
 * Everything is computed in normalised `0..1` coordinates so the result is
 * resolution independent and can be applied to the proxy during preview and to
 * the original at export time.
 */
object CropMath {

    /**
     * Largest crop of [targetAspect] that fits inside the source, optionally
     * tightened by [zoomScale] and centred on a focus point.
     *
     * When the focus point is close to an edge the window is clamped so the crop
     * never leaves the frame — this is what keeps "keep me centred" from
     * producing black bars.
     */
    fun cropForAspect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: Float,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f,
        zoomScale: Float = 1f,
    ): CropRect {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetAspect <= 0f) return CropRect.FULL

        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val width: Float
        val height: Float
        if (targetAspect > sourceAspect) {
            // Target is wider than the source: keep full width, reduce height.
            width = 1f
            height = sourceAspect / targetAspect
        } else {
            // Target is taller: keep full height, reduce width.
            height = 1f
            width = targetAspect / sourceAspect
        }

        val shrink = 1f / zoomScale.coerceAtLeast(1f)
        val finalWidth = (width * shrink).coerceIn(0.05f, 1f)
        val finalHeight = (height * shrink).coerceIn(0.05f, 1f)

        val centerX = focusX.coerceIn(finalWidth / 2f, 1f - finalWidth / 2f)
        val centerY = focusY.coerceIn(finalHeight / 2f, 1f - finalHeight / 2f)

        return CropRect(
            NormalizedRect(
                left = centerX - finalWidth / 2f,
                top = centerY - finalHeight / 2f,
                right = centerX + finalWidth / 2f,
                bottom = centerY + finalHeight / 2f,
            ),
        )
    }

    /** True when [crop] actually removes part of the frame. */
    fun isMeaningful(crop: CropRect): Boolean = crop.rect != NormalizedRect.FULL
}
