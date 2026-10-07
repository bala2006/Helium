package com.sekhar.helium.media.indexer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.sekhar.helium.core.common.AppDispatchers
import com.sekhar.helium.core.common.Hashing
import com.sekhar.helium.core.model.VideoMetadata
import com.sekhar.helium.media.indexer.analysis.FrameSignal
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Decoded mono PCM plus its sample rate. */
data class PcmAudio(val samples: FloatArray, val sampleRateHz: Int) {
    val durationMs: Long get() = if (sampleRateHz == 0) 0L else (samples.size * 1000L) / sampleRateHz
}

/** A decoded proxy frame and its timestamp. */
data class ProxyFrame(
    val timestampMs: Long,
    val bitmap: Bitmap,
    val signal: FrameSignal,
)

/**
 * Pulls the cheap local signals the semantic index needs.
 *
 * Everything here runs on-device through platform decoders. The decoded frames
 * are then downscaled to a tiny grid before analysis, which is what keeps memory
 * flat: a 4K frame never becomes a 4K `IntArray`.
 */
class MediaSignalExtractor(
    private val context: Context,
    private val dispatchers: AppDispatchers,
) {

    /** Reads technical metadata without decoding any frames. */
    suspend fun readMetadata(uri: Uri): VideoMetadata = withContext(dispatchers.io) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int): String? = retriever.extractMetadata(key)

            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            val bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val frameCount = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            val frameRate = if (frameCount != null && durationMs > 0L) {
                (frameCount * 1000.0 / durationMs).toFloat()
            } else {
                0f
            }

            VideoMetadata(
                durationMs = durationMs,
                width = width,
                height = height,
                rotationDegrees = rotation,
                frameRate = frameRate,
                hasAudio = hasAudio,
                videoMimeType = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                bitrate = bitrate,
                isVariableFrameRate = frameRate <= 0f,
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Decodes one proxy frame at each timestamp in [timestampsMs], returning both
     * the bitmap and its measured signal.
     *
     * Signals are computed at a fixed 64x36 grid: small enough to be free, large
     * enough to distinguish a static talking head from a fast action beat.
     */
    suspend fun extractFrames(
        uri: Uri,
        timestampsMs: List<Long>,
        proxyWidth: Int = PROXY_ANALYSIS_WIDTH,
        proxyHeight: Int = PROXY_ANALYSIS_HEIGHT,
    ): List<ProxyFrame> = withContext(dispatchers.default) {
        if (timestampsMs.isEmpty()) return@withContext emptyList()
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<ProxyFrame>()
        var previousLuma: IntArray? = null
        var previousHistogram: IntArray? = null
        var previousHash = 0L

        try {
            retriever.setDataSource(context, uri)
            timestampsMs.forEach { timestampMs ->
                val bitmap = runCatching {
                    retriever.getFrameAtTime(
                        timestampMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }.getOrNull() ?: return@forEach

                val scaled = Bitmap.createScaledBitmap(bitmap, proxyWidth, proxyHeight, true)
                val pixels = IntArray(proxyWidth * proxyHeight)
                scaled.getPixels(pixels, 0, proxyWidth, 0, 0, proxyWidth, proxyHeight)
                val luma = IntArray(pixels.size) { index ->
                    val pixel = pixels[index]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    (r * 77 + g * 150 + b * 29) shr 8
                }
                val histogram = histogramOf(luma)
                val hash = Hashing.perceptualHash(luma, proxyWidth, proxyHeight)

                val lumaDelta = previousLuma?.let { meanAbsoluteDifference(it, luma) } ?: 0f
                val histogramDelta = previousHistogram?.let { histogramDistance(it, histogram) } ?: 0f
                val motion = maxOf(lumaDelta, histogramDelta * 0.8f)

                frames += ProxyFrame(
                    timestampMs = timestampMs,
                    bitmap = scaled,
                    signal = FrameSignal(
                        timestampMs = timestampMs,
                        lumaDelta = lumaDelta.coerceIn(0f, 1f),
                        histogramDelta = histogramDelta.coerceIn(0f, 1f),
                        motion = motion.coerceIn(0f, 1f),
                        perceptualHash = hash,
                    ),
                )

                previousLuma = luma
                previousHistogram = histogram
                previousHash = hash
                if (scaled !== bitmap) bitmap.recycle()
            }
        } finally {
            retriever.release()
        }
        frames
    }

    /**
     * Decodes the audio track to mono PCM, capped at [maxSeconds].
     *
     * The cap keeps memory bounded on a long recording — silence detection and
     * impact finding do not need more than a few minutes at a time, and the whole
     * file is analysed in chunks by the caller.
     */
    suspend fun decodePcm(uri: Uri, maxSeconds: Int = 600): PcmAudio = withContext(dispatchers.io) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            // MediaExtractor only exposes the (context, uri, headers) overload for
            // content:// URIs; null headers is the documented "no extra headers" form.
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return@withContext PcmAudio(FloatArray(0), 0)

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext PcmAudio(FloatArray(0), 0)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val maxSamples = sampleRate * maxSeconds

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val output = ArrayList<Float>(minOf(maxSamples, 1 shl 20))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var timestampOffsetUs = -1L

            while (!outputDone && output.size < maxSamples) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            if (timestampOffsetUs < 0) timestampOffsetUs = sampleTimeUs
                            codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        while (shorts.hasRemaining() && output.size < maxSamples) {
                            // Down-mix by averaging the interleaved channels.
                            var sum = 0f
                            var channel = 0
                            while (channel < channelCount && shorts.hasRemaining()) {
                                sum += shorts.get().toFloat() / 32768f
                                channel++
                            }
                            output += if (channel == 0) 0f else sum / channel
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    outputDone = true
                }
            }

            PcmAudio(samples = output.toFloatArray(), sampleRateHz = sampleRate)
        } catch (failure: Throwable) {
            // A file with an unsupported audio codec still gets a visual index.
            PcmAudio(FloatArray(0), 0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun meanAbsoluteDifference(previous: IntArray, current: IntArray): Float {
        if (previous.size != current.size || previous.isEmpty()) return 0f
        var sum = 0L
        for (index in previous.indices) sum += abs(current[index] - previous[index])
        return (sum.toDouble() / (previous.size * 255.0)).toFloat()
    }

    private fun histogramOf(luma: IntArray): IntArray {
        val histogram = IntArray(16)
        for (value in luma) histogram[(value shr 4).coerceIn(0, 15)]++
        return histogram
    }

    private fun histogramDistance(previous: IntArray, current: IntArray): Float {
        if (previous.size != current.size) return 0f
        var total = 0L
        var sum = 0L
        for (index in previous.indices) {
            total += previous[index] + current[index]
            sum += abs(previous[index] - current[index])
        }
        return if (total == 0L) 0f else (sum.toDouble() / total).toFloat()
    }

    companion object {
        const val PROXY_ANALYSIS_WIDTH = 64
        const val PROXY_ANALYSIS_HEIGHT = 36
        private const val TIMEOUT_US = 10_000L
    }
}
