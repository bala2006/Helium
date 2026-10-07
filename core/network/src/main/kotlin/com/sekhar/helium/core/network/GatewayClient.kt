package com.sekhar.helium.core.network

import kotlinx.serialization.Serializable

/**
 * One turn in the model conversation, as understood by the Helium gateway.
 *
 * The Android app never speaks a provider's API directly: it speaks this small,
 * stable protocol, and the gateway translates it. That is what allows the model
 * provider behind the gateway to change without shipping a new APK, and it is
 * what keeps every provider secret off the device.
 */
@Serializable
data class GatewayMessage(
    val role: String,
    val text: String? = null,
    /** Base64 JPEG evidence attached to a user turn. */
    val images: List<GatewayImage> = emptyList(),
    /** Present on assistant turns that asked for tools, in call order. */
    val toolCalls: List<GatewayToolCall> = emptyList(),
    /** Present on tool-result turns. */
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolResultJson: String? = null,
)

@Serializable
data class GatewayImage(
    val base64: String,
    val mimeType: String = "image/jpeg",
    val detail: String = "low",
    /** Provenance, echoed back in the privacy report. */
    val label: String? = null,
    val timestampMs: Long? = null,
)

@Serializable
data class GatewayTool(
    val name: String,
    val description: String,
    /** JSON Schema, serialised, so the wire format stays a plain string map. */
    val parametersJson: String,
)

@Serializable
data class GatewayRequest(
    /** Short-lived session token minted by the gateway; never a provider key. */
    val sessionToken: String? = null,
    val modelId: String,
    val instructions: String,
    val messages: List<GatewayMessage>,
    val tools: List<GatewayTool> = emptyList(),
    val reasoningLevel: String = "low",
    val imageDetail: String = "low",
    val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.2,
    /** Client-supplied key so a retried request is never charged twice. */
    val idempotencyKey: String? = null,
)

@Serializable
data class GatewayToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
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
data class GatewayResponse(
    val modelId: String = "",
    val outputText: String = "",
    val toolCalls: List<GatewayToolCall> = emptyList(),
    val usage: GatewayUsage = GatewayUsage(),
    val finishReason: String = "",
)

/** Failure taxonomy the UI can act on. */
sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No usable network. Editing must keep working; AI shows an offline state. */
    class Offline(cause: Throwable? = null) : GatewayException("You are offline", cause)

    /** The gateway rejected our session; the user must sign in again. */
    class Unauthorized(message: String) : GatewayException(message)

    /** The gateway or provider is throttling us; retrying later is safe. */
    class RateLimited(val retryAfterMs: Long?) : GatewayException("Rate limited")

    /** The request failed validation — a bug on our side, not the user's. */
    class BadRequest(message: String) : GatewayException(message)

    class Timeout(cause: Throwable? = null) : GatewayException("The AI request timed out", cause)

    class Server(val statusCode: Int, message: String) : GatewayException(message)

    /** Response could not be parsed. */
    class Malformed(message: String, cause: Throwable? = null) : GatewayException(message, cause)
}

/**
 * Transport to the Helium gateway.
 *
 * Implemented by [KtorGatewayClient]; faked in tests.
 */
interface GatewayClient {
    suspend fun generate(request: GatewayRequest): GatewayResponse

    /** Cheap reachability probe used to render the offline state. */
    suspend fun ping(): Boolean
}
