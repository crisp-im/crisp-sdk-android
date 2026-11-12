package im.crisp.sdk.api

/**
 * Receives the logs of the SDK and its Web client, once registered with [Crisp.addLogger].
 *
 * While none is registered, logs go to Logcat.
 * Once one is registered, they only go to the registered loggers, until all of them are removed.
 *
 * Register it early, e.g. to forward warnings and errors to your own logging tool, the others being
 * filtered out by default by [Crisp.setLogLevel], whether a logger is registered or not:
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Crisp.addLogger { priority, tag, message ->
 *             crashReporter.log("$tag: $message")
 *         }
 *     }
 * }
 * ```
 *
 * To also keep them in Logcat, forward them there:
 * ```
 * class ForwardingLogger(private val crashReporter: CrashReporter) : CrispLogger {
 *     override fun log(priority: Int, tag: String, message: String) {
 *         Log.println(priority, tag, message)
 *         crashReporter.log("$tag: $message")
 *     }
 * }
 * ```
 *
 * To silence the SDK, without any logger: `Crisp.setLogLevel(Int.MAX_VALUE)`.
 *
 * The test app has a [logger](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/logs/CatalogLogger.kt),
 * registered from its [Application](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/CatalogApplication.kt).
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
