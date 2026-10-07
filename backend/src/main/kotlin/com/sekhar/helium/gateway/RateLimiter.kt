package com.sekhar.helium.gateway

/**
 * Sliding-window rate limiter.
 *
 * Two budgets are enforced: one per session, and a deployment-wide ceiling so a
 * single noisy client cannot exhaust the provider quota for everyone. A refused
 * request reports the exact wait in seconds, which the gateway returns as
 * `Retry-After` and the app's client turns into a backoff.
 *
 * State is in-process. A multi-instance deployment must put this behind a shared
 * store (Redis or the platform's limiter); [RateLimiter] is an interface so that
 * swap does not touch the routes.
 */
interface RateLimiter {
    /** @return null when the call may proceed, otherwise the seconds to wait. */
    fun check(key: String): Long?
}

class SlidingWindowRateLimiter(
    private val limit: Int,
    private val windowSeconds: Long = 60,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : RateLimiter {

    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val lock = Any()

    override fun check(key: String): Long? {
        if (limit <= 0) return null
        val now = clock()
        synchronized(lock) {
            val window = hits.getOrPut(key) { ArrayDeque() }
            while (window.isNotEmpty() && now - window.first() >= windowSeconds) {
                window.removeFirst()
            }
            if (window.size >= limit) {
                val oldest = window.first()
                return (oldest + windowSeconds - now).coerceAtLeast(1L)
            }
            window.addLast(now)
        }
        return null
    }

    /** Drops idle windows so a long-running gateway does not leak keys. */
    fun prune() {
        val now = clock()
        synchronized(lock) {
            hits.entries.removeAll { (_, window) ->
                window.isNotEmpty() && now - window.last() >= windowSeconds
            }
        }
    }
}
