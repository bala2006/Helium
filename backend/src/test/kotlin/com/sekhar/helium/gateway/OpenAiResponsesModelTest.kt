package com.sekhar.helium.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The provider mapping.
 *
 * Everything here is about the two things a gateway exists to get right: the
 * request it builds must be one the provider accepts, and the response it parses
 * must be one the app understands. A change to either side that breaks the other
 * fails here rather than in a user's editor.
 */
class OpenAiResponsesModelTest {

    private fun config(maxImagesPerRequest: Int = 8) = GatewayConfig(
        providerBaseUrl = "https://provider.invalid/v1",
        providerApiKey = "test-provider-key",
        sessionSecret = "test-session-secret",
        maxImagesPerRequest = maxImagesPerRequest,
    )

    private class Recorder {
        val sent = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val urls = mutableListOf<String>()
    }

    /** Captures the outgoing body and replies with [reply]. */
    private fun model(
        reply: String = COMPLETED,
        status: HttpStatusCode = HttpStatusCode.OK,
        responseHeaders: List<Pair<String, String>> = emptyList(),
        maxImagesPerRequest: Int = 8,
    ): Pair<OpenAiResponsesModel, Recorder> {
        val recorder = Recorder()
        val engine = MockEngine { request ->
            recorder.sent += (request.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()
                ?.decodeToString()
                .orEmpty()
            recorder.authorizations += request.headers[HttpHeaders.Authorization]
            recorder.urls += request.url.toString()
            respond(
                content = reply,
                status = status,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    *responseHeaders.map { (name, value) -> name to listOf(value) }.toTypedArray(),
                ),
            )
        }
        val subject = OpenAiResponsesModel(
            client = HttpClient(engine),
            config = config(maxImagesPerRequest),
            requestTimeoutMs = 5_000,
        )
        return subject to recorder
    }

    /** Runs [block] expecting it to raise an [UpstreamFailure], and returns it. */
    private suspend fun failureFrom(block: suspend () -> Unit): UpstreamFailure {
        try {
            block()
        } catch (failure: UpstreamFailure) {
            return failure
        }
        fail("expected an UpstreamFailure")
    }

    private fun Recorder.body(): JsonObject = GatewayJson.parseToJsonElement(sent.single()).jsonObject

    // ------------------------------------------------------------ request shape

    @Test
    fun buildsAResponsesRequestWithInstructionsToolsAndReasoning() = runBlocking {
        val (subject, recorder) = model()
        subject.complete(request(reasoningLevel = "medium"), "gpt-6-luna") {}

        val body = recorder.body()
        assertEquals("gpt-6-luna", body["model"]?.jsonPrimitive?.content)
        assertEquals("You plan edits.", body["instructions"]?.jsonPrimitive?.content)
        assertEquals("medium", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("Bearer test-provider-key", recorder.authorizations.single())
        assertTrue(recorder.urls.single().endsWith("/v1/responses"))

        val tool = body["tools"]?.jsonArray?.single()?.jsonObject
        assertEquals("remove_range", tool?.get("name")?.jsonPrimitive?.content)
        // The serialised JSON Schema must arrive as an object, not as a string.
        assertEquals("object", tool?.get("parameters")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun omitsReasoningForAnUnknownLevel() = runBlocking {
        val (subject, recorder) = model()
        subject.complete(request(reasoningLevel = "galaxy-brain"), "gpt-6-luna") {}

        assertNull(
            recorder.body()["reasoning"],
            "a non-reasoning model must never be handed an unsupported parameter",
        )
    }

    @Test
    fun anAssistantTurnsToolCallsPrecedeTheirOutputs() = runBlocking {
        val (subject, recorder) = model()
        subject.complete(
            request().copy(
                messages = listOf(
                    GatewayMessage(role = "user", text = "cut the boring part"),
                    GatewayMessage(
                        role = "assistant",
                        text = "Looking.",
                        toolCalls = listOf(
                            GatewayToolCall("call-1", "get_transcript", "{}"),
                            GatewayToolCall("call-2", "remove_range", """{"startMs":0}"""),
                        ),
                    ),
                    GatewayMessage(
                        role = "tool",
                        toolCallId = "call-1",
                        toolName = "get_transcript",
                        toolResultJson = """{"words":[]}""",
                    ),
                    GatewayMessage(
                        role = "tool",
                        toolCallId = "call-2",
                        toolName = "remove_range",
                        toolResultJson = """{"ok":true}""",
                    ),
                ),
            ),
            "gpt-6-luna",
        ) {}

        val items = recorder.body()["input"]!!.jsonArray.map { it.jsonObject }
        val kinds = items.map { item ->
            item["type"]?.jsonPrimitive?.content ?: "message@" + item["role"]?.jsonPrimitive?.content
        }
        assertEquals(
            listOf(
                "message@user",
                "message@assistant",
                "function_call",
                "function_call",
                "function_call_output",
                "function_call_output",
            ),
            kinds,
            "each tool output must be preceded by its function_call item",
        )

        val calls = items.filter { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals(listOf("call-1", "call-2"), calls.map { it["call_id"]?.jsonPrimitive?.content })
        assertEquals(
            listOf("get_transcript", "remove_range"),
            calls.map { it["name"]?.jsonPrimitive?.content },
        )
        // The captured arguments must survive the round trip, not be synthesised.
        assertEquals(
            """{"startMs":0}""",
            calls[1]["arguments"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun evidenceImagesAreCappedAndUserTurnsBecomeInputImageParts() = runBlocking {
        val (subject, recorder) = model(maxImagesPerRequest = 2)
        var reported = -1
        subject.complete(
            request().copy(
                messages = listOf(
                    GatewayMessage(
                        role = "user",
                        text = "look at this",
                        images = listOf(
                            GatewayImage("AAAA", detail = "low"),
                            GatewayImage("BBBB", detail = "high"),
                            GatewayImage("CCCC", detail = "high"),
                        ),
                    ),
                ),
            ),
            "gpt-6-luna",
        ) { reported = it }

        assertEquals(2, reported, "the gateway's own cap must bound what leaves the process")

        val parts = recorder.body()["input"]!!.jsonArray
            .single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("input_text", parts[0]["type"]?.jsonPrimitive?.content)
        assertEquals(2, parts.count { it["type"]?.jsonPrimitive?.content == "input_image" })
        assertEquals("data:image/jpeg;base64,AAAA", parts[1]["image_url"]?.jsonPrimitive?.content)
        assertEquals("high", parts[2]["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun anUnknownImageDetailFallsBackToTheRequestDefault() = runBlocking {
        val (subject, recorder) = model()
        subject.complete(
            request().copy(
                imageDetail = "low",
                messages = listOf(
                    GatewayMessage(
                        role = "user",
                        text = "look",
                        images = listOf(GatewayImage("AAAA", detail = "ultra")),
                    ),
                ),
            ),
            "gpt-6-luna",
        ) {}

        val image = recorder.body()["input"]!!.jsonArray
            .single().jsonObject["content"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "input_image" }
        assertEquals("low", image["detail"]?.jsonPrimitive?.content)
    }

    // ----------------------------------------------------------- response shape

    @Test
    fun parsesTextToolCallsUsageAndCost() = runBlocking {
        val (subject, _) = model(reply = WITH_TOOL_CALL)
        val response = subject.complete(request(), "gpt-6-luna") {}

        assertEquals("gpt-6-luna", response.modelId)
        assertEquals("Cutting the intro.", response.outputText)
        assertEquals(1, response.toolCalls.size)
        val call = response.toolCalls.single()
        assertEquals("call-9", call.callId)
        assertEquals("remove_range", call.name)
        assertEquals("""{"startMs":0,"endMs":500}""", call.argumentsJson)

        assertEquals(1_000_000, response.usage.inputTokens)
        assertEquals(1_000_000, response.usage.outputTokens)
        assertEquals(500, response.usage.reasoningTokens)
        // 1M input at $0.25 + 1M output at $2.00 for gpt-6-luna.
        assertEquals(2.25, response.usage.estimatedCostUsd, 1e-9)
        assertEquals("completed", response.finishReason)
    }

    @Test
    fun reportsAnIncompleteStopReason() = runBlocking {
        val (subject, _) = model(reply = INCOMPLETE)
        val response = subject.complete(request(), "gpt-6-luna") {}
        assertEquals("incomplete:max_output_tokens", response.finishReason)
    }

    @Test
    fun anUnknownModelIsNotPricedAndTheProvidersReportWins() = runBlocking {
        val (subject, _) = model(reply = COMPLETED)
        val response = subject.complete(request(), "some-future-model") {}
        // No price is invented for a model we have never heard of.
        assertEquals(0.0, response.usage.estimatedCostUsd)
        // The provider names what it actually served, which may differ from what
        // was asked for; that is the id the app records in telemetry.
        assertEquals("gpt-6-luna", response.modelId)
    }

    @Test
    fun fallsBackToTheRequestedModelWhenTheProviderOmitsOne() = runBlocking {
        val (subject, _) = model(reply = NO_MODEL)
        val response = subject.complete(request(), "some-future-model") {}
        assertEquals("some-future-model", response.modelId)
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun aProviderErrorBecomesAnUpstreamFailure() = runBlocking {
        val (subject, _) = model(
            reply = """{"error":"nope"}""",
            status = HttpStatusCode.InternalServerError,
        )
        val failure = failureFrom { subject.complete(request(), "gpt-6-luna") {} }
        assertEquals(500, failure.statusCode)
        assertTrue(failure.message.contains("500"), "got ${failure.message}")
    }

    @Test
    fun providerThrottlingCarriesItsRetryAfter() = runBlocking {
        val (subject, _) = model(
            reply = """{"error":"slow down"}""",
            status = HttpStatusCode.TooManyRequests,
            responseHeaders = listOf(HttpHeaders.RetryAfter to "42"),
        )
        val failure = failureFrom { subject.complete(request(), "gpt-6-luna") {} }
        assertEquals(429, failure.statusCode)
        assertEquals(42L, failure.retryAfterSeconds)
    }

    @Test
    fun anUnparseableProviderResponseIsAnUpstreamFailure() = runBlocking {
        val (subject, _) = model(reply = "not json at all")
        val failure = failureFrom { subject.complete(request(), "gpt-6-luna") {} }
        assertEquals(502, failure.statusCode)
    }

    private fun request(reasoningLevel: String = "low") = GatewayRequest(
        modelId = "gpt-6-luna",
        instructions = "You plan edits.",
        messages = listOf(GatewayMessage(role = "user", text = "cut the boring part")),
        tools = listOf(
            GatewayTool(
                name = "remove_range",
                description = "Remove a range",
                parametersJson = """{"type":"object","properties":{"startMs":{"type":"integer"}},"additionalProperties":false}""",
            ),
        ),
        reasoningLevel = reasoningLevel,
        imageDetail = "low",
    )

    private companion object {
        val COMPLETED = """
            {
              "model": "gpt-6-luna",
              "status": "completed",
              "output": [
                {"type": "message", "role": "assistant", "content": [
                  {"type": "output_text", "text": "Two cuts planned."}
                ]},
                {"type": "reasoning", "summary": []}
              ],
              "usage": {"input_tokens": 120, "output_tokens": 40}
            }
        """.trimIndent()

        val WITH_TOOL_CALL = """
            {
              "model": "gpt-6-luna",
              "status": "completed",
              "output": [
                {"type": "reasoning", "summary": []},
                {"type": "message", "role": "assistant", "content": [
                  {"type": "output_text", "text": "Cutting the "},
                  {"type": "output_text", "text": "intro."}
                ]},
                {"type": "function_call", "call_id": "call-9", "name": "remove_range",
                 "arguments": "{\"startMs\":0,\"endMs\":500}"}
              ],
              "usage": {
                "input_tokens": 1000000,
                "output_tokens": 1000000,
                "output_tokens_details": {"reasoning_tokens": 500}
              }
            }
        """.trimIndent()

        val NO_MODEL = """
            {
              "status": "completed",
              "output": [],
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """.trimIndent()

        val INCOMPLETE = """
            {
              "model": "gpt-6-luna",
              "status": "incomplete",
              "incomplete_details": {"reason": "max_output_tokens"},
              "output": [],
              "usage": {"input_tokens": 5, "output_tokens": 1}
            }
        """.trimIndent()
    }
}
