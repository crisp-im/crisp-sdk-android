package im.crisp.sdk.internal.environment

import android.util.Log
import im.crisp.sdk.api.CrispLogger
import im.crisp.sdk.internal.bridge.LogEvent
import java.util.concurrent.CopyOnWriteArrayList

/** Tag of the logs coming from the Web client (`onLog` and `onScriptError` events). */
private const val CRISP_CLIENT_LOG_TAG = "CrispClient"

// Copied on write, so that loggers can be registered from any thread, even while logging
private val loggers = CopyOnWriteArrayList<CrispLogger>()

// Volatile, so that it can be set from any thread
@Volatile
private var minPriority = Log.VERBOSE

internal enum class Level(val priority: Int) {
    ERROR(Log.ERROR),
    WARN(Log.WARN),
    INFO(Log.INFO),
    DEBUG(Log.DEBUG),
    VERBOSE(Log.VERBOSE),
}

internal fun registerLogger(logger: CrispLogger) {
    loggers.addIfAbsent(logger)
}

internal fun unregisterLogger(logger: CrispLogger) {
    loggers.remove(logger)
}

internal fun setMinLogPriority(priority: Int) {
    minPriority = priority
}

/** Logs from the SDK. */
internal fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
    if (level.priority < minPriority) return
    val text = if (throwable == null) message else "$message\n${Log.getStackTraceString(throwable)}"
    println(level.priority, tag, text)
}

/** Logs coming from the Web client, whose levels are `error`, `warn`, `info`, `debug` and `log`. */
internal fun log(level: LogEvent.Level, namespace: String, value: String) {
    val priority = when (level) {
        LogEvent.Level.ERROR -> Level.ERROR
        LogEvent.Level.WARN -> Level.WARN
        LogEvent.Level.DEBUG -> Level.DEBUG
        LogEvent.Level.INFO -> Level.INFO
        LogEvent.Level.LOG -> Level.VERBOSE
    }.priority
    if (priority < minPriority) return
    val ns = namespace
        .split(']')
        .last { it.isNotEmpty() }
        .trimStart { it.isWhitespace() || it == '[' }
    println(priority, CRISP_CLIENT_LOG_TAG, "[$ns] $value")
}

/** Sends to the registered loggers, or to Logcat if none is registered. */
private fun println(priority: Int, tag: String, message: String) {
    // Iterates a snapshot, so that a log is not lost if the last logger is removed meanwhile
    var isLogged = false
    loggers.forEach {
        it.log(priority, tag, message)
        isLogged = true
    }
    if (!isLogged) {
        Log.println(priority, tag, message)
    }
}
