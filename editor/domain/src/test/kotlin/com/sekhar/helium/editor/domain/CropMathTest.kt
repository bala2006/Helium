package com.sekhar.helium.editor.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropMathTest {

    @Test
    fun portraitTargetKeepsFullHeightAndTrimsWidth() {
        // Landscape 1920x1080 source, 9:16 target.
        val crop = CropMath.cropForAspect(1920, 1080, 9f / 16f)
        assertEquals(0f, crop.rect.top, 1e-4f)
        assertEquals(1f, crop.rect.bottom, 1e-4f)
        assertEquals(0.5f, crop.rect.centerX, 1e-4f)
        assertTrue(crop.rect.width < 1f)
        // Aspect of the resulting window must match the target.
        val windowAspect = (crop.rect.width * 1920f) / (crop.rect.height * 1080f)
        assertEquals(9f / 16f, windowAspect, 1e-3f)
    }

    @Test
    fun landscapeTargetOnPortraitSourceTrimsHeight() {
        val crop = CropMath.cropForAspect(1080, 1920, 16f / 9f)
        assertEquals(1f, crop.rect.width, 1e-4f)
        assertTrue(crop.rect.height < 1f)
    }

    @Test
    fun matchingAspectIsAnIdentityCrop() {
        val crop = CropMath.cropForAspect(1080, 1920, 1080f / 1920f)
        assertEquals(0f, crop.rect.left, 1e-3f)
        assertEquals(1f, crop.rect.right, 1e-3f)
        assertEquals(1f, crop.rect.bottom, 1e-3f)
    }

    @Test
    fun zoomShrinksTheWindowFurther() {
        val normal = CropMath.cropForAspect(1080, 1920, 9f / 16f, zoomScale = 1f)
        val zoomed = CropMath.cropForAspect(1080, 1920, 9f / 16f, zoomScale = 2f)
        assertTrue(zoomed.rect.width < normal.rect.width)
        assertTrue(zoomed.rect.height < normal.rect.height)
    }

    @Test
    fun focusPointIsClampedInsideTheFrame() {
        // A focus near the left edge must not push the crop out of bounds.
        val crop = CropMath.cropForAspect(1920, 1080, 9f / 16f, focusX = 0.0f)
        assertTrue(crop.rect.left >= 0f)
        assertTrue(crop.rect.right <= 1f)
    }

    @Test
    fun degenerateInputFallsBackToTheFullFrame() {
        assertEquals(
            com.sekhar.helium.core.model.CropRect.FULL,
            CropMath.cropForAspect(0, 0, 9f / 16f),
        )
    }
}
