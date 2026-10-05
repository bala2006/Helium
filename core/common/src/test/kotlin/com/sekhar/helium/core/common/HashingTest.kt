package com.sekhar.helium.core.common

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class HashingTest {

    @Test
    fun sha256IsStableAndDistinct() {
        val a = Hashing.sha256Hex("helium")
        val b = Hashing.sha256Hex("helium")
        val c = Hashing.sha256Hex("helium2")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(64, a.length)
        assertEquals(a, a.lowercase(Locale.ROOT))
    }

    @Test
    fun sourceFingerprintDependsOnAllInputs() {
        val base = Hashing.sourceFingerprint("content://v/1", 100, 1000)
        assertEquals(base, Hashing.sourceFingerprint("content://v/1", 100, 1000))
        assertNotEquals(base, Hashing.sourceFingerprint("content://v/2", 100, 1000))
        assertNotEquals(base, Hashing.sourceFingerprint("content://v/1", 200, 1000))
        assertNotEquals(base, Hashing.sourceFingerprint("content://v/1", 100, 2000))
    }

    @Test
    fun perceptualHashDetectsIdenticalAndDifferentImages() {
        val width = 16
        val height = 16
        val flat = IntArray(width * height) { 128 }
        val gradient = IntArray(width * height) { i -> (i % width) * 16 }
        val gradientCopy = IntArray(width * height) { i -> (i % width) * 16 }

        val flatHash = Hashing.perceptualHash(flat, width, height)
        val gradientHash = Hashing.perceptualHash(gradient, width, height)
        val gradientCopyHash = Hashing.perceptualHash(gradientCopy, width, height)

        assertEquals(gradientHash, gradientCopyHash)
        assertEquals(0, Hashing.hammingDistance(gradientHash, gradientCopyHash))
        assertTrue(Hashing.hammingDistance(flatHash, gradientHash) > 0)
    }

    @Test
    fun perceptualHashRejectsDegenerateInput() {
        assertEquals(-1L, Hashing.perceptualHash(IntArray(0), 0, 0))
        assertEquals(-1L, Hashing.perceptualHash(IntArray(4), 16, 16))
    }
}
