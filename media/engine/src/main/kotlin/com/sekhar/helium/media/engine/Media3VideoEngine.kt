package com.sekhar.helium.media.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.sekhar.helium.core.common.AppDispatchers
import com.sekhar.helium.core.model.NormalizedRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders exports with Media3's [Transformer].
 *
 * Media3's transformer is `@UnstableApi`, so it is wrapped here and exposed to
 * the rest of the app only through [VideoEngine]. Everything this engine can do
 * is declared in [capabilities], and anything it cannot render is reported back
 * in [ExportOutcome.Success.skippedFeatures] so an export never silently drops
 * an edit the user made.
 *
 * Exports always read the ORIGINAL media files, never the low-resolution
 * analysis proxy.
 */
@UnstableApi
class Media3VideoEngine(
    private val context: Context,
    private val dispatchers: AppDispatchers,
) : VideoEngine {

    override val name: String = "media3-transformer"

    override suspend fun capabilities(): EngineCapabilities = withContext(dispatchers.default) {
        EngineCapabilities(
            supportedVideoMimeTypes = buildSet {
                add(MimeTypes.VIDEO_H264)
                // HEVC is only advertised when the platform decoder table lists it.
                if (android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                        .codecInfos.any { info ->
                            info.isEncoder && info.supportedTypes.any {
                                it.equals(MimeTypes.VIDEO_H265, ignoreCase = true)
                            }
                        }
                ) {
                    add(MimeTypes.VIDEO_H265)
                }
            },
            supportedAudioMimeTypes = setOf(MimeTypes.AUDIO_AAC),
            maxWidth = MAX_SUPPORTED_DIMENSION,
            maxHeight = MAX_SUPPORTED_DIMENSION,
            bakedEffects = BAKED_EFFECTS,
        )
    }

    override suspend fun export(
        request: ExportRequest,
        onProgress: (progress: Float, stage: String) -> Unit,
    ): ExportOutcome = withContext(dispatchers.io) {
        val plan = request.plan
        if (plan.isEmpty) {
            return@withContext ExportOutcome.Failure("There is nothing to export: the timeline has no video.")
        }

        onProgress(0f, "Preparing export")
        val output = request.outputFile
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val items = plan.clips.map { clip ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.sourceStartMs)
                .setEndPositionMs(clip.sourceEndMs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(clip.uri))
                .setClippingConfiguration(clipping)
                .build()

            val videoEffects = mutableListOf<androidx.media3.common.Effect>()
            val hasExplicitCrop = clip.crop != NormalizedRect.FULL
            videoEffects += Presentation.createForWidthAndHeight(
                plan.width,
                plan.height,
                // Without an explicit crop the frame is scaled to fill the output
                // aspect (no black bars); with one, the explicit crop wins.
                if (hasExplicitCrop) {
                    Presentation.LAYOUT_SCALE_TO_FIT
                } else {
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                },
            )
            if (hasExplicitCrop) {
                // Media3's Crop takes (left, right, bottom, top) as normalised offsets.
                videoEffects += Crop(
                    clip.crop.left,
                    clip.crop.right,
                    clip.crop.bottom,
                    clip.crop.top,
                )
            }

            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(!clip.hasAudio || clip.muted)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
        }

        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(items)
        val composition = Composition.Builder(listOf(sequence)).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(plan.videoCodecMime)
            .setAudioMimeType(plan.audioCodecMime)
            .build()

        onProgress(0.02f, "Encoding")

        val completion = kotlinx.coroutines.CompletableDeferred<ExportOutcome>()
        transformer.addListener(
            object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completion.complete(
                        ExportOutcome.Success(
                            // Media3's ExportResult reports stats, not the destination;
                            // the export always writes to the path we passed to start().
                            outputPath = output.absolutePath,
                            sizeBytes = exportResult.fileSizeBytes,
                            durationMs = exportResult.approximateDurationMs,
                            width = plan.width,
                            height = plan.height,
                            skippedFeatures = plan.unsupportedFeatures,
                        ),
                    )
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException,
                ) {
                    completion.complete(
                        ExportOutcome.Failure(
                            message = exception.message ?: "The encoder failed.",
                            recoverable = true,
                        ),
                    )
                }
            },
        )

        val started = runCatching { transformer.start(composition, output.absolutePath) }
        if (started.isFailure) {
            return@withContext ExportOutcome.Failure(
                started.exceptionOrNull()?.message ?: "Could not start the encoder.",
            )
        }

        val progressHolder = ProgressHolder()
        try {
            while (!completion.isCompleted) {
                currentCoroutineContext().ensureActive()
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress((progressHolder.progress / 100f).coerceIn(0f, 0.99f), "Encoding")
                }
                delay(PROGRESS_POLL_MS)
            }
        } catch (cancelled: CancellationException) {
            transformer.cancel()
            output.delete()
            throw cancelled
        }

        val outcome = completion.await()
        if (outcome is ExportOutcome.Success) {
            onProgress(1f, "Finished")
        } else if (outcome is ExportOutcome.Failure) {
            output.delete()
        }
        outcome
    }

    private companion object {
        const val PROGRESS_POLL_MS = 400L
        const val MAX_SUPPORTED_DIMENSION = 4096

        /**
         * Effects this engine bakes into the render pass today. Anything else is
         * reported to the user by [ExportPlanBuilder].
         */
        val BAKED_EFFECTS: Set<RenderableEffect> = emptySet()
    }
}
