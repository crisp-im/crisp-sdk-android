package im.crisp.sdk.api

import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.bridge.types.BotScenarioId
import im.crisp.sdk.internal.bridge.types.LanguageCode
import im.crisp.sdk.internal.bridge.types.sessionValueFromJson
import im.crisp.sdk.internal.bridge.types.toBridge
import im.crisp.sdk.internal.bridge.types.toModel
import im.crisp.sdk.internal.chatbox.CrispChatboxState
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.internal.chatbox.CrispControllerDispatcher
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.model.Company
import im.crisp.sdk.model.MessageContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the Web client running in one chatbox ([CrispChatbox][im.crisp.sdk.ui.CrispChatbox],
 * [CrispBottomSheetModal][im.crisp.sdk.ui.CrispBottomSheetModal],
 * [CrispChatboxView][im.crisp.sdk.ui.CrispChatboxView], [CrispFragment][im.crisp.sdk.ui.CrispFragment],
 * [CrispBottomSheetDialogFragment][im.crisp.sdk.ui.CrispBottomSheetDialogFragment]),
 * grouped like the Web client methods: [bot][Bot], [helpdesk][Helpdesk], [message][Message], [session][Session] and [user][User].
 *
 * Every method has two flavors: a `suspend` one for Kotlin and a callback one for Java, whose
 * callback is invoked on the main thread. UI methods take an optional completion, so Java can omit it,
 * and Kotlin can call them outside of a coroutine with `completion = null`. Failures are logged by
 * the SDK, getters then return `null` (or `false`) and completions are still invoked.
 *
 * Only getters and UI methods are available. Set up the session with [CrispSessionController],
 * whose calls are saved until dispatched, with or without a chatbox shown.
 *
 * Calls made before the chatbox is ready, or while it reloads, wait until it is ready,
 * so they can be made right after creating the component.
 * They are dispatched once the [CrispSessionController] calls waiting for the session of the chatbox are,
 * so that calls are executed in order.
 * Interrupted by a reload, they are dispatched again, unless the chatbox loads another session meanwhile
 * (another [websiteId][CrispConfig.websiteId] or [tokenId][CrispConfig.tokenId]).
 * In this case, they fail, as they were made for the previous one.
 * Calls that would duplicate something if made twice ([bot.runScenario][Bot.runScenario] and
 * [message.show][Message.show]) are made at most once: interrupted once dispatched, they fail, as they may have been made.
 *
 * A controller outlives the WebView: a [CrispFragment][im.crisp.sdk.ui.CrispFragment] keeps the same one when its view is recreated,
 * and Compose keeps the one passed as [controller][im.crisp.sdk.ui.CrispChatbox].
 *
 * [close] a controller you create once you no longer need it, e.g. by passing it to your
 * `ViewModel` constructor, so pending calls are then dropped. The controllers created by the SDK
 * components ([rememberCrispChatboxController][im.crisp.sdk.ui.rememberCrispChatboxController],
 * or the default one of a [view][im.crisp.sdk.ui.CrispChatboxView.controller]
 * or [fragment][im.crisp.sdk.ui.CrispFragment.controller]) are closed with them.
 *
 * Register [CrispEventCallbacks] with [addEventCallbacks] to be notified of the chatbox events.
 *
 * ## Getting a controller
 *
 * How long the controller must live decides where it comes from.
 *
 * ### As long as the composition, with `rememberCrispChatboxController`
 *
 * In Compose, [rememberCrispChatboxController][im.crisp.sdk.ui.rememberCrispChatboxController] suits most cases.
 * It is closed when it leaves the composition, and is recreated with the `Activity`, e.g. on rotation:
 * ```
 * @Composable
 * fun SupportScreen() {
 *     val scope = rememberCoroutineScope()
 *     val controller = rememberCrispChatboxController()
 *     var isOngoing by remember { mutableStateOf(false) }
 *
 *     // Waits for the chatbox to be ready
 *     LaunchedEffect(controller) {
 *         isOngoing = controller.session.isOngoing()
 *     }
 *
 *     Column {
 *         if (!isOngoing) {
 *             Button(onClick = { scope.launch { controller.bot.runScenario("YOUR_SCENARIO_ID") } }) {
 *                 Text("Get started")
 *             }
 *         }
 *         CrispChatbox(websiteId = "YOUR_WEBSITE_ID", controller = controller)
 *     }
 * }
 * ```
 *
 * ### Across configuration changes, in a `ViewModel`
 *
 * Own it in a `ViewModel` so that pending calls survive configuration changes, and to call it from your logic.
 * Passed to the `ViewModel` constructor, it is closed with it:
 * ```
 * class SupportViewModel(
 *     val controller: CrispChatboxController = CrispChatboxController()
 * ) : ViewModel(controller) {
 *     fun openArticle(slug: String) {
 *         viewModelScope.launch {
 *             controller.helpdesk.openArticle(locale = "en", slug = slug)
 *         }
 *     }
 * }
 * ```
 *
 * In Compose, pass it to the component:
 * ```
 * CrispChatbox(websiteId = "YOUR_WEBSITE_ID", controller = viewModel.controller)
 * ```
 *
 * With a [CrispChatboxView][im.crisp.sdk.ui.CrispChatboxView], set it on the view on each creation:
 * ```
 * findViewById<CrispChatboxView>(R.id.chatbox_view).controller = viewModel.controller
 * ```
 *
 * With a [CrispFragment][im.crisp.sdk.ui.CrispFragment] or a
 * [CrispBottomSheetDialogFragment][im.crisp.sdk.ui.CrispBottomSheetDialogFragment], Android restores the fragment
 * by itself, so set it when attached. Register the listener before `super.onCreate()`, which restores it:
 * ```
 * supportFragmentManager.addFragmentOnAttachListener { _, fragment ->
 *     if (fragment is CrispFragment) {
 *         fragment.controller = viewModel.controller
 *     }
 * }
 * ```
 *
 * The controller outlives the `Activity`, so remove the callbacks that reference the `Activity` in its `onDestroy`,
 * see [addEventCallbacks]. The test app has a
 * [complete sample](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/ProgrammaticFragmentActivity.kt).
 *
 * ### As long as a View or a Fragment, with their default one
 *
 * Without passing one, [CrispChatboxView.controller][im.crisp.sdk.ui.CrispChatboxView.controller],
 * [CrispFragment.controller][im.crisp.sdk.ui.CrispFragment.controller] and
 * [CrispBottomSheetDialogFragment.controller][im.crisp.sdk.ui.CrispBottomSheetDialogFragment.controller]
 * give the one created with the component, or [CrispChat.show][im.crisp.sdk.ui.CrispChat.show] through the sheet
 * it returns. It is lost when the component is recreated.
 *
 * ## Calling it
 *
 * From Kotlin coroutines, use the `suspend` flavors:
 * ```
 * scope.launch {
 *     val email = controller.user.getEmail()
 *     controller.helpdesk.search()
 * }
 * ```
 *
 * Without a coroutine, UI methods can be called with `completion = null`:
 * ```
 * controller.helpdesk.search(completion = null)
 * ```
 *
 * From Java, use the callback flavors:
 * ```java
 * controller.getUser().getEmail(email -> showEmail(email));
 * controller.getHelpdesk().search();
 * ```
 *
 * @see CrispSessionController
 */
class CrispChatboxController : AutoCloseable {
    private class ChatboxAttachment(
        val bridge: CrispClientBridge,
        val config: StateFlow<CrispConfig?>,
        val state: StateFlow<CrispChatboxState>,
        val events: Flow<CrispClientEvent>,
        val dispatcher: CrispControllerDispatcher
    )

    /** The config of the attached chatbox with its bridge while it is ready. */
    private class ChatboxTarget(
        val config: CrispConfig?,
        val bridge: CrispClientBridge?,
        val dispatcher: CrispControllerDispatcher
    )

    /** What a call waits for before being made. */
    private sealed interface CallWait {
        object ControllerClosed : CallWait

        /** The attached chatbox loads another session than the one the call is made for. */
        object SessionChanged : CallWait

        class ChatboxReady(
            val config: CrispConfig,
            val bridge: CrispClientBridge,
            val dispatcher: CrispControllerDispatcher
        ) : CallWait
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attachedChatbox = MutableStateFlow<ChatboxAttachment?>(null)
    private val closed = MutableStateFlow(false)

    // Copied on write, so that callbacks can remove themselves while notified
    private val eventCallbacks = CopyOnWriteArrayList<CrispEventCallbacks>()

    /** The chatbox to target, if attached and ready, `null` otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val chatboxTarget: Flow<ChatboxTarget?> = attachedChatbox.flatMapLatest { attachment ->
        attachment?.let {
            combine(it.config, it.state) { config, state ->
                ChatboxTarget(
                    config,
                    it.bridge.takeIf { state is CrispChatboxState.Ready },
                    it.dispatcher
                )
            }
        } ?: flowOf(null)
    }

    /** The Web client events of the attached chatbox. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val attachedEvents: Flow<CrispClientEvent> =
        attachedChatbox.flatMapLatest { it?.events ?: emptyFlow() }

    // TODO: restore once it switches the chatbox to its chat section, see Chat below
    // val chat: Chat = Chat()
    val helpdesk: Helpdesk = Helpdesk()
    val bot: Bot = Bot()
    val message: Message = Message()
    val session: Session = Session()
    val user: User = User()

    init {
        scope.launch { attachedEvents.collect(::onClientEvent) }
    }

    // region Attachment, called by CrispChatboxView

    internal fun attach(
        bridge: CrispClientBridge,
        config: StateFlow<CrispConfig?>,
        state: StateFlow<CrispChatboxState>,
        events: Flow<CrispClientEvent>,
        dispatcher: CrispControllerDispatcher
    ) {
        if (closed.value) {
            log(Level.WARN, LOG_TAG, "Cannot attach a closed controller")
            return
        }
        attachedChatbox.value = ChatboxAttachment(bridge, config, state, events, dispatcher)
    }

    internal fun detach(bridge: CrispClientBridge) {
        if (attachedChatbox.value?.bridge === bridge) {
            attachedChatbox.value = null
        }
    }

    // endregion

    // region Events

    // TODO: maybe expose `isChatOpened: StateFlow<Boolean>`, `true` between onChatOpened and onChatClosed,
    //  for UI to observe the current value (e.g. `collectAsStateWithLifecycle()` in Compose),
    //  like Media3 `Player.isPlaying` alongside `onIsPlayingChanged`.
    //  Reset it to `false` when the chatbox detaches, e.g. when the controller is replaced, without notifying onChatClosed.
    // TODO: maybe expose `isOngoing: StateFlow<Boolean>`,
    //  e.g. set to `true` once the session identifier is received after CrispClientEvent.Loaded,
    //  and to `false` once Session.reset succeeds or the chat is closed.
    //  To clarify: a session identifier exists before any conversation starts, so Session.isOngoing
    //  (`isSessionOngoing`) may be the better source, refreshed on Loaded and on MessageSent / MessageReceived.

    /**
     * Registers [callbacks][CrispEventCallbacks] to be notified of the chatbox events, on the main thread.
     *
     * Register them before the chatbox is drawn, e.g. in `Activity.onCreate`, right after creating
     * the controller (in a `ViewModel` initializer), or right after
     * [CrispChat.show][im.crisp.sdk.ui.CrispChat.show]. Events that happened before are not notified, such as
     * [onChatOpened][CrispEventCallbacks.onChatOpened] if the chatbox is already shown.
     *
     * In Compose, register them in a `DisposableEffect` and remove them in its `onDispose`,
     * or pass them to [rememberCrispChatboxController][im.crisp.sdk.ui.rememberCrispChatboxController].
     * Do not call this from the composable body, which registers callbacks again on each recomposition.
     *
     * They are unregistered when this controller is [closed][close]. Remove them with
     * [removeEventCallbacks] if they reference something that is destroyed before, e.g. an
     * `Activity` (destroyed on configuration changes) registering them on the controller of
     * its `ViewModel`.
     *
     * Does nothing if already registered.
     */
    fun addEventCallbacks(callbacks: CrispEventCallbacks) {
        if (!warnIfClosed("addEventCallbacks")) {
            eventCallbacks.addIfAbsent(callbacks)
        }
    }

    /** Unregisters [callbacks][CrispEventCallbacks], which are no longer notified. */
    fun removeEventCallbacks(callbacks: CrispEventCallbacks) {
        eventCallbacks.remove(callbacks)
    }

    internal fun notifyChatOpened() = notify { onChatOpened() }

    internal fun notifyChatClosed() = notify { onChatClosed() }

    private suspend fun onClientEvent(event: CrispClientEvent) {
        when (event) {
            is CrispClientEvent.Loaded -> {
                val sessionId =
                    await("session.getIdentifier", null) { getSessionIdentifier().rawValue }
                sessionId?.let { notify { onSessionLoaded(it) } }
            }

            is CrispClientEvent.MessageSent -> notify { onMessageSent(event.message) }
            is CrispClientEvent.MessageReceived -> notify { onMessageReceived(event.message) }
        }
    }

    private inline fun notify(block: CrispEventCallbacks.() -> Unit) {
        if (!closed.value) {
            eventCallbacks.forEach(block)
        }
    }

    // endregion

    /**
     * Drops the pending calls, whose callbacks and completions are not invoked, and makes `suspend`
     * calls return their fallback. Later calls are ignored and event callbacks are unregistered.
     *
     * Does nothing if already closed.
     */
    override fun close() {
        // Cancels first, so that pending callback calls are not resumed by the closing below
        scope.cancel()
        attachedChatbox.value = null
        closed.value = true
        eventCallbacks.clear()
    }

    // region Calls

    /** Waits until the attached chatbox is ready for [session], or for any session if `null`. */
    private suspend fun awaitReady(session: CrispSession?): CallWait =
        combine(chatboxTarget, closed) { target, isClosed ->
            when {
                isClosed -> CallWait.ControllerClosed
                session != null && target?.config != null && target.config.session != session ->
                    CallWait.SessionChanged
                // Ready once a config is loaded, so that calls are made for it, after its queued session calls
                target?.bridge != null && target.config != null -> CallWait.ChatboxReady(
                    target.config,
                    target.bridge,
                    target.dispatcher
                )

                else -> null
            }
        }.filterNotNull().first()

    private suspend fun <T> await(
        name: String,
        fallback: T,
        isIdempotent: Boolean = true,
        block: suspend CrispClientBridge.() -> T
    ): T = withContext(Dispatchers.Main.immediate) { awaitAndCall(name, fallback, isIdempotent, block) }

    /**
     * Calls [block] once the attached chatbox is ready for the session loaded when the call is made,
     * or for the next one if none is loaded yet.
     *
     * Canceled if another session is loaded meanwhile (e.g. another website),
     * or made once ready again if the page is reloaded with the same session before answering.
     * Unless not [isIdempotent], the page may have made it, so it fails instead, made at most once.
     */
    private suspend fun <T> awaitAndCall(
        name: String,
        fallback: T,
        isIdempotent: Boolean,
        block: suspend CrispClientBridge.() -> T
    ): T {
        var session = attachedChatbox.value?.config?.value?.session
        while (true) {
            when (val wait = awaitReady(session)) {
                CallWait.ControllerClosed -> {
                    log(Level.WARN, LOG_TAG, "Cannot call '$name': the controller is closed")
                    return fallback
                }

                CallWait.SessionChanged -> {
                    log(
                        Level.WARN,
                        LOG_TAG,
                        "Cannot call '$name': the chatbox loaded another session"
                    )
                    return fallback
                }

                is CallWait.ChatboxReady -> {
                    session = wait.config.session
                    try {
                        return wait.dispatcher.withSession(wait.config, wait.bridge) { wait.bridge.block() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CrispControllerDispatcher.SessionNotReady) {
                        // Not made yet
                        log(Level.DEBUG, LOG_TAG, "Calling '$name' once the chatbox is reloaded")
                    } catch (e: JSEvaluationException.PageUnloaded) {
                        if (!isIdempotent) {
                            log(
                                Level.WARN,
                                LOG_TAG,
                                "Cannot call '$name': the page was unloaded before answering, " +
                                        "it may have been made, and is made at most once",
                                e
                            )
                            return fallback
                        }
                        log(Level.DEBUG, LOG_TAG, "Calling '$name' again once the chatbox is reloaded")
                    } catch (e: Exception) {
                        log(Level.WARN, LOG_TAG, "Cannot call '$name'", e)
                        return fallback
                    }
                }
            }
        }
    }

    private fun <T> call(
        name: String,
        fallback: T,
        callback: CrispMethodCallback<T>,
        block: suspend CrispClientBridge.() -> T
    ) {
        if (!warnIfClosed(name)) {
            scope.launch { callback.onResult(await(name, fallback, block = block)) }
        }
    }

    private fun call(
        name: String,
        completion: CrispMethodCompletion?,
        isIdempotent: Boolean = true,
        block: suspend CrispClientBridge.() -> Unit
    ) {
        if (!warnIfClosed(name)) {
            scope.launch {
                await(name, Unit, isIdempotent, block)
                completion?.onComplete()
            }
        }
    }

    private fun warnIfClosed(name: String): Boolean = closed.value.also { isClosed ->
        if (isClosed) {
            log(Level.WARN, LOG_TAG, "Cannot call '$name': the controller is closed")
        }
    }

    // endregion

    // region Groups

    // TODO: `chatShow` shows the Web client chatbox, it does not switch it from the helpdesk to the chat
    //  section as documented. Restore this group (and `chat` above) once the Web client supports it.
    // inner class Chat internal constructor() {
    //     /** Shows the chat (as opposed to the helpdesk). */
    //     @JvmSynthetic
    //     suspend fun show(): Unit = await("chat.show", Unit) { chatShow() }

    //     @JvmOverloads
    //     fun show(completion: CrispMethodCompletion? = null) = call("chat.show", completion) { chatShow() }
    // }

    inner class Helpdesk internal constructor() {
        /** Shows the helpdesk search. */
        @JvmSynthetic
        suspend fun search(): Unit = await("helpdesk.search", Unit) { helpdeskSearch() }

        @JvmOverloads
        fun search(completion: CrispMethodCompletion? = null) =
            call("helpdesk.search", completion) { helpdeskSearch() }

        /**
         * Opens a helpdesk article.
         *
         * @param locale the article locale, e.g. `en`.
         * @param slug the article slug.
         * @param title the article title, shown while it loads.
         * @param category the article category, shown while it loads.
         */
        @JvmSynthetic
        suspend fun openArticle(
            locale: String,
            slug: String,
            title: String? = null,
            category: String? = null
        ): Unit = await("helpdesk.openArticle", Unit) {
            helpdeskArticleOpen(LanguageCode(locale), slug, title, category)
        }

        @JvmOverloads
        fun openArticle(
            locale: String,
            slug: String,
            title: String? = null,
            category: String? = null,
            completion: CrispMethodCompletion? = null
        ) = call("helpdesk.openArticle", completion) {
            helpdeskArticleOpen(LanguageCode(locale), slug, title, category)
        }
    }

    inner class Bot internal constructor() {
        /**
         * Runs the bot scenario [id].
         *
         * Made at most once: if the chatbox is reloaded before answering,
         * the scenario is not run again, as it may have been.
         */
        @JvmSynthetic
        suspend fun runScenario(id: String): Unit =
            await("bot.runScenario", Unit, isIdempotent = false) { botScenarioRun(BotScenarioId(id)) }

        @JvmOverloads
        fun runScenario(id: String, completion: CrispMethodCompletion? = null) =
            call("bot.runScenario", completion, isIdempotent = false) { botScenarioRun(BotScenarioId(id)) }
    }

    inner class Message internal constructor() {
        /**
         * Shows a message from the website in the chat, without sending it.
         *
         * Made at most once: if the chatbox is reloaded before answering,
         * the message is not shown again, as it may have been.
         */
        @JvmSynthetic
        suspend fun show(content: MessageContent): Unit =
            await("message.show", Unit, isIdempotent = false) { showMessage(content) }

        @JvmOverloads
        fun show(content: MessageContent, completion: CrispMethodCompletion? = null) =
            call("message.show", completion, isIdempotent = false) { showMessage(content) }

        private suspend fun CrispClientBridge.showMessage(content: MessageContent) {
            val bridgeContent = content.toBridge()
            messageShow(bridgeContent.messageType, bridgeContent, null, null)
        }
    }

    inner class Session internal constructor() {
        /** The session identifier, `null` if unavailable. */
        @JvmSynthetic
        suspend fun getIdentifier(): String? =
            await("session.getIdentifier", null) { getSessionIdentifier().rawValue }

        fun getIdentifier(callback: CrispMethodCallback<String?>) =
            call("session.getIdentifier", null, callback) { getSessionIdentifier().rawValue }

        /** Whether a conversation is ongoing, `false` if it cannot be determined. */
        @JvmSynthetic
        suspend fun isOngoing(): Boolean = await("session.isOngoing", false) { isSessionOngoing() }

        fun isOngoing(callback: CrispMethodCallback<Boolean>) =
            call("session.isOngoing", false, callback) { isSessionOngoing() }

        /** The session data value for [key]: a [String], [Boolean], [Long], [Double] or `null`. */
        @JvmSynthetic
        suspend fun getData(key: String): Any? =
            await("session.getData", null) { sessionValueFromJson(getSessionData(key)) }

        fun getData(key: String, callback: CrispMethodCallback<Any?>) =
            call("session.getData", null, callback) { sessionValueFromJson(getSessionData(key)) }
    }

    inner class User internal constructor() {
        @JvmSynthetic
        suspend fun getEmail(): String? = await("user.getEmail", null) { getUserEmail() }

        fun getEmail(callback: CrispMethodCallback<String?>) =
            call("user.getEmail", null, callback) { getUserEmail() }

        @JvmSynthetic
        suspend fun getPhone(): String? = await("user.getPhone", null) { getUserPhone() }

        fun getPhone(callback: CrispMethodCallback<String?>) =
            call("user.getPhone", null, callback) { getUserPhone() }

        @JvmSynthetic
        suspend fun getNickname(): String? = await("user.getNickname", null) { getUserNickname() }

        fun getNickname(callback: CrispMethodCallback<String?>) =
            call("user.getNickname", null, callback) { getUserNickname() }

        @JvmSynthetic
        suspend fun getAvatar(): String? = await("user.getAvatar", null) { getUserAvatar() }

        fun getAvatar(callback: CrispMethodCallback<String?>) =
            call("user.getAvatar", null, callback) { getUserAvatar() }

        @JvmSynthetic
        suspend fun getCompany(): Company? =
            await("user.getCompany", null) { getUserCompany()?.toModel() }

        fun getCompany(callback: CrispMethodCallback<Company?>) =
            call("user.getCompany", null, callback) { getUserCompany()?.toModel() }
    }

    // endregion

    private companion object {
        private const val LOG_TAG = "CrispChatboxController"
    }
}
