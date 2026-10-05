package com.sekhar.helium.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Coroutine dispatchers used by the app. Injectable so tests stay deterministic. */
interface AppDispatchers {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

/** Production dispatchers. [main] is lazy so pure-JVM tests can construct this safely. */
object DefaultAppDispatchers : AppDispatchers {
    override val main: CoroutineDispatcher by lazy { Dispatchers.Main }
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
}
