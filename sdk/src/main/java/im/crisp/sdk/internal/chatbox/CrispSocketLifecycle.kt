package im.crisp.sdk.internal.chatbox

/**
 * Connects or disconnects the Web socket of the loaded page, so that it is only connected while
 * it [should][update] (app in foreground and online).
 *
 * The page connects its socket on its own once loaded, so it is considered connected until ready,
 * and only connected or disconnected once ready.
 */
internal class CrispSocketLifecycle(
    private val connect: suspend () -> Unit,
    private val disconnect: suspend () -> Unit,
) {
    private var isConnected = true

    /**
     * Connects or disconnects the socket if [state] is ready and it is not as it [shouldConnect].
     *
     * Exceptions of [connect] and [disconnect] are thrown, the socket being considered as requested
     * anyway, not to retry it until it should be the other way.
     */
    suspend fun update(shouldConnect: Boolean, state: CrispChatboxState) {
        if (state != CrispChatboxState.Ready) {
            isConnected = true
            return
        }
        if (shouldConnect == isConnected) return
        isConnected = shouldConnect
        if (shouldConnect) connect() else disconnect()
    }

    /**
     * Connects the socket right away, whether it should be connected or not,
     * e.g. before handing files to the page, which drops them while disconnected.
     *
     * Disconnected again on the next [update] that should be.
     */
    suspend fun connectNow() {
        isConnected = true
        connect()
    }
}
