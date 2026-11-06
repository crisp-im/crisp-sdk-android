package im.crisp.sdk.api

import android.content.Context
import im.crisp.sdk.BuildConfig
import im.crisp.sdk.internal.environment.registerLogger
import im.crisp.sdk.internal.environment.setMinLogPriority
import im.crisp.sdk.internal.environment.unregisterLogger
import im.crisp.sdk.internal.webview.CrispWebView

object Crisp {
    /**
     * Version of the Crisp SDK, e.g. `3.0.0`.
     */
    const val version: String = BuildConfig.VERSION

    /**
     * Registers [logger][CrispLogger] to receive the SDK and Web client logs, from every chatbox.
     *
     * While none is registered, logs go to Logcat.
     * Once one is registered, they only go to the registered loggers,
     * until all of them are removed with [removeLogger].
     * Register it early, so that no log is missed.
     *
     * Keep a reference to it in Kotlin if you pass a lambda, to be able to remove it.
     *
     * Does nothing if already registered.
     */
    fun addLogger(logger: CrispLogger) {
        registerLogger(logger)
    }

    /** Unregisters [logger][CrispLogger], which no longer receives logs. */
    fun removeLogger(logger: CrispLogger) {
        unregisterLogger(logger)
    }

    /**
     * Only logs the SDK and Web client logs of [priority] or higher, whether to Logcat or to the registered
     * [loggers][addLogger]. Defaults to [Log.VERBOSE][android.util.Log.VERBOSE], logging everything.
     *
     * The Web client `log` level maps to [Log.VERBOSE][android.util.Log.VERBOSE].
     *
     * @param priority one of the [Log][android.util.Log] priorities, e.g. [Log.WARN][android.util.Log.WARN]
     * to only log warnings and errors. Use a priority above [Log.ASSERT][android.util.Log.ASSERT],
     * e.g. [Int.MAX_VALUE], to log nothing.
     */
    fun setLogLevel(priority: Int) {
        setMinLogPriority(priority)
    }

    /**
     * Starts WebView up in the background ahead of time, so that the first chatbox shows faster:
     * the first WebView of the app process otherwise takes long to create, blocking the UI thread.
     *
     * Call it early, e.g. from [Application.onCreate][android.app.Application.onCreate].
     * Only the first call has an effect, or the first one running the UI thread tasks afterwards:
     * e.g. call it without them from [Application.onCreate][android.app.Application.onCreate],
     * not to delay the app start, then with them once the first screen is drawn.
     *
     * @param shouldRunUiThreadTasks whether to also run the start-up tasks that must run on the UI thread,
     * split into short tasks. Otherwise, they run when the first WebView is created.
     */
    @JvmOverloads
    fun preheat(context: Context, shouldRunUiThreadTasks: Boolean = true) {
        CrispWebView.startUp(context, shouldRunUiThreadTasks)
    }
}
