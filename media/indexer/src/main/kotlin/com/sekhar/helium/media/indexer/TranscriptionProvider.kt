package com.sekhar.helium.media.indexer

import com.sekhar.helium.core.model.SourceMedia
import com.sekhar.helium.core.model.TranscriptSegment

/**
 * Speech-to-text, deliberately provider independent.
 *
 * Helium's index is built so that transcription is optional: captions and
 * "find what I said" need it, but scene detection, motion, audio events and
 * re-framing do not. A missing transcript degrades those specific features
 * instead of failing the whole import.
 *
 * Implementations that can be added without touching the editor:
 * a downloadable on-device model (whisper.cpp), the Helium gateway, or the
 * platform recogniser.
 */
interface TranscriptionProvider {

    /** Stable id, recorded in telemetry. */
    val id: String

    /** False when no model or backend is configured; the UI hides speech features. */
    val isAvailable: Boolean

    /** Timestamped segments, ideally with word-level timings. */
    suspend fun transcribe(source: SourceMedia): List<TranscriptSegment>
}

/**
 * Used when no transcription backend is configured.
 *
 * Returning an empty list is a truthful "there is no transcript", which the UI
 * surfaces as "transcription unavailable" rather than pretending speech was
 * silent.
 */
object UnavailableTranscriptionProvider : TranscriptionProvider {
    override val id: String = "unavailable"
    override val isAvailable: Boolean = false
    override suspend fun transcribe(source: SourceMedia): List<TranscriptSegment> = emptyList()
}
