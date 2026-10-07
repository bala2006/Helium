package com.sekhar.helium.gateway

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

/**
 * Starts the Helium gateway.
 *
 * Reads `PORT` (so a platform can pick it), binds `0.0.0.0` so it is reachable
 * from a container network, and fails loudly at boot when a required secret is
 * missing instead of failing at the first user edit.
 */
fun main() {
    val log = LoggerFactory.getLogger("helium.gateway")
    val config = GatewayConfig.fromEnv()

    if (!config.providerConfigured) {
        log.error("HELIUM_PROVIDER_API_KEY is not set: /v1/generate cannot reach a model provider.")
    }
    if (config.sessionSecret.isBlank()) {
        log.error("HELIUM_SESSION_SECRET is not set: /v1/generate will answer 503 for every client.")
    }

    val client = OpenAiResponsesModel.defaultClient()
    val upstream = OpenAiResponsesModel(client, config)

    log.info(
        "starting helium-gateway host={} port={} providerConfigured={} defaultModel={}",
        config.host,
        config.port,
        config.providerConfigured,
        config.defaultModelId,
    )

    embeddedServer(Netty, port = config.port, host = config.host) {
        gatewayModule(config, upstream)
    }.start(wait = true)
}
