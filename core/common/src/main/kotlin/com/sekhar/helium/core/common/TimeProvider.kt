package com.sekhar.helium.core.common

/** Wall-clock abstraction so timestamps are testable. */
fun interface TimeProvider {
    fun nowEpochMs(): Long
}

/** Production [TimeProvider]. */
object SystemTimeProvider : TimeProvider {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

/** Test [TimeProvider] with a manually advanced clock. */
class FixedTimeProvider(private var now: Long = 0L) : TimeProvider {
    override fun nowEpochMs(): Long = now

    fun advance(byMs: Long) {
        now += byMs
    }
}
