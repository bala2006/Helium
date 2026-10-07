package com.sekhar.helium.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Helium gateway wire protocol.
 *
 * This is the server-side half of the contract owned by the app: the Android
 * module `:core:network` (`GatewayClient.kt`) is the authority for field names,
 * defaults and status semantics, and everything here mirrors it exactly.
 * `WireContractTest` pins the shape from the client side and
 * `GatewayRoutesTest` exercises it from the server side, so a change that is not
 * mirrored on both ends fails a build instead of a user's edit.
 *
 * Names are deliberately left as-is (no `@SerialName` renaming): the client
 * serialises with a plain `Json` instance, so the wire format is camelCase.
 */
val GatewayJson: Json = Json {
    // Mirrors the client so a newer app can talk to an older gateway and the
    // model configuration can be extended without breaking deployments.
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
    classDiscriminator = "kind"
    coerceInputValues = true
}

@Serializable
data class GatewayImage(
    val base64: String,
    val mimeType: String = "image/jpeg",
    val detail: String = "low",
    val label: String? = null,
    val timestampMs: Long? = null,
)

@Serializable
data class GatewayTool(
    val name: String,
    val description: String,
    /** JSON Schema, serialised by the client so the wire stays a plain string. */
    val parametersJson: String,
)

@Serializable
data class GatewayToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

@Serializable
data class GatewayMessage(
    val role: String,
    val text: String? = null,
    val images: List<GatewayImage> = emptyList(),
    val toolCalls: List<GatewayToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolResultJson: String? = null,
)

@Serializable
data class GatewayUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val imagesSent: Int = 0,
    val estimatedCostUsd: Double = 0.0,
)

@Serializable
data class GatewayRequest(
    val sessionToken: String? = null,
    val modelId: String,
    val instructions: String,
    val messages: List<GatewayMessage>,
    val tools: List<GatewayTool> = emptyList(),
    val reasoningLevel: String = "low",
    val imageDetail: String = "low",
    val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.2,
    val idempotencyKey: String? = null,
)

@Serializable
data class GatewayResponse(
    val modelId: String = "",
    val outputText: String = "",
    val toolCalls: List<GatewayToolCall> = emptyList(),
    val usage: GatewayUsage = GatewayUsage(),
    val finishReason: String = "",
)

/**
 * Error body. The client maps status codes onto its own exception taxonomy, so
 * this exists for humans reading logs and for the settings screen's diagnostics.
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val detail: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val service: String = "helium-gateway",
    val providerConfigured: Boolean = false,
)

/** Mints a short-lived session token for a client id. */
@Serializable
data class SessionRequest(
    val clientId: String,
)

@Serializable
data class SessionResponse(
    val sessionToken: String,
    val expiresAtEpochSeconds: Long,
)

/** Per-session totals, exposed for operators. */
@Serializable
data class UsageSummary(
    val sessionId: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val imagesSent: Long,
    val estimatedCostUsd: Double,
)
