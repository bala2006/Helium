package com.sekhar.helium.gateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val GLOBAL_LIMITER_KEY = "__gateway__"

/**
 * The Helium gateway.
 *
 * Responsibilities, in the order they are applied to every request:
 *
 * 1. **Bound the payload** — a request larger than `HELIUM_MAX_REQUEST_BYTES` is
 *    rejected before it is parsed.
 * 2. **Authenticate** — `Authorization: Bearer <session token>` (or the
 *    `sessionToken` field, for clients that cannot set headers). No valid token,
 *    no proxy call.
 * 3. **Rate limit** — deployment-wide first, then per session, both reported with
 *    an exact `Retry-After`.
 * 4. **Replay** — a repeated `Idempotency-Key` returns the stored response, so a
 *    client retry is never charged twice.
 * 5. **Proxy** — [UpstreamModel] translates the stable protocol to the provider.
 * 6. **Account** — tokens, images and estimated cost are recorded per session.
 *
 * The provider key is read once from the environment and used only by
 * [UpstreamModel]; it is never echoed, logged, or accepted from a client.
 */
fun Application.gatewayModule(
    config: GatewayConfig,
    upstream: UpstreamModel,
    sessions: SessionTokens = SessionTokens(config.sessionSecret, config.sessionLifetimeSeconds),
    perSessionLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(config.requestsPerMinute),
    globalLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(config.globalRequestsPerMinute),
    idempotency: IdempotencyStore = IdempotencyStore(config.idempotencyTtlSeconds),
    ledger: UsageLedger = InMemoryUsageLedger(),
    log: Logger = LoggerFactory.getLogger("helium.gateway"),
) {
    install(ContentNegotiation) {
        json(GatewayJson)
    }
    install(DefaultHeaders)

    install(CallLogging) {
        // One line per request, key=value so it stays greppable in plain text and
        // still parses if a log pipeline is added later.
        format { call ->
            "http method=${call.request.httpMethod.value} path=${call.request.path()} " +
                "status=${call.response.status()?.value ?: 0}"
        }
    }

    install(StatusPages) {
        exception<UpstreamFailure> { call, cause ->
            if (cause.statusCode == 429) {
                cause.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("rate_limited", "The model provider is throttling requests."),
                )
            } else {
                log.warn("upstream failure status={} detail={}", cause.statusCode, cause.message)
                call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("upstream_error", "The model provider rejected the request."),
                )
            }
        }
        exception<Throwable> { call, cause ->
            log.error("unhandled failure path={}", call.request.path(), cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
    }

    routing {
        // Liveness plus the one fact an operator needs: is the provider key wired?
        // Deliberately 200 even when it is not, so the app's "test connection" can
        // distinguish "unreachable" from "reachable but unconfigured".
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    providerConfigured = config.providerConfigured,
                ),
            )
        }

        /**
         * Mints a session token.
         *
         * This is the seam for real authentication. Helium ships without accounts,
         * so the gateway signs whatever `clientId` it is given; a deployment that
         * has users must verify them here (or in front of this route) before
         * calling [SessionTokens.mint]. The signing key is still required, so
         * tokens cannot be forged either way.
         */
        post("/v1/session") {
            if (!sessions.configured) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("not_configured", "HELIUM_SESSION_SECRET is not set."),
                )
            }
            val body = runCatching { GatewayJson.decodeFromString<SessionRequest>(call.receiveText()) }
                .getOrElse {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid_request", "Expected {\"clientId\": \"...\"}."),
                    )
                }
            val clientId = body.clientId.trim()
            if (clientId.isEmpty() || clientId.length > 128) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "clientId must be 1..128 characters."),
                )
            }
            call.respond(sessions.mint(clientId))
        }

        post("/v1/generate") {
            val raw = call.receiveText()
            if (raw.length > config.maxRequestBytes) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("request_too_large", "The request exceeded ${config.maxRequestBytes} bytes."),
                )
            }
            val request = runCatching { GatewayJson.decodeFromString<GatewayRequest>(raw) }
                .getOrElse {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid_request", it.message?.take(200)),
                    )
                }

            val sessionId = resolveSession(call, request, sessions)
            if (sessionId == null) {
                if (!sessions.configured) {
                    return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("not_configured", "HELIUM_SESSION_SECRET is not set."),
                    )
                }
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("unauthorized", "Missing or expired session token."),
                )
            }

            val waitSeconds = globalLimiter.check(GLOBAL_LIMITER_KEY)
                ?: perSessionLimiter.check(sessionId)
            if (waitSeconds != null) {
                call.response.header(HttpHeaders.RetryAfter, waitSeconds.toString())
                return@post call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("rate_limited", "Too many requests; retry in ${waitSeconds}s."),
                )
            }

            idempotency.get(request.idempotencyKey, sessionId)?.let { replayed ->
                log.info("replayed idempotent request session={}", sessionId)
                return@post call.respond(replayed)
            }

            val modelId = request.modelId.takeIf { it.isNotBlank() } ?: config.defaultModelId

            var imagesSent = 0
            // An UpstreamFailure is rethrown for StatusPages to translate; it must
            // not be swallowed here or the client would see a 200 with no content.
            val response = upstream.complete(request, modelId) { imagesSent = it }
            val accounted = response.copy(
                usage = response.usage.copy(imagesSent = imagesSent),
            )

            ledger.record(sessionId, accounted.usage)
            idempotency.put(request.idempotencyKey, sessionId, accounted)
            log.info(
                "generate session={} model={} toolCalls={} inputTokens={} outputTokens={} images={}",
                sessionId,
                modelId,
                accounted.toolCalls.size,
                accounted.usage.inputTokens,
                accounted.usage.outputTokens,
                imagesSent,
            )
            call.respond(accounted)
        }

        get("/v1/usage") {
            val sessionId = bearerSession(call, sessions)
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("unauthorized", "Missing or expired session token."),
                )
            call.respond(ledger.summary(sessionId))
        }
    }
}

/** Accepts the token from the body field or the standard header, never both. */
private fun resolveSession(
    call: io.ktor.server.application.ApplicationCall,
    request: GatewayRequest,
    sessions: SessionTokens,
): String? = request.sessionToken?.takeIf { it.isNotBlank() }?.let { sessions.verify(it) }
    ?: bearerSession(call, sessions)

private fun bearerSession(
    call: io.ktor.server.application.ApplicationCall,
    sessions: SessionTokens,
): String? = call.request.header(HttpHeaders.Authorization)
    ?.removePrefix("Bearer ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { sessions.verify(it) }
