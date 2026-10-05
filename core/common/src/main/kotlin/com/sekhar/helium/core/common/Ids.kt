package com.sekhar.helium.core.common

import java.util.UUID

/**
 * Source of unique identifiers.
 *
 * Abstracted so that tests (and deterministic re-imports) can supply stable ids.
 */
fun interface IdGenerator {
    fun newId(): String
}

/** Default [IdGenerator] backed by random UUIDs. */
object UuidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** Deterministic [IdGenerator] used by tests and fixtures. */
class SequentialIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun newId(): String = "$prefix-${++counter}"
}
