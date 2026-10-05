package com.sekhar.helium.core.common

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/**
 * Logging abstraction.
 *
 * Production code must never pass transcripts, OCR text, frame data or any other
 * user media content to this interface — see `PRIVACY.md`. Log identifiers,
 * timings and error codes instead.
 */
interface HeliumLog {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = log(LogLevel.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, message, t)
}

/** Discards everything. Useful in unit tests. */
object NoOpLog : HeliumLog {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) = Unit
}

/** Minimal stdout logger. Android maps this onto `android.util.Log` at the edge. */
class PrintLog(private val minLevel: LogLevel = LogLevel.DEBUG) : HeliumLog {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        println("${level.name}/$tag: $message")
        throwable?.printStackTrace()
    }
}
