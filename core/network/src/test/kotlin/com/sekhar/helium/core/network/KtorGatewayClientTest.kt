package com.sekhar.helium.core.network

import com.sekhar.helium.core.common.AppDispatchers
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transport-level guarantees for the gateway client.
 *
 * The point of these tests is the failure taxonomy: the editor must be able to
 * tell "you are offline" (keep working locally) apart from "rate limited" (retry
 * soon) apart from "your session expired" (sign in again), because each one leads
 * to a different, user-visible behaviour.
 */
class KtorGatewayClientTest {

    private val dispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    private fun clientFor(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"modelId":"gpt-6-luna","outputText":"ok"}""",
        headers: Map<String, String> = emptyMap(),
        baseUrl: String = "https://gateway.test",
    ): KtorGatewayClient {
        val engine = MockEngine { request ->
            assertTrue(
                request.url.encodedPath.startsWith("/v1/generate") || request.url.encodedPath == "/health",
                "unexpected path ${request.url.encodedPath}",
            )
            respond(
                content = body,
                status = status,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    *headers.map { (key, value) -> key to listOf(value) }.toTypedArray(),
                ),
            )
        }
        return KtorGatewayClient(
            client = heliumHttpClient(engine),
            baseUrlProvider = { baseUrl },
            dispatchers = dispatchers,
        )
    }

    private fun request() = GatewayRequest(
        modelId = "gpt-6-luna",
        instructions = "You are Helium.",
        messages = listOf(GatewayMessage(role = "user", text = "make it a Reel")),
        idempotencyKey = "key-1",
    )

    @Test
    fun `a successful response is parsed into tool calls and usage`() = runTest {
        val client = clientFor(
            body = """
                {
                  "modelId": "gpt-6-luna",
                  "outputText": "removing 2 pauses",
                  "toolCalls": [
                    {"callId":"c1","name":"remove_range","argumentsJson":"{\"videoId\":\"s1\",\"startMs\":1000,\"endMs\":2000}"}
                  ],
                  "usage": {"inputTokens": 800, "outputTokens": 120, "imagesSent": 2},
                  "finishReason": "tool_calls"
                }
            """.trimIndent(),
        )

        val response = client.generate(request())

        assertEquals("gpt-6-luna", response.modelId)
        assertEquals(1, response.toolCalls.size)
        assertEquals("remove_range", response.toolCalls.first().name)
        assertEquals(800, response.usage.inputTokens)
        assertEquals(2, response.usage.imagesSent)
    }

    @Test
    fun `an unconfigured gateway is a bad request rather than a network error`() = runTest {
        val client = clientFor(baseUrl = "")

        val failure = assertFailsWith<GatewayException.BadRequest> { client.generate(request()) }
        assertTrue(failure.message.orEmpty().contains("not been configured"))
    }

    @Test
    fun `401 maps to an expired session`() = runTest {
        val client = clientFor(status = HttpStatusCode.Unauthorized, body = """{"error":"expired"}""")

        assertFailsWith<GatewayException.Unauthorized> { client.generate(request()) }
    }

    @Test
    fun `429 exposes the Retry-After hint and eventually gives up`() = runTest {
        val client = clientFor(
            status = HttpStatusCode.TooManyRequests,
            body = """{"error":"slow down"}""",
            headers = mapOf(HttpHeaders.RetryAfter to "2"),
        )

        // Retries are bounded, so a permanently throttled gateway terminates.
        assertFailsWith<GatewayException.RateLimited> { client.generate(request()) }
    }

    @Test
    fun `500 is retried then surfaced as a server error`() = runTest {
        val client = clientFor(status = HttpStatusCode.InternalServerError, body = """{"error":"boom"}""")

        assertFailsWith<GatewayException.Server> { client.generate(request()) }
    }

    @Test
    fun `a malformed body is reported as malformed, not as a server error`() = runTest {
        val client = clientFor(body = "not json at all")

        assertFailsWith<GatewayException.Malformed> { client.generate(request()) }
    }

    @Test
    fun `ping reports reachability without throwing`() = runTest {
        assertTrue(clientFor().ping())
        assertFalse(clientFor(status = HttpStatusCode.ServiceUnavailable, body = "").ping())
        assertFalse(clientFor(baseUrl = "").ping())
    }

    @Test
    fun `the wire request carries the idempotency key`() = runTest {
        var seenKey: String? = null
        var seenMethod: HttpMethod? = null
        val engine = MockEngine { request ->
            seenKey = request.headers["Idempotency-Key"]
            seenMethod = request.method
            respond(
                content = """{"modelId":"m","outputText":""}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = KtorGatewayClient(
            client = heliumHttpClient(engine),
            baseUrlProvider = { "https://gateway.test" },
            dispatchers = dispatchers,
        )

        client.generate(request())

        assertEquals("key-1", seenKey)
        assertEquals(HttpMethod.Post, seenMethod)
    }
}
