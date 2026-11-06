package im.crisp.sdk.api

/**
 * Receives the result of a [CrispChatboxController] getter, on the main thread.
 *
 * Mostly meant for Java, Kotlin can call the `suspend` overloads instead.
 */
fun interface CrispMethodCallback<T> {
    fun onResult(result: T)
}

/**
 * Notified once a [CrispChatboxController] call has been processed by the chatbox, on the main thread.
 *
 * Mostly meant for Java, Kotlin can call the `suspend` overloads instead.
 */
fun interface CrispMethodCompletion {
    fun onComplete()
}

