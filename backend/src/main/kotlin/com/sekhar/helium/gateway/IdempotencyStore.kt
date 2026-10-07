package com.sekhar.helium.gateway

/**
 * Replay cache for `Idempotency-Key`.
 *
 * The app supplies a key on every request so a retry after a timeout is never
 * charged twice. Entries are scoped by session: one client must not be able to
 * read another's response by guessing a key.
 *
 * Completed responses only. Two truly concurrent calls with the same key both
 * reach the provider — the client already serialises its own turns, and a promise
 * table here would add a failure mode (a crashed first call blocking the second
 * forever) for no benefit at this scale. See `ARCHITECTURE.md`.
 */
class IdempotencyStore(
    private val ttlSeconds: Long,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private data class Entry(val sessionId: String, val expiresAt: Long, val response: GatewayResponse)

    private val entries = HashMap<String, Entry>()
    private val lock = Any()

    fun get(key: String?, sessionId: String): GatewayResponse? {
        if (key.isNullOrBlank()) return null
        synchronized(lock) {
            val entry = entries[key] ?: return null
            if (entry.expiresAt <= clock() || entry.sessionId != sessionId) {
                entries.remove(key)
                return null
            }
            return entry.response
        }
    }

    fun put(key: String?, sessionId: String, response: GatewayResponse) {
        if (key.isNullOrBlank()) return
        synchronized(lock) {
            entries[key] = Entry(sessionId, clock() + ttlSeconds, response)
        }
    }

    /** Drops expired keys so the map cannot grow without bound. */
    fun prune() {
        val now = clock()
        synchronized(lock) {
            entries.entries.removeAll { (_, entry) -> entry.expiresAt <= now }
        }
    }
}
