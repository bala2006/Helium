package com.sekhar.helium.core.common

import java.security.MessageDigest

/**
 * Hashing helpers used for cache keys and cheap visual comparison.
 *
 * These are intentionally dependency-free so they can run on any thread and in
 * plain JVM unit tests.
 */
object Hashing {

    private val HEX = "0123456789abcdef".toCharArray()

    /** Stable SHA-256 of [bytes], hex encoded. Used for source fingerprints. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    /** Stable SHA-256 of [value] interpreted as UTF-8. */
    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    /**
     * Fingerprint of a media source: size + last-modified + uri.
     *
     * Deliberately cheap: reading multi-GB files to hash them is not acceptable on
     * import. The fingerprint only needs to be stable for cache invalidation.
     */
    fun sourceFingerprint(uri: String, sizeBytes: Long, lastModifiedMs: Long): String =
        sha256Hex("$uri|$sizeBytes|$lastModifiedMs")

    /**
     * 64-bit average-hash of an 8x8 grayscale block grid.
     *
     * [gray] is a row-major luminance buffer of [width] x [height] in `0..255`.
     * Returns -1 when the buffer is degenerate.
     */
    fun perceptualHash(gray: IntArray, width: Int, height: Int): Long {
        if (width <= 0 || height <= 0 || gray.size < width * height) return -1L
        val cells = LongArray(64)
        for (cellY in 0 until 8) {
            val y0 = cellY * height / 8
            val y1 = maxOf(y0 + 1, (cellY + 1) * height / 8)
            for (cellX in 0 until 8) {
                val x0 = cellX * width / 8
                val x1 = maxOf(x0 + 1, (cellX + 1) * width / 8)
                var sum = 0L
                var count = 0
                for (y in y0 until minOf(y1, height)) {
                    val row = y * width
                    for (x in x0 until minOf(x1, width)) {
                        sum += gray[row + x].toLong()
                        count++
                    }
                }
                cells[cellY * 8 + cellX] = if (count == 0) 0L else sum / count
            }
        }
        var mean = 0L
        for (c in cells) mean += c
        mean /= 64
        var hash = 0L
        for (i in 0 until 64) {
            if (cells[i] >= mean) hash = hash or (1L shl i)
        }
        return hash
    }

    /** Hamming distance between two perceptual hashes; smaller means more similar. */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
