package com.filestech.sms.sync

import io.netty.util.internal.logging.InternalLogLevel
import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import timber.log.Timber

/**
 * Routes Netty's internal logging to Timber.
 *
 * Netty logs through its own factory: on the JVM it finds java.util.logging,
 * but on Android JUL goes nowhere and SLF4J has no binding, so Netty is
 * silent by default. That silence hid the 2026-11-14 TLS handshake failure
 * completely — the server closed the connection with no log line anywhere.
 * Installing this factory before the engine starts makes Netty's warnings
 * (including SslHandler handshake failures, with their stack traces) show
 * up in logcat under the originating class's simple name.
 *
 * Debug-only in effect: release builds strip Timber calls at the bytecode
 * level and plant a no-op tree, so the bridge costs nothing there.
 */
fun installNettyTimberLogging() {
    InternalLoggerFactory.setDefaultFactory(NettyTimberLoggerFactory)
}

internal object NettyTimberLoggerFactory : InternalLoggerFactory() {
    override fun newInstance(name: String): InternalLogger = TimberInternalLogger(name)
}

/**
 * An [InternalLogger] backed by Timber. Trace is disabled — Netty's trace
 * logs every buffer operation and would drown the log. Debug and above are
 * enabled: the handshake path logs at debug/warn.
 *
 * The function count is the interface's, not ours: InternalLogger declares
 * 44 methods and every one needs an implementation.
 */
@Suppress("TooManyFunctions")
internal class TimberInternalLogger(name: String) : InternalLogger {

    // Logcat tags truncate, so use the simple class name (SslHandler)
    // instead of the fully-qualified one.
    private val tag = name.substringAfterLast('.')

    override fun name(): String = tag

    override fun isTraceEnabled(): Boolean = false

    override fun isDebugEnabled(): Boolean = true

    override fun isInfoEnabled(): Boolean = true

    override fun isWarnEnabled(): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    override fun isEnabled(level: InternalLogLevel): Boolean = when (level) {
        InternalLogLevel.TRACE -> false
        InternalLogLevel.DEBUG,
        InternalLogLevel.INFO,
        InternalLogLevel.WARN,
        InternalLogLevel.ERROR,
        -> true
    }

    override fun trace(message: String) { /* disabled */ }

    override fun trace(message: String, arg: Any?) { /* disabled */ }

    override fun trace(message: String, arg1: Any?, arg2: Any?) { /* disabled */ }

    override fun trace(message: String, vararg args: Any?) { /* disabled */ }

    override fun trace(message: String, cause: Throwable) { /* disabled */ }

    override fun trace(cause: Throwable) { /* disabled */ }

    override fun debug(message: String) {
        Timber.tag(tag).d(message)
    }

    override fun debug(message: String, arg: Any?) {
        Timber.tag(tag).d(format(message, arrayOf(arg)))
    }

    override fun debug(message: String, arg1: Any?, arg2: Any?) {
        Timber.tag(tag).d(format(message, arrayOf(arg1, arg2)))
    }

    override fun debug(message: String, vararg args: Any?) {
        dispatch(InternalLogLevel.DEBUG, message, args)
    }

    override fun debug(message: String, cause: Throwable) {
        Timber.tag(tag).d(cause, message)
    }

    override fun debug(cause: Throwable) {
        Timber.tag(tag).d(cause, "Exception")
    }

    override fun info(message: String) {
        Timber.tag(tag).i(message)
    }

    override fun info(message: String, arg: Any?) {
        Timber.tag(tag).i(format(message, arrayOf(arg)))
    }

    override fun info(message: String, arg1: Any?, arg2: Any?) {
        Timber.tag(tag).i(format(message, arrayOf(arg1, arg2)))
    }

    override fun info(message: String, vararg args: Any?) {
        dispatch(InternalLogLevel.INFO, message, args)
    }

    override fun info(message: String, cause: Throwable) {
        Timber.tag(tag).i(cause, message)
    }

    override fun info(cause: Throwable) {
        Timber.tag(tag).i(cause, "Exception")
    }

    override fun warn(message: String) {
        Timber.tag(tag).w(message)
    }

    override fun warn(message: String, arg: Any?) {
        Timber.tag(tag).w(format(message, arrayOf(arg)))
    }

    override fun warn(message: String, vararg args: Any?) {
        dispatch(InternalLogLevel.WARN, message, args)
    }

    override fun warn(message: String, arg1: Any?, arg2: Any?) {
        Timber.tag(tag).w(format(message, arrayOf(arg1, arg2)))
    }

    override fun warn(message: String, cause: Throwable) {
        Timber.tag(tag).w(cause, message)
    }

    override fun warn(cause: Throwable) {
        Timber.tag(tag).w(cause, "Exception")
    }

    override fun error(message: String) {
        Timber.tag(tag).e(message)
    }

    override fun error(message: String, arg: Any?) {
        Timber.tag(tag).e(format(message, arrayOf(arg)))
    }

    override fun error(message: String, arg1: Any?, arg2: Any?) {
        Timber.tag(tag).e(format(message, arrayOf(arg1, arg2)))
    }

    override fun error(message: String, vararg args: Any?) {
        dispatch(InternalLogLevel.ERROR, message, args)
    }

    override fun error(message: String, cause: Throwable) {
        Timber.tag(tag).e(cause, message)
    }

    override fun error(cause: Throwable) {
        Timber.tag(tag).e(cause, "Exception")
    }

    override fun log(level: InternalLogLevel, message: String) {
        when (level) {
            InternalLogLevel.TRACE -> { /* disabled */ }
            InternalLogLevel.DEBUG -> debug(message)
            InternalLogLevel.INFO -> info(message)
            InternalLogLevel.WARN -> warn(message)
            InternalLogLevel.ERROR -> error(message)
        }
    }

    override fun log(level: InternalLogLevel, message: String, arg: Any?) {
        when (level) {
            InternalLogLevel.TRACE -> { /* disabled */ }
            InternalLogLevel.DEBUG -> debug(message, arg)
            InternalLogLevel.INFO -> info(message, arg)
            InternalLogLevel.WARN -> warn(message, arg)
            InternalLogLevel.ERROR -> error(message, arg)
        }
    }

    override fun log(level: InternalLogLevel, message: String, arg1: Any?, arg2: Any?) {
        when (level) {
            InternalLogLevel.TRACE -> { /* disabled */ }
            InternalLogLevel.DEBUG -> debug(message, arg1, arg2)
            InternalLogLevel.INFO -> info(message, arg1, arg2)
            InternalLogLevel.WARN -> warn(message, arg1, arg2)
            InternalLogLevel.ERROR -> error(message, arg1, arg2)
        }
    }

    override fun log(level: InternalLogLevel, message: String, vararg args: Any?) {
        dispatch(level, message, args)
    }

    /**
     * Shared vararg handling for the per-level methods and [log]: a
     * trailing Throwable is the cause (SLF4J convention), not a format
     * argument. Takes the array directly so callers never spread.
     */
    private fun dispatch(level: InternalLogLevel, message: String, args: Array<out Any?>) {
        val cause = args.lastOrNull() as? Throwable
        val values = if (cause != null) args.dropLast(1).toTypedArray() else args
        val text = format(message, values)
        when (level) {
            InternalLogLevel.TRACE -> { /* disabled */ }
            InternalLogLevel.DEBUG -> if (cause != null) Timber.tag(tag).d(cause, text) else Timber.tag(tag).d(text)
            InternalLogLevel.INFO -> if (cause != null) Timber.tag(tag).i(cause, text) else Timber.tag(tag).i(text)
            InternalLogLevel.WARN -> if (cause != null) Timber.tag(tag).w(cause, text) else Timber.tag(tag).w(text)
            InternalLogLevel.ERROR -> if (cause != null) Timber.tag(tag).e(cause, text) else Timber.tag(tag).e(text)
        }
    }

    override fun log(level: InternalLogLevel, message: String, cause: Throwable) {
        when (level) {
            InternalLogLevel.TRACE -> { /* disabled */ }
            InternalLogLevel.DEBUG -> debug(message, cause)
            InternalLogLevel.INFO -> info(message, cause)
            InternalLogLevel.WARN -> warn(message, cause)
            InternalLogLevel.ERROR -> error(message, cause)
        }
    }

    override fun log(level: InternalLogLevel, cause: Throwable) {
        when (level) {
            InternalLogLevel.TRACE -> { /* disabled */ }
            InternalLogLevel.DEBUG -> debug(cause)
            InternalLogLevel.INFO -> info(cause)
            InternalLogLevel.WARN -> warn(cause)
            InternalLogLevel.ERROR -> error(cause)
        }
    }

    /**
     * Netty formats with SLF4J-style `{}` placeholders; Timber uses
     * String.format, so substitute the placeholders here. Extra args are
     * appended rather than dropped.
     */
    private fun format(message: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return message
        val out = StringBuilder(message.length + 16)
        var argIndex = 0
        var i = 0
        while (i < message.length) {
            if (isPlaceholder(message, i) && argIndex < args.size) {
                out.append(args[argIndex++])
                i += 2
            } else {
                out.append(message[i])
                i++
            }
        }
        while (argIndex < args.size) {
            out.append(" [").append(args[argIndex++]).append(']')
        }
        return out.toString()
    }

    private fun isPlaceholder(message: String, i: Int): Boolean =
        i + 1 < message.length && message[i] == '{' && message[i + 1] == '}'
}
