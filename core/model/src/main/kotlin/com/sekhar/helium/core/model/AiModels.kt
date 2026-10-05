package com.sekhar.helium.core.model

import kotlinx.serialization.Serializable

/**
 * How much cloud analysis the user allows.
 *
 * `MINIMIZE` keeps everything except the current instruction on-device and only
 * sends text; `BALANCED` (default) additionally allows selected low-resolution
 * evidence images; `FULL` allows original-resolution frames when the model
 * explicitly requests them. See PRIVACY.md.
 */
@Serializable
enum class CloudAnalysisMode(val displayName: String) {
    MINIMIZE("Minimize cloud analysis"),
    BALANCED("Balanced (recommended)"),
    FULL("Allow high-resolution inspection"),
}

/** Reasoning effort hint passed to the model provider. */
@Serializable
enum class ReasoningLevel(val wireValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/** Image detail hint for multimodal payloads. */
@Serializable
enum class ImageDetail(val wireValue: String) {
    LOW("low"),
    AUTO("auto"),
    HIGH("high"),
}

/**
 * Model configuration.
 *
 * Deliberately data, not code: the provider, model id, reasoning level, image
 * detail and limits are all remotely selectable so Helium can adopt a new model
 * without shipping an app update.
 */
@Serializable
data class AiModelConfig(
    val providerId: String = "openai",
    val modelId: String = "gpt-6-luna",
    val reasoningLevel: ReasoningLevel = ReasoningLevel.LOW,
    val imageDetail: ImageDetail = ImageDetail.LOW,
    /** Hard bound on the agent tool loop. */
    val maxToolIterations: Int = 12,
    /** Hard bound on evidence images attached to a single request. */
    val maxImagesPerRequest: Int = 12,
    val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.2,
    val requestTimeoutMs: Long = 90_000L,
    /** When enabled, hard prompts may be retried once against [escalationModelId]. */
    val allowEscalation: Boolean = false,
    val escalationModelId: String? = null,
    val cloudAnalysisMode: CloudAnalysisMode = CloudAnalysisMode.BALANCED,
) {
    init {
        require(maxToolIterations in 1..64) { "maxToolIterations out of range" }
        require(maxImagesPerRequest in 0..64) { "maxImagesPerRequest out of range" }
    }
}

/** Token accounting returned by a provider. */
@Serializable
data class AiTokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
    operator fun plus(other: AiTokenUsage) = AiTokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
    )
}

/** Per-request and cumulative AI telemetry. Never contains media or transcripts. */
@Serializable
data class AiUsage(
    val tokens: AiTokenUsage = AiTokenUsage(),
    val imagesSent: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val requests: Int = 0,
    val latencyMs: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
) {
    operator fun plus(other: AiUsage) = AiUsage(
        tokens = tokens + other.tokens,
        imagesSent = imagesSent + other.imagesSent,
        toolCalls = toolCalls + other.toolCalls,
        toolFailures = toolFailures + other.toolFailures,
        requests = requests + other.requests,
        latencyMs = latencyMs + other.latencyMs,
        estimatedCostUsd = estimatedCostUsd + other.estimatedCostUsd,
    )
}

/** A bounded, user-visible progress line for the AI command panel. */
@Serializable
data class AgentProgress(
    val stage: AgentStage,
    val message: String,
    val iteration: Int = 0,
) {
    companion object {
        val IDLE = AgentProgress(AgentStage.IDLE, "")
    }
}

/** Coarse agent stages. Internal chain-of-thought is never exposed. */
@Serializable
enum class AgentStage {
    IDLE,
    UNDERSTANDING,
    RETRIEVING_CONTEXT,
    REASONING,
    INSPECTING,
    PLANNING_EDITS,
    APPLYING,
    VERIFYING,
    SUMMARIZING,
    FAILED,
    OFFLINE,
}
