package im.crisp.sdk.internal.chatbox

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.api.CrispSession
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Dispatches the calls of [CrispSessionController][im.crisp.sdk.api.CrispSessionController] to their session:
 * right away to a chatbox ready with it, or else once one is, before it becomes ready.
 *
 * Persisted until dispatched, so that they survive the chatbox being closed or the app being killed.
 * Deduplicated as they wait, see [CrispSessionCalls.plus].
 * Shared by all the chatboxes and controllers of the app.
 */
internal class CrispControllerDispatcher(
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    /**
     * A chatbox calls can be dispatched to,
     * while [ready][CrispChatboxState.Ready] with its [config][CrispConfig].
     */
    class Chatbox(
        val bridge: CrispClientBridge,
        val config: StateFlow<CrispConfig?>,
        val state: StateFlow<CrispChatboxState>,
        // Reloads the chatbox, e.g. to start a new session once reset
        val reload: () -> Unit
    )

    // Main thread only
    private val chatboxes = mutableMapOf<CrispClientBridge, Pair<Chatbox, Job>>()

    /** How [dispatch] ended. */
    enum class Result {
        /** No call is left, the chatbox can become ready. */
        DISPATCHED,

        /** A reset is requested: the session must be reset, the calls are dispatched once reloaded. */
        RESET_PENDING,

        /** The page was unloaded meanwhile: the calls left are dispatched once reloaded. */
        PAGE_UNLOADED
    }

    /**
     * The chatbox was not ready with the session once the calls waiting for it were dispatched
     * (e.g. the page was unloaded meanwhile, or reloaded to reset the session): the call was not made.
     */
    class SessionNotReady : Exception("The chatbox is not ready with the session")

    // region Concurrency

    // Dispatches a session from one chatbox at a time, so that a call is not dispatched twice. Main thread only
    private val dispatchMutexes = mutableMapOf<CrispSession, Mutex>()

    private fun mutex(key: CrispSession) = dispatchMutexes.getOrPut(key, ::Mutex)

    // endregion

    // region Calls, from any thread

    /** Saves [call][CrispSessionCall] for the session of [key][CrispSession]. */
    fun add(key: CrispSession, call: CrispSessionCall) {
        update(key) { it + call }
        dispatchToReadyChatbox(key)
    }

    /** Drops the calls saved for the session of [key][CrispSession] and requests its reset. */
    fun reset(key: CrispSession) {
        update(key) { CrispSessionCalls(reset = true) }
        dispatchToReadyChatbox(key)
    }

    /**
     * Runs [block] on the session of [config][CrispConfig] once the calls waiting for it are dispatched,
     * so that calls are made in order.
     *
     * @throws [SessionNotReady] if the chatbox is not ready with the session anymore before [block] is run.
     * @throws [IllegalStateException] if [bridge] is not [attached][attach], which would not keep calls in order.
     */
    suspend fun <T> withSession(
        config: CrispConfig,
        bridge: CrispClientBridge,
        block: suspend () -> T
    ): T {
        // Not retried as unloaded: the chatbox would still be ready, so the call would loop
        val chatbox = chatboxes[bridge]?.first
            ?: throw IllegalStateException("The chatbox is not attached to the dispatcher")
        return mutex(config.session).withLock {
            if (!prepareLocked(config, chatbox)) throw SessionNotReady()
            block()
        }
    }

    // endregion

    // region Chatboxes, from the main thread

    /**
     * Dispatches to [chatbox][Chatbox] each time it becomes ready, and right away while it is.
     */
    fun attach(chatbox: Chatbox) {
        if (chatbox.bridge in chatboxes) return
        val job = scope.launch {
            combine(chatbox.config, chatbox.state) { config, state ->
                config.takeIf { state is CrispChatboxState.Ready }
            }.collect { config -> config?.let { prepare(it, chatbox) } }
        }
        chatboxes[chatbox.bridge] = chatbox to job
    }

    fun detach(bridge: CrispClientBridge) {
        chatboxes.remove(bridge)?.second?.cancel()
    }

    /**
     * The session of [config][CrispConfig] was reset.
     *
     * Calls made after the reset request are kept for the new one.
     */
    fun onSessionReset(config: CrispConfig) {
        update(config.session) { it.copy(reset = false) }
    }

    // endregion

    // region Calls dispatch and Reset handling

    private fun dispatchToReadyChatbox(key: CrispSession) {
        scope.launch {
            val chatbox = chatboxes.values.firstOrNull { (chatbox, _) ->
                chatbox.state.value is CrispChatboxState.Ready && chatbox.config.value?.session == key
            }?.first ?: return@launch
            prepare(chatbox.config.value ?: return@launch, chatbox)
        }
    }

    private suspend fun prepare(config: CrispConfig, chatbox: Chatbox) {
        try {
            mutex(config.session).withLock { prepareLocked(config, chatbox) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No call to fail here, e.g. the session could not be reset
            log(Level.WARN, LOG_TAG, "Cannot prepare the session", e)
        }
    }

    /**
     * Resets the session if requested, then reloads the chatbox, or else dispatches its calls,
     * while [chatbox][Chatbox] is ready with the session of [config][CrispConfig],
     * which may have changed while waiting for the lock.
     *
     * Returns whether the page can be used: `false` if it is being replaced, e.g. reloaded once reset.
     *
     * @throws [IllegalStateException] if the session could not be reset: the page cannot be used for it,
     * but is not replaced either, so waiting for the next one could loop. The reset stays requested,
     * retried on the next preparation (the next call, ready chatbox or load).
     */
    private suspend fun prepareLocked(config: CrispConfig, chatbox: Chatbox): Boolean {
        val isReady = {
            chatbox.state.value is CrispChatboxState.Ready && chatbox.config.value?.session == config.session
        }
        return when (dispatchLocked(config.session, chatbox.bridge, isReady)) {
            Result.DISPATCHED -> true
            Result.PAGE_UNLOADED -> false
            Result.RESET_PENDING -> try {
                chatbox.bridge.reset()
                onSessionReset(config)
                // Not if its WebView was removed meanwhile, e.g. its view recreated
                if (chatboxes[chatbox.bridge]?.first === chatbox) {
                    chatbox.reload()
                }
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: JSEvaluationException.PageUnloaded) {
                // Reset by the next page, before it becomes ready
                false
            } catch (e: Exception) {
                throw IllegalStateException("Cannot reset the session", e)
            }
        }
    }

    /**
     * Dispatches the calls of [config][CrispConfig] to [bridge][CrispClientBridge],
     * until none is left, including those made meanwhile.
     *
     * Calls failing are dropped, as they would fail again: right away if put back, then on each load.
     * Calls are taken out before being dispatched. Interrupted before the page answers (the page unloaded,
     * or the dispatch canceled), [idempotent][CrispSessionCall.isIdempotent] calls are put back, to be dispatched
     * once reloaded, while others are dropped, as the page may have made them: they are made at most once.
     * If the app is killed meanwhile, the call being dispatched is lost.
     */
    suspend fun dispatch(config: CrispConfig, bridge: CrispClientBridge): Result =
        mutex(config.session).withLock { dispatchLocked(config.session, bridge) }

    /**
     * Dispatches the calls of [key] to [bridge] while [canRun].
     *
     * @see [dispatch]
     */
    private suspend fun dispatchLocked(
        key: CrispSession,
        bridge: CrispClientBridge,
        canRun: () -> Boolean = { true }
    ): Result {
        while (true) {
            if (!canRun()) return Result.PAGE_UNLOADED
            val pending = update(key) { it.takeNext() }
            if (pending.reset) return Result.RESET_PENDING
            val call = pending.calls.firstOrNull() ?: return Result.DISPATCHED
            try {
                call.run(bridge)
            } catch (e: CancellationException) {
                putBackIfIdempotent(key, call, "Dispatch of $call canceled before the page answered")
                throw e
            } catch (e: JSEvaluationException.PageUnloaded) {
                putBackIfIdempotent(key, call, "Page unloaded before answering $call")
                return Result.PAGE_UNLOADED
            } catch (e: Exception) {
                // Call may have failed because of crisp-client rejection or exception
                // or an error occurred while encoding/decoding
                log(Level.WARN, LOG_TAG, "Cannot dispatch $call, dropped", e)
            }
        }
    }

    /** Puts back [call], interrupted before the page answered, unless not idempotent, see [dispatch]. */
    private fun putBackIfIdempotent(key: CrispSession, call: CrispSessionCall, reason: String) {
        if (call.isIdempotent) {
            update(key) { it.putBack(call) }
        } else {
            log(Level.WARN, LOG_TAG, "$reason, dropped: it may have been made, and is made at most once")
        }
    }

    // endregion

    // region Persistence

    @Synchronized
    private fun read(key: CrispSession): CrispSessionCalls {
        val value = preferences.getString(preferenceKey(key), null) ?: return CrispSessionCalls()
        return try {
            json.decodeFromString(value)
        } catch (e: SerializationException) {
            log(Level.WARN, LOG_TAG, "Cannot read the calls waiting for $key, dropped", e)
            CrispSessionCalls()
        } catch (e: IllegalArgumentException) {
            log(Level.WARN, LOG_TAG, "Cannot read the calls waiting for $key, dropped", e)
            CrispSessionCalls()
        }
    }

    /** Saves the calls of [key] transformed by [transform], returning the previous ones. */
    @Synchronized
    private fun update(
        key: CrispSession,
        transform: (CrispSessionCalls) -> CrispSessionCalls
    ): CrispSessionCalls {
        val previous = read(key)
        val calls = transform(previous)
        if (calls != previous) {
            preferences.edit {
                if (calls.isEmpty) {
                    remove(preferenceKey(key))
                } else {
                    putString(preferenceKey(key), json.encodeToString(calls))
                }
            }
        }
        return previous
    }

    private fun preferenceKey(key: CrispSession) = json.encodeToString(key)

    // endregion

    companion object {
        private const val LOG_TAG = "CrispControllerDispatcher"
        private const val NAME = "im.crisp.sdk.controller.calls"

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        @Volatile
        private var instance: CrispControllerDispatcher? = null

        fun get(context: Context): CrispControllerDispatcher = instance ?: synchronized(this) {
            instance ?: CrispControllerDispatcher(
                context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
