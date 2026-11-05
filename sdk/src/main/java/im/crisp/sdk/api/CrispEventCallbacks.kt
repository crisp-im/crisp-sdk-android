package im.crisp.sdk.api

import im.crisp.sdk.model.Message

/**
 * Notified of the events of a chatbox on the main thread, once registered with
 * [CrispChatboxController.addEventCallbacks], or in Compose with
 * [rememberCrispChatboxController][im.crisp.sdk.ui.rememberCrispChatboxController].
 *
 * Every method does nothing by default, override the ones you need.
 * In Kotlin, the `CrispEventCallbacks(…)` function creates them from lambdas.
 */
interface CrispEventCallbacks {
    /**
     * The chatbox is shown to the user: drawn while attached to a window and its host is started,
     * even while it loads or shows an error.
     *
     * Notified on the first frame drawn, so that callbacks registered meanwhile are notified,
     * e.g. from a Compose `DisposableEffect`.
     */
    fun onChatOpened() {}

    /**
     * The chatbox is no longer shown to the user: removed from its window (e.g. dismissed),
     * or its host is stopped (e.g. covered by another `Activity`, or the app went to background).
     */
    fun onChatClosed() {}

    /**
     * The Web client finished loading the session [sessionId]. Called again on each reload,
     * e.g. after a retry, or when the hosting `Activity` is recreated.
     */
    fun onSessionLoaded(sessionId: String) {}

    /** The user sent [message][Message]. Messages the public [Message] model cannot represent are skipped. */
    fun onMessageSent(message: Message) {}

    /** The user received [message][Message]. Messages the public [Message] model cannot represent are skipped. */
    fun onMessageReceived(message: Message) {}
}

/**
 * Creates [CrispEventCallbacks][im.crisp.sdk.api.CrispEventCallbacks] invoking the given lambdas, the `null` ones being ignored.
 *
 * Java can implement [CrispEventCallbacks][im.crisp.sdk.api.CrispEventCallbacks] instead.
 */
@JvmSynthetic
fun CrispEventCallbacks(
    onChatOpened: (() -> Unit)? = null,
    onChatClosed: (() -> Unit)? = null,
    onSessionLoaded: ((sessionId: String) -> Unit)? = null,
    onMessageSent: ((Message) -> Unit)? = null,
    onMessageReceived: ((Message) -> Unit)? = null
): CrispEventCallbacks = object : CrispEventCallbacks {
    override fun onChatOpened() {
        onChatOpened?.invoke()
    }

    override fun onChatClosed() {
        onChatClosed?.invoke()
    }

    override fun onSessionLoaded(sessionId: String) {
        onSessionLoaded?.invoke(sessionId)
    }

    override fun onMessageSent(message: Message) {
        onMessageSent?.invoke(message)
    }

    override fun onMessageReceived(message: Message) {
        onMessageReceived?.invoke(message)
    }
}
