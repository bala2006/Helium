package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/**
 * Identifiers.
 *
 * These are inline value classes so the compiler prevents mixing a `ClipId`
 * with a `SourceId`, while still serializing as a plain JSON string and costing
 * nothing at runtime.
 */
@JvmInline
@Serializable
value class ProjectId(val value: String)

@JvmInline
@Serializable
value class SourceId(val value: String)

@JvmInline
@Serializable
value class TrackId(val value: String)

@JvmInline
@Serializable
value class ClipId(val value: String)

@JvmInline
@Serializable
value class TextItemId(val value: String)

@JvmInline
@Serializable
value class OverlayItemId(val value: String)

@JvmInline
@Serializable
value class SceneId(val value: String)

@JvmInline
@Serializable
value class ShotId(val value: String)

@JvmInline
@Serializable
value class EventId(val value: String)

@JvmInline
@Serializable
value class KeyframeId(val value: String)

@JvmInline
@Serializable
value class StripId(val value: String)
