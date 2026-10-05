package com.sekhar.helium.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MathUtilsTest {

    @Test
    fun normalizeClampsAndScales() {
        assertEquals(0.0, MathUtils.normalize(5.0, 10.0, 20.0))
        assertEquals(1.0, MathUtils.normalize(50.0, 10.0, 20.0))
        assertEquals(0.5, MathUtils.normalize(15.0, 10.0, 20.0))
        assertEquals(0.0, MathUtils.normalize(15.0, 20.0, 20.0))
    }

    @Test
    fun percentileInterpolates() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.0, MathUtils.percentile(values, 0.0))
        assertEquals(4.0, MathUtils.percentile(values, 1.0))
        assertEquals(2.5, MathUtils.percentile(values, 0.5))
        assertEquals(0.0, MathUtils.percentile(doubleArrayOf(), 0.5))
    }

    @Test
    fun rmsMatchesKnownValue() {
        val samples = floatArrayOf(1f, -1f, 1f, -1f)
        assertEquals(1.0, MathUtils.rms(samples), 1e-9)
        assertEquals(0.0, MathUtils.rms(floatArrayOf()))
        assertTrue(MathUtils.rms(floatArrayOf(0.25f, 0.25f)) < 0.3)
    }

    @Test
    fun clampWorksForAllTypes() {
        assertEquals(2, MathUtils.clamp(5, 0, 2))
        assertEquals(2.0f, MathUtils.clamp(5.0f, 0.0f, 2.0f))
        assertEquals(2L, MathUtils.clamp(5L, 0L, 2L))
    }
}
