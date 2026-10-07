package com.sekhar.helium.gateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A provider failure, translated from an upstream HTTP status.
 *
 * [statusCode] is the *upstream* status. The routes decide what the client sees
 * (rate limiting is passed through; anything else becomes 502, because from the
 * app's point of view a provider rejection is a gateway fault, not its own).
 */
class UpstreamFailure(
    val statusCode: Int,
    override val message: String,
    val retryAfterSeconds: Long? = null,
) : Exception(message)

/** The thing behind the gateway. Swapped in tests for a scripted fake. */
interface UpstreamModel {
    /**
     * @param imagesSent receives the number of evidence images actually forwarded
     *   upstream, after the gateway's own cap has been applied.
     */
    suspend fun complete(
        request: GatewayRequest,
        modelId: String,
        imagesSent: (Int) -> Unit,
    ): GatewayResponse
}

/**
 * Talks to the OpenAI Responses API.
 *
 * This is the only class that knows the provider's shape, which is the whole
 * point of the gateway: the protocol the app speaks is stable, the provider
 * behind it is not, and `HELIUM_PROVIDER_API_KEY` never leaves this process.
 *
 * Two provider-specific shapes are worth calling out:
 *
 * * Tool outputs must be preceded by the matching `function_call` item, which is
 *   why the protocol carries an assistant turn's tool calls rather than only
 *   their results.
 * * Reasoning effort is only sent when the app asked for one, so a non-reasoning
 *   model is never handed an unsupported parameter.
 */
class OpenAiResponsesModel(
    private val client: HttpClient,
    private val config: GatewayConfig,
    private val json: Json = GatewayJson,
    private val requestTimeoutMs: Long = 120_000L,
) : UpstreamModel {

    override suspend fun complete(
        request: GatewayRequest,
        modelId: String,
        imagesSent: (Int) -> Unit,
    ): GatewayResponse {
        val body = buildRequestBody(request, modelId, imagesSent)
        val url = "${config.providerBaseUrl.trimEnd('/')}/responses"

        val response = runCatching {
            client.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${config.providerApiKey}")
                timeout { requestTimeoutMillis = requestTimeoutMs }
                setBody(body.toString())
            }
        }.getOrElse { cause ->
            // A connection failure often carries no message, so fall back to the
            // type name rather than logging "null".
            throw UpstreamFailure(
                503,
                "The model provider is unreachable: ${cause.message ?: cause::class.simpleName}",
            )
        }

        val status = response.status.value
        val text = response.bodyAsText()
        if (status !in 200..299) {
            throw UpstreamFailure(
                statusCode = status,
                message = "Provider returned $status: ${text.take(300)}",
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
            )
        }

        return runCatching { parseResponse(text, modelId) }.getOrElse { cause ->
            throw UpstreamFailure(502, "Unreadable provider response: ${cause.message}")
        }
    }

    private fun buildRequestBody(
        request: GatewayRequest,
        modelId: String,
        imagesSent: (Int) -> Unit,
    ): JsonObject {
        var forwarded = 0
        val input = buildJsonArray {
            request.messages.forEach { message ->
                when {
                    // An assistant turn that used tools: its text (if any) plus one
                    // `function_call` item per call, ahead of the outputs below.
                    message.toolCalls.isNotEmpty() -> {
                        if (!message.text.isNullOrBlank()) add(assistantMessage(message.text))
                        message.toolCalls.forEach { call ->
                            addJsonObject {
                                put("type", "function_call")
                                put("call_id", call.callId)
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        }
                    }

                    message.toolResultJson != null -> addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message.toolCallId.orEmpty())
                        put("output", message.toolResultJson)
                    }

                    else -> {
                        val budget = (config.maxImagesPerRequest - forwarded).coerceAtLeast(0)
                        val images = message.images.take(budget)
                        forwarded += images.size
                        add(
                            userMessage(
                                text = message.text.orEmpty(),
                                images = images,
                                detail = request.imageDetail,
                            ),
                        )
                    }
                }
            }
        }
        imagesSent(forwarded)

        return buildJsonObject {
            put("model", modelId)
            put("instructions", request.instructions)
            put("input", input)
            put("max_output_tokens", request.maxOutputTokens.coerceIn(64, 32_768))
            put("temperature", request.temperature.coerceIn(0.0, 2.0))
            if (request.reasoningLevel in REASONING_LEVELS) {
                put("reasoning", buildJsonObject { put("effort", request.reasoningLevel) })
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            // The app's schemas are strict JSON Schema objects, so the
                            // provider gets the exact contract the parser enforces.
                            put("parameters", json.parseToJsonElement(tool.parametersJson))
                        }
                    }
                }
                put("tool_choice", "auto")
            }
        }
    }

    private fun userMessage(text: String, images: List<GatewayImage>, detail: String): JsonObject =
        buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                if (text.isNotBlank()) {
                    addJsonObject {
                        put("type", "input_text")
                        put("text", text)
                    }
                }
                images.forEach { image ->
                    addJsonObject {
                        put("type", "input_image")
                        put("image_url", "data:${image.mimeType};base64,${image.base64}")
                        // The app already decided the detail; the gateway only
                        // validates it so an unknown value cannot reach the provider.
                        put("detail", image.detail.takeIf { it in IMAGE_DETAILS } ?: detail)
                    }
                }
            }
        }

    private fun assistantMessage(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        putJsonArray("content") {
            addJsonObject {
                put("type", "output_text")
                put("text", text)
            }
        }
    }

    private fun parseResponse(raw: String, requestedModel: String): GatewayResponse {
        val root = json.parseToJsonElement(raw).jsonObject

        val text = StringBuilder()
        val toolCalls = mutableListOf<GatewayToolCall>()

        (root["output"] as? JsonArray)?.forEach { item ->
            val node = item as? JsonObject ?: return@forEach
            when (node["type"]?.jsonPrimitive?.content) {
                "message" -> (node["content"] as? JsonArray)?.forEach { part ->
                    val piece = part as? JsonObject ?: return@forEach
                    if (piece["type"]?.jsonPrimitive?.content == "output_text") {
                        text.append(piece["text"]?.jsonPrimitive?.content.orEmpty())
                    }
                }

                "function_call" -> toolCalls += GatewayToolCall(
                    callId = node["call_id"]?.jsonPrimitive?.content.orEmpty(),
                    name = node["name"]?.jsonPrimitive?.content.orEmpty(),
                    argumentsJson = node["arguments"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }

        val usageNode = root["usage"] as? JsonObject
        val inputTokens = usageNode?.get("input_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val outputTokens = usageNode?.get("output_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val reasoningTokens = ((usageNode?.get("output_tokens_details") as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.content?.toIntOrNull()) ?: 0

        val status = root["status"]?.jsonPrimitive?.content.orEmpty()
        val incompleteReason = ((root["incomplete_details"] as? JsonObject)
            ?.get("reason")?.jsonPrimitive?.content)
        val finishReason = if (status == "incomplete" && incompleteReason != null) {
            "incomplete:$incompleteReason"
        } else {
            status.ifBlank { "completed" }
        }

        return GatewayResponse(
            modelId = root["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: requestedModel,
            outputText = text.toString(),
            toolCalls = toolCalls,
            usage = GatewayUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                estimatedCostUsd = ModelPricing.estimateUsd(requestedModel, inputTokens, outputTokens),
            ),
            finishReason = finishReason,
        )
    }

    companion object {
        val REASONING_LEVELS = setOf("minimal", "low", "medium", "high")
        val IMAGE_DETAILS = setOf("low", "auto", "high")

        /** Client used when no engine is injected (production and Docker). */
        fun defaultClient(): HttpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 120_000L
            }
        }
    }
}
