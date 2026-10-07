package com.sekhar.helium.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-session usage accounting.
 *
 * This is what makes the AI cost observable: every completed request records the
 * tokens the provider reported, the evidence images the app chose to send, and an
 * estimated cost. In-memory by default; implement [UsageLedger] over your
 * database to keep history across restarts.
 */
interface UsageLedger {
    fun record(sessionId: String, usage: GatewayUsage)

    fun summary(sessionId: String): UsageSummary

    fun total(): UsageSummary
}

class InMemoryUsageLedger : UsageLedger {

    private class Totals {
        val requests = AtomicLong()
        val inputTokens = AtomicLong()
        val outputTokens = AtomicLong()
        val reasoningTokens = AtomicLong()
        val imagesSent = AtomicLong()
        @Volatile var costUsd: Double = 0.0
    }

    private val perSession = ConcurrentHashMap<String, Totals>()

    override fun record(sessionId: String, usage: GatewayUsage) {
        val totals = perSession.computeIfAbsent(sessionId) { Totals() }
        totals.requests.incrementAndGet()
        totals.inputTokens.addAndGet(usage.inputTokens.toLong())
        totals.outputTokens.addAndGet(usage.outputTokens.toLong())
        totals.reasoningTokens.addAndGet(usage.reasoningTokens.toLong())
        totals.imagesSent.addAndGet(usage.imagesSent.toLong())
        totals.costUsd += usage.estimatedCostUsd
    }

    override fun summary(sessionId: String): UsageSummary {
        val totals = perSession[sessionId] ?: return UsageSummary(sessionId, 0, 0, 0, 0, 0, 0.0)
        return totals.toSummary(sessionId)
    }

    override fun total(): UsageSummary = perSession.entries.fold(UsageSummary("all", 0, 0, 0, 0, 0, 0.0)) { acc, (id, totals) ->
        val one = totals.toSummary(id)
        UsageSummary(
            sessionId = "all",
            requests = acc.requests + one.requests,
            inputTokens = acc.inputTokens + one.inputTokens,
            outputTokens = acc.outputTokens + one.outputTokens,
            reasoningTokens = acc.reasoningTokens + one.reasoningTokens,
            imagesSent = acc.imagesSent + one.imagesSent,
            estimatedCostUsd = acc.estimatedCostUsd + one.estimatedCostUsd,
        )
    }

    private fun Totals.toSummary(sessionId: String) = UsageSummary(
        sessionId = sessionId,
        requests = requests.get().toInt(),
        inputTokens = inputTokens.get(),
        outputTokens = outputTokens.get(),
        reasoningTokens = reasoningTokens.get(),
        imagesSent = imagesSent.get(),
        estimatedCostUsd = costUsd,
    )
}

/**
 * Provider prices in USD per million tokens.
 *
 * Deliberately a table rather than provider-reported cost: the app displays an
 * estimate, and an unknown model reports zero instead of inventing a number.
 */
object ModelPricing {
    private data class Price(val inputPerMillion: Double, val outputPerMillion: Double)

    private val prices = mapOf(
        "gpt-6-luna" to Price(0.25, 2.00),
        "gpt-6-luna-deep" to Price(1.25, 10.00),
    )

    fun estimateUsd(modelId: String, inputTokens: Int, outputTokens: Int): Double {
        val price = prices[modelId] ?: return 0.0
        return inputTokens / 1_000_000.0 * price.inputPerMillion +
            outputTokens / 1_000_000.0 * price.outputPerMillion
    }
}
