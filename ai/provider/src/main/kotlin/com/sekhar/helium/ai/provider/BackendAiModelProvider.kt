package com.sekhar.helium.ai.provider

import com.sekhar.helium.ai.tools.ToolCallRequest
import com.sekhar.helium.core.common.HeliumJson
import com.sekhar.helium.core.common.HeliumLog
import com.sekhar.helium.core.common.NoOpLog
import com.sekhar.helium.core.model.AiTokenUsage
import com.sekhar.helium.core.model.CloudAnalysisMode
import com.sekhar.helium.core.network.GatewayClient
import com.sekhar.helium.core.network.GatewayImage
import com.sekhar.helium.core.network.GatewayMessage
import com.sekhar.helium.core.network.GatewayRequest
import com.sekhar.helium.core.network.GatewayTool
import com.sekhar.helium.core.network.GatewayToolCall
import kotlinx.serialization.encodeToString

/**
 * The shipping provider: it reaches the model through the Helium gateway.
 *
 * This is the single place where the privacy policy is enforced, which is why
 * the enforcement lives here and not in the UI:
 *
 * * `MINIMIZE` strips **every** evidence image before the request is built, so
 *   the gateway cannot receive frames even if the model asks for them.
 * * Otherwise images are capped at [com.sekhar.helium.core.model.AiModelConfig.maxImagesPerRequest].
 * * The high-resolution (`original`) tool is only honoured when the mode is
 *   [CloudAnalysisMode.FULL]; the tool dispatcher checks this too, and this class
 *   is the backstop.
 *
 * The gateway URL and the session token are the only credentials involved; the
 * provider API key exists solely on the server.
 */
class BackendAiModelProvider(
    private val client: GatewayClient,
    private val log: HeliumLog = NoOpLog,
) : AiModelProvider {

    override val id: String = ID
    override val displayName: String = "Helium AI gateway"

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val config = request.config
        val notes = mutableListOf<String>()
        var imagesSent = 0

        val messages = request.messages.map { message ->
            val budget = (config.maxImagesPerRequest - imagesSent).coerceAtLeast(0)
            val allowed = when (config.cloudAnalysisMode) {
                CloudAnalysisMode.MINIMIZE -> {
                    if (message.images.isNotEmpty()) {
                        notes += "Minimize cloud analysis is on, so ${message.images.size} evidence image(s) were not sent."
                    }
                    emptyList()
                }
                else -> message.images.take(budget).also { imagesSent += it.size }
            }
            if (message.images.isNotEmpty() && allowed.size < message.images.size &&
                config.cloudAnalysisMode != CloudAnalysisMode.MINIMIZE
            ) {
                notes += "Evidence images were capped at ${config.maxImagesPerRequest} for this request."
            }

            GatewayMessage(
                role = message.role.wire,
                text = message.text.takeIf { it.isNotBlank() },
                images = allowed.map {
                    GatewayImage(
                        base64 = it.base64,
                        mimeType = it.mimeType,
                        detail = config.imageDetail.wireValue,
                        label = it.label,
                        timestampMs = it.timestampMs,
                    )
                },
                toolCalls = message.toolCalls.map {
                    GatewayToolCall(callId = it.callId, name = it.name, argumentsJson = it.argumentsJson)
                },
                toolCallId = message.toolCallId,
                toolName = message.toolName,
                toolResultJson = message.toolResultJson,
            )
        }

        val gatewayRequest = GatewayRequest(
            modelId = config.modelId,
            instructions = request.instructions,
            messages = messages,
            tools = request.tools.map {
                GatewayTool(
                    name = it.name,
                    description = it.description,
                    parametersJson = HeliumJson.encodeToString(it.parameters),
                )
            },
            reasoningLevel = config.reasoningLevel.wireValue,
            imageDetail = config.imageDetail.wireValue,
            maxOutputTokens = config.maxOutputTokens,
            temperature = config.temperature,
            idempotencyKey = request.idempotencyKey,
        )

        log.d(TAG, "Sending request: model=${config.modelId} images=$imagesSent tools=${request.tools.size}")

        val response = client.generate(gatewayRequest)

        return ModelResponse(
            modelId = response.modelId.ifBlank { config.modelId },
            text = response.outputText,
            toolCalls = response.toolCalls.map {
                ToolCallRequest(callId = it.callId, name = it.name, argumentsJson = it.argumentsJson)
            },
            usage = AiTokenUsage(
                inputTokens = response.usage.inputTokens,
                outputTokens = response.usage.outputTokens,
                reasoningTokens = response.usage.reasoningTokens,
            ),
            finishReason = response.finishReason,
            notes = notes,
        )
    }

    companion object {
        const val ID: String = "helium-gateway"

        private const val TAG = "AiProvider"
    }
}
