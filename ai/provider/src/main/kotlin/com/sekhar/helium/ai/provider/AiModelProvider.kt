package com.sekhar.helium.ai.provider

import com.sekhar.helium.ai.tools.EvidenceImage
import com.sekhar.helium.ai.tools.ToolCallRequest
import com.sekhar.helium.ai.tools.ToolJsonSchema
import com.sekhar.helium.core.model.AiModelConfig
import com.sekhar.helium.core.model.AiTokenUsage

/** Who a message in the conversation came from. */
enum class ModelRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

/** One turn of the conversation. */
data class ModelMessage(
    val role: ModelRole,
    val text: String = "",
    val images: List<EvidenceImage> = emptyList(),
    /**
     * The calls an assistant turn made. They must be replayed with the turn:
     * a provider that expects each tool output to be preceded by the matching
     * `function_call` item cannot reconstruct the arguments from the output.
     */
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolResultJson: String? = null,
)

/** A single completion request. */
data class ModelRequest(
    val instructions: String,
    val messages: List<ModelMessage>,
    val tools: List<ToolJsonSchema>,
    val config: AiModelConfig,
    val idempotencyKey: String? = null,
)

/** A single completion response. */
data class ModelResponse(
    val modelId: String,
    val text: String,
    val toolCalls: List<ToolCallRequest>,
    val usage: AiTokenUsage,
    val finishReason: String = "",
    /** Set when the provider had to drop evidence images to respect limits. */
    val notes: List<String> = emptyList(),
)

/**
 * The editing intelligence.
 *
 * Helium never hard-codes a vendor here. The shipping implementation is
 * [BackendAiModelProvider], which reaches a model through the Helium gateway so
 * no provider secret is ever present in the APK. A direct provider (for local
 * models, or for a future on-device model) only has to implement this interface.
 */
interface AiModelProvider {
    /** Stable provider id recorded in telemetry, e.g. `helium-gateway`. */
    val id: String

    /** Human-readable label for the settings screen. */
    val displayName: String

    suspend fun generate(request: ModelRequest): ModelResponse
}

/** Resolves the provider named by a remotely supplied [AiModelConfig]. */
class AiProviderRegistry(providers: List<AiModelProvider>) {

    private val byId: Map<String, AiModelProvider> = providers.associateBy { it.id }

    val ids: Set<String> get() = byId.keys

    val available: List<AiModelProvider> get() = byId.values.toList()

    fun provider(providerId: String): AiModelProvider? = byId[providerId]

    /** Falls back to the first registered provider so a stale config never bricks AI. */
    fun resolve(config: AiModelConfig): AiModelProvider? =
        byId[config.providerId] ?: byId.values.firstOrNull()
}
