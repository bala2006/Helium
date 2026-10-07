package com.sekhar.helium.gateway

/**
 * Runtime configuration, read from the environment.
 *
 * Deployment contract: the provider API key exists ONLY here. It is never
 * compiled into the APK, never accepted from a client, and never logged.
 */
data class GatewayConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Upstream provider base URL, e.g. `https://api.openai.com/v1`. */
    val providerBaseUrl: String,
    /** The secret that talks to the provider. Empty means "not configured". */
    val providerApiKey: String,
    /** Fallback model when a client asks for one we do not know. */
    val defaultModelId: String = "gpt-6-luna",
    /** HMAC key for session tokens. */
    val sessionSecret: String,
    /** Lifetime of a minted session token. */
    val sessionLifetimeSeconds: Long = 12 * 60 * 60,
    /** Per-session request budget, enforced with a `Retry-After` response. */
    val requestsPerMinute: Int = 30,
    /** Hard cap on evidence images per request, whatever the client asks for. */
    val maxImagesPerRequest: Int = 8,
    /** Reject bodies larger than this before deserialising them. */
    val maxRequestBytes: Long = 8L * 1024 * 1024,
    /** How long a completed idempotent response stays replayable. */
    val idempotencyTtlSeconds: Long = 15 * 60,
    /** Origins allowed by CORS. The app is native, so this is for tooling only. */
    val allowedOrigins: List<String> = emptyList(),
    /** Deployment-wide ceiling on upstream requests per minute. */
    val globalRequestsPerMinute: Int = 600,
) {
    val providerConfigured: Boolean get() = providerApiKey.isNotBlank()

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): GatewayConfig = GatewayConfig(
            host = env("HELIUM_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
            port = env("PORT")?.toIntOrNull() ?: 8080,
            providerBaseUrl = env("HELIUM_PROVIDER_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: "https://api.openai.com/v1",
            providerApiKey = env("HELIUM_PROVIDER_API_KEY").orEmpty(),
            defaultModelId = env("HELIUM_DEFAULT_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-6-luna",
            sessionSecret = env("HELIUM_SESSION_SECRET").orEmpty(),
            sessionLifetimeSeconds = env("HELIUM_SESSION_LIFETIME_SECONDS")?.toLongOrNull()
                ?: (12 * 60 * 60),
            requestsPerMinute = env("HELIUM_REQUESTS_PER_MINUTE")?.toIntOrNull() ?: 30,
            maxImagesPerRequest = env("HELIUM_MAX_IMAGES_PER_REQUEST")?.toIntOrNull() ?: 8,
            maxRequestBytes = env("HELIUM_MAX_REQUEST_BYTES")?.toLongOrNull() ?: (8L * 1024 * 1024),
            idempotencyTtlSeconds = env("HELIUM_IDEMPOTENCY_TTL_SECONDS")?.toLongOrNull()
                ?: (15 * 60),
            allowedOrigins = env("HELIUM_ALLOWED_ORIGINS")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            globalRequestsPerMinute = env("HELIUM_GLOBAL_REQUESTS_PER_MINUTE")?.toIntOrNull() ?: 600,
        )
    }
}
