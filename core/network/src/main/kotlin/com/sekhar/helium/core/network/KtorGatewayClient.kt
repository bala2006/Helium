package com.sekhar.helium.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import com.sekhar.helium.core.common.AppDispatchers
import com.sekhar.helium.core.common.HeliumJson
import com.sekhar.helium.core.common.HeliumLog
import com.sekhar.helium.core.common.NoOpLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Builds the shared HTTP client.
 *
 * The engine is injected so the Android app supplies OkHttp while tests supply a
 * mock engine; this module stays free of any particular networking stack.
 */
fun heliumHttpClient(
    engine: HttpClientEngine,
    requestTimeoutMs: Long = 90_000L,
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(HeliumJson)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = requestTimeoutMs
        connectTimeoutMillis = 15_000L
        socketTimeoutMillis = requestTimeoutMs
    }
}

/**
 * Talks to the Helium gateway.
 *
 * Deliberately knows nothing about OpenAI or any other provider: it posts the
 * gateway protocol and maps transport failures onto [GatewayException] so the UI
 * can distinguish "no network" (keep editing locally) from "rate limited"
 * (retry shortly) from "signed out" (re-authenticate).
 */
class KtorGatewayClient(
    private val client: HttpClient,
    private val baseUrlProvider: () -> String,
    private val dispatchers: AppDispatchers,
    private val defaultSessionToken: () -> String? = { null },
    private val log: HeliumLog = NoOpLog,
    private val maxAttempts: Int = 3,
) : GatewayClient {

    override suspend fun generate(request: GatewayRequest): GatewayResponse = withContext(dispatchers.io) {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            throw GatewayException.BadRequest("The AI gateway address has not been configured.")
        }

        var attempt = 0
        var lastFailure: GatewayException? = null
        while (attempt < maxAttempts) {
            attempt++
            try {
                return@withContext post(baseUrl, request)
            } catch (rateLimited: GatewayException.RateLimited) {
                lastFailure = rateLimited
                val waitMs = rateLimited.retryAfterMs ?: backoffMs(attempt)
                log.w(TAG, "Gateway rate limited, retrying in ${waitMs}ms")
                delay(waitMs)
            } catch (server: GatewayException.Server) {
                lastFailure = server
                if (server.statusCode < 500) throw server
                log.w(TAG, "Gateway server error ${server.statusCode}, retrying")
                delay(backoffMs(attempt))
            } catch (offline: GatewayException.Offline) {
                lastFailure = offline
                delay(backoffMs(attempt))
            }
        }
        throw lastFailure ?: GatewayException.Server(0, "The AI request failed.")
    }

    override suspend fun ping(): Boolean = withContext(dispatchers.io) {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isEmpty()) return@withContext false
        runCatching { client.get("$baseUrl/health") { header(HttpHeaders.Accept, "application/json") } }
            .map { it.status.value in 200..299 }
            .getOrDefault(false)
    }

    private suspend fun post(baseUrl: String, request: GatewayRequest): GatewayResponse {
        val sessionToken = request.sessionToken ?: defaultSessionToken()
        val response = runCatching {
            client.post("$baseUrl/v1/generate") {
                contentType(ContentType.Application.Json)
                if (!sessionToken.isNullOrBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $sessionToken")
                }
                request.idempotencyKey?.let { header("Idempotency-Key", it) }
                setBody(request)
            }
        }.getOrElse { cause ->
            throw when (cause) {
                is HttpRequestTimeoutException -> GatewayException.Timeout(cause)
                is IOException -> GatewayException.Offline(cause)
                else -> GatewayException.Offline(cause)
            }
        }

        // Every non-2xx branch throws: `post` returns a parsed response or raises
        // one of the taxonomy types above, which is what lets `generate` retry
        // only the failures that are worth retrying.
        return when (response.status.value) {
            in 200..299 -> runCatching { response.body<GatewayResponse>() }
                .getOrElse { throw GatewayException.Malformed("Unreadable gateway response: ${it.message}", it) }

            401, 403 -> throw GatewayException.Unauthorized("Your Helium session has expired. Please sign in again.")

            400, 422 -> throw GatewayException.BadRequest(
                response.bodyAsText().take(200).ifBlank { "The AI request was rejected." },
            )

            408, 504 -> throw GatewayException.Timeout()

            429 -> throw GatewayException.RateLimited(
                response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.times(1000L),
            )

            else -> throw GatewayException.Server(
                response.status.value,
                response.bodyAsText().take(200).ifBlank { "AI gateway error ${response.status.value}" },
            )
        }
    }

    private fun backoffMs(attempt: Int): Long = (500L shl (attempt - 1)).coerceAtMost(8_000L)

    private companion object {
        const val TAG = "GatewayClient"
    }
}
