package com.sekhar.helium.gateway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatewayRoutesTest {

    private fun testConfig(
        providerApiKey: String = "test-provider-key",
        sessionSecret: String = "test-session-secret",
        requestsPerMinute: Int = 200,
        maxImagesPerRequest: Int = 8,
        maxRequestBytes: Long = 1_000_000,
        idempotencyTtlSeconds: Long = 60,
    ) = GatewayConfig(
        providerBaseUrl = "https://provider.invalid/v1",
        providerApiKey = providerApiKey,
        defaultModelId = "gpt-6-luna",
        sessionSecret = sessionSecret,
        requestsPerMinute = requestsPerMinute,
        maxImagesPerRequest = maxImagesPerRequest,
        maxRequestBytes = maxRequestBytes,
        idempotencyTtlSeconds = idempotencyTtlSeconds,
    )

    /** Records what the routes asked the provider to do, and returns a scripted reply. */
    private class FakeUpstream(
        private val response: GatewayResponse = GatewayResponse(
            modelId = "gpt-6-luna",
            outputText = "Two cuts planned.",
            usage = GatewayUsage(inputTokens = 120, outputTokens = 40, reasoningTokens = 10),
            finishReason = "completed",
        ),
        private val failure: UpstreamFailure? = null,
    ) : UpstreamModel {
        val requests = mutableListOf<GatewayRequest>()
        val modelIds = mutableListOf<String>()
        var reportedImagesSent = -1

        override suspend fun complete(
            request: GatewayRequest,
            modelId: String,
            imagesSent: (Int) -> Unit,
        ): GatewayResponse {
            requests += request
            modelIds += modelId
            if (failure != null) throw failure
            reportedImagesSent = request.messages.sumOf { message ->
                // Mirrors the real provider: only user turns can carry evidence.
                if (message.toolCalls.isEmpty() && message.toolResultJson == null) message.images.size else 0
            }
            imagesSent(reportedImagesSent)
            return response
        }
    }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(GatewayJson) }
    }

    private fun token(sessions: SessionTokens, clientId: String = "client-1"): String =
        sessions.mint(clientId).sessionToken

    // ------------------------------------------------------------------ health

    @Test
    fun healthReportsAConfiguredProvider() = testApplication {
        application { gatewayModule(testConfig(), FakeUpstream()) }

        val response = jsonClient().get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val health = response.body<HealthResponse>()
        assertEquals("ok", health.status)
        assertTrue(health.providerConfigured)
    }

    @Test
    fun healthStaysOkWhenTheProviderKeyIsMissing() = testApplication {
        // The app's reachability probe distinguishes "no gateway" from "gateway
        // without a key", so health must answer 200 either way.
        application { gatewayModule(testConfig(providerApiKey = ""), FakeUpstream()) }

        val response = jsonClient().get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(!response.body<HealthResponse>().providerConfigured)
    }

    // -------------------------------------------------------------- session/auth

    @Test
    fun mintedSessionTokenVerifiesAndExpires() {
        val sessions = SessionTokens("secret", lifetimeSeconds = 100)
        val minted = sessions.mint("client-1", nowEpochSeconds = 1_000)

        assertEquals("client-1", sessions.verify(minted.sessionToken, nowEpochSeconds = 1_050))
        assertEquals(1_100L, minted.expiresAtEpochSeconds)

        // Expired, tampered, truncated, and wrong-key tokens all fail closed.
        assertEquals(null, sessions.verify(minted.sessionToken, nowEpochSeconds = 1_101))
        assertEquals(null, sessions.verify("${minted.sessionToken}x", nowEpochSeconds = 1_050))
        assertEquals(null, sessions.verify("not-a-token", nowEpochSeconds = 1_050))
        assertEquals(null, SessionTokens("other", 100).verify(minted.sessionToken, 1_050))
        assertEquals(null, SessionTokens("", 100).verify(minted.sessionToken, 1_050))
    }

    @Test
    fun generateWithoutASessionTokenIsUnauthorized() = testApplication {
        val upstream = FakeUpstream()
        application { gatewayModule(testConfig(), upstream) }

        val response = jsonClient().post("/v1/generate") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(upstream.requests.isEmpty(), "an unauthenticated request must never reach the provider")
    }

    @Test
    fun generateWithAForgedTokenIsUnauthorized() = testApplication {
        val upstream = FakeUpstream()
        application { gatewayModule(testConfig(), upstream) }

        val response = jsonClient().post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer forged.token.value")
            setBody(request())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(upstream.requests.isEmpty())
    }

    @Test
    fun generateIsUnavailableWhenTheSigningSecretIsMissing() = testApplication {
        val upstream = FakeUpstream()
        application { gatewayModule(testConfig(sessionSecret = ""), upstream) }

        val response = jsonClient().post("/v1/generate") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(upstream.requests.isEmpty())
    }

    @Test
    fun sessionEndpointMintsATokenOnlyWhenTheSecretIsSet() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        application { gatewayModule(config, FakeUpstream(), sessions = sessions) }
        val client = jsonClient()

        val minted = client.post("/v1/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("device-7"))
        }
        assertEquals(HttpStatusCode.OK, minted.status)
        assertEquals("device-7", sessions.verify(minted.body<SessionResponse>().sessionToken))

        val blank = client.post("/v1/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("   "))
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
    }

    @Test
    fun sessionEndpointIsDisabledWithoutASigningSecret() = testApplication {
        application { gatewayModule(testConfig(sessionSecret = ""), FakeUpstream()) }

        val disabled = jsonClient().post("/v1/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("device-7"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, disabled.status)
    }

    // ------------------------------------------------------------------- proxy

    @Test
    fun anAuthenticatedRequestIsProxiedAndAccounted() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        val ledger = InMemoryUsageLedger()
        application { gatewayModule(config, upstream, sessions = sessions, ledger = ledger) }
        val client = jsonClient()
        val bearer = token(sessions)

        val response = client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $bearer")
            setBody(request())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<GatewayResponse>()
        assertEquals("Two cuts planned.", body.outputText)
        assertEquals(120, body.usage.inputTokens)
        assertEquals(1, upstream.requests.size)
        assertEquals("gpt-6-luna", upstream.modelIds.single())

        val summary = client.get("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
        }.body<UsageSummary>()
        assertEquals(1, summary.requests)
        assertEquals(120L, summary.inputTokens)
        assertEquals(40L, summary.outputTokens)

        // The body token is an accepted alternative to the header, and the
        // provider's own key never appears anywhere in the response.
        assertTrue(!response.bodyAsText().contains(config.providerApiKey))
    }

    @Test
    fun aBlankModelIdFallsBackToTheConfiguredDefault() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        application { gatewayModule(config, upstream, sessions = sessions) }

        jsonClient().post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${token(sessions)}")
            setBody(request().copy(modelId = "  "))
        }

        assertEquals(config.defaultModelId, upstream.modelIds.single())
    }

    @Test
    fun usageAndIdempotencyAreScopedToTheSession() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        val ledger = InMemoryUsageLedger()
        application { gatewayModule(config, upstream, sessions = sessions, ledger = ledger) }
        val client = jsonClient()
        val alice = token(sessions, "alice")
        val bob = token(sessions, "bob")

        client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $alice")
            setBody(request(idempotencyKey = "shared-key"))
        }

        // Bob reusing Alice's key gets his own call, not her stored response.
        client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $bob")
            setBody(request(idempotencyKey = "shared-key"))
        }
        assertEquals(2, upstream.requests.size)

        assertEquals(1, client.get("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer $alice")
        }.body<UsageSummary>().requests)
        assertEquals(1, client.get("/v1/usage") {
            header(HttpHeaders.Authorization, "Bearer $bob")
        }.body<UsageSummary>().requests)
    }

    // ------------------------------------------------------- limits & failures

    @Test
    fun aRepeatedIdempotencyKeyReplaysWithoutCallingTheProvider() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        application { gatewayModule(config, upstream, sessions = sessions) }
        val client = jsonClient()
        val bearer = token(sessions)

        repeat(2) {
            val response = client.post("/v1/generate") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $bearer")
                setBody(request(idempotencyKey = "op-42"))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Two cuts planned.", response.body<GatewayResponse>().outputText)
        }

        assertEquals(1, upstream.requests.size, "a replayed key must not be charged twice")
    }

    @Test
    fun exceedingThePerSessionBudgetReturnsRetryAfter() = testApplication {
        val config = testConfig(requestsPerMinute = 1)
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        application {
            gatewayModule(
                config,
                upstream,
                sessions = sessions,
                // A fresh window: the deployed default would be shared across tests.
                perSessionLimiter = SlidingWindowRateLimiter(limit = 1),
            )
        }
        val client = jsonClient()
        val bearer = token(sessions)

        val first = client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $bearer")
            setBody(request())
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $bearer")
            setBody(request())
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        val retryAfter = second.headers[HttpHeaders.RetryAfter]
        assertNotNull(retryAfter, "a throttled client must be told when to retry")
        assertTrue(retryAfter.toLong() >= 1L)
        assertEquals(1, upstream.requests.size)
    }

    @Test
    fun theGlobalBudgetAlsoApplies() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        application {
            gatewayModule(
                config,
                upstream,
                sessions = sessions,
                globalLimiter = SlidingWindowRateLimiter(limit = 1),
            )
        }
        val client = jsonClient()

        // Two different sessions: only the deployment-wide budget can refuse the second.
        client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${token(sessions, "alice")}")
            setBody(request())
        }
        val refused = client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${token(sessions, "bob")}")
            setBody(request())
        }
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
    }

    @Test
    fun providerThrottlingIsPassedThroughWithRetryAfter() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream(
            failure = UpstreamFailure(429, "slow down", retryAfterSeconds = 30),
        )
        application { gatewayModule(config, upstream, sessions = sessions) }

        val response = jsonClient().post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${token(sessions)}")
            setBody(request())
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("30", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun aProviderRejectionBecomesBadGatewayNotAClientError() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream(failure = UpstreamFailure(400, "bad model name"))
        application { gatewayModule(config, upstream, sessions = sessions) }

        val response = jsonClient().post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${token(sessions)}")
            setBody(request())
        }

        // The app treats 4xx as its own bug and does not retry, so a provider-side
        // rejection must surface as a gateway fault (5xx) instead.
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun aMalformedBodyIsRejectedBeforeTheProviderIsCalled() = testApplication {
        val config = testConfig()
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        application { gatewayModule(config, upstream, sessions = sessions) }
        val client = jsonClient()

        val response = client.post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${token(sessions)}")
            setBody("{ this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.requests.isEmpty())
    }

    @Test
    fun anOversizedBodyIsRejectedBeforeItIsParsed() = testApplication {
        val config = testConfig(maxRequestBytes = 200)
        val sessions = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds)
        val upstream = FakeUpstream()
        application { gatewayModule(config, upstream, sessions = sessions) }

        val response = jsonClient().post("/v1/generate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${token(sessions)}")
            setBody(request().copy(instructions = "x".repeat(1_000)))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(upstream.requests.isEmpty())
    }

    private fun request(idempotencyKey: String? = null) = GatewayRequest(
        modelId = "gpt-6-luna",
        instructions = "You plan edits.",
        messages = listOf(GatewayMessage(role = "user", text = "cut the boring part")),
        tools = listOf(
            GatewayTool(
                name = "remove_range",
                description = "Remove a range",
                parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
            ),
        ),
        reasoningLevel = "low",
        idempotencyKey = idempotencyKey,
    )
}
