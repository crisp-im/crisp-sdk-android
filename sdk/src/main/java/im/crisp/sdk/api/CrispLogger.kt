package im.crisp.sdk.api

/**
 * Receives the logs of the SDK and its Web client, once registered with [Crisp.addLogger].
 *
 * While none is registered, logs go to Logcat.
 * Once one is registered, they only go to the registered loggers, until all of them are removed.
 */
fun interface CrispLogger {
    /**
     * Logs [message], as [Log.println][android.util.Log.println] does.
     *
     * May be called from any thread: implementations must be thread-safe.
     *
     * @param priority one of the [Log][android.util.Log] priorities, e.g. [Log.WARN][android.util.Log.WARN].
     * @param tag the source of the log, e.g. CrispClient for the Web client logs.
     * @param message the log message, followed by the stack trace of the error if any.
     */
    fun log(priority: Int, tag: String, message: String)
}
