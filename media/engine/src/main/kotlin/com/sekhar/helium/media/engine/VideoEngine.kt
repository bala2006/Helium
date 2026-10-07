package com.sekhar.helium.media.engine

import java.io.File

/** What an engine can encode on this device. */
data class EngineCapabilities(
    val supportedVideoMimeTypes: Set<String>,
    val supportedAudioMimeTypes: Set<String>,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Effect kinds this engine can bake into an export. */
    val bakedEffects: Set<RenderableEffect>,
)

/** Everything an export needs. */
data class ExportRequest(
    val plan: ExportPlan,
    val outputFile: File,
)

/** Terminal state of an export. */
sealed interface ExportOutcome {
    data class Success(
        val outputPath: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        /**
         * Effects the user added that this engine could not bake in. Surfaced to
         * the user rather than silently dropped.
         */
        val skippedFeatures: List<String>,
    ) : ExportOutcome

    data class Failure(val message: String, val recoverable: Boolean = true) : ExportOutcome

    data object Cancelled : ExportOutcome
}

/**
 * Renders an [ExportPlan] to a file.
 *
 * The abstraction exists so the editor never depends directly on an experimental
 * media API: Media3's transformer is `@UnstableApi`, and a future native or
 * server-side renderer can be swapped in behind this interface without touching
 * the timeline, the AI tools or the UI.
 */
interface VideoEngine {

    /** Human-readable engine name, recorded in export telemetry. */
    val name: String

    /** Probes what this device can actually encode. */
    suspend fun capabilities(): EngineCapabilities

    /**
     * Renders [request].
     *
     * Implementations must be cancellation-safe: cancelling the calling coroutine
     * must stop the encoder and leave no partial output registered as a success.
     */
    suspend fun export(
        request: ExportRequest,
        onProgress: (progress: Float, stage: String) -> Unit,
    ): ExportOutcome
}
