package im.crisp.sdk.api

import im.crisp.sdk.model.Message

/**
 * Notified of the events of a chatbox on the main thread, once registered with
 * [CrispChatboxController.addEventCallbacks], or in Compose with
 * [rememberCrispChatboxController][im.crisp.sdk.ui.rememberCrispChatboxController].
 *
 * Every method does nothing by default, override the ones you need.
 * In Kotlin, the `CrispEventCallbacks(…)` function creates them from lambdas.
 *
 * ## Compose
 *
 * Pass them to [rememberCrispChatboxController][im.crisp.sdk.ui.rememberCrispChatboxController]. They can
 * be recreated on each recomposition, and can capture state, as the latest ones are notified:
 * ```
 * @Composable
 * fun SupportScreen(analytics: Analytics) {
 *     var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
 *     val controller = rememberCrispChatboxController(
 *         callbacks = CrispEventCallbacks(
 *             onChatOpened = { analytics.track("crisp_opened") },
 *             onChatClosed = { analytics.track("crisp_closed") },
 *             onSessionLoaded = { sessionId = it },
 *             onMessageSent = { analytics.track("crisp_message_sent") },
 *             onMessageReceived = { message ->
 *                 if (message.from == Message.Sender.OPERATOR) {
 *                     analytics.track("crisp_operator_replied")
 *                 }
 *             }
 *         )
 *     )
 *     CrispChatbox(websiteId = "YOUR_WEBSITE_ID", controller = controller)
 * }
 * ```
 *
 * On a controller created elsewhere, e.g. in a `ViewModel`, register them in a `DisposableEffect`:
 * ```
 * DisposableEffect(controller) {
 *     val callbacks = CrispEventCallbacks(onChatOpened = { analytics.track("crisp_opened") })
 *     controller.addEventCallbacks(callbacks)
 *     onDispose { controller.removeEventCallbacks(callbacks) }
 * }
 * ```
 *
 * ## Views and Fragments
 *
 * Register them on the controller of the component on each creation of your `Activity`. A default controller
 * is closed with its component, which unregisters them, so they need not be removed:
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     setContentView(R.layout.activity_support)
 *
 *     val chatboxView = findViewById<CrispChatboxView>(R.id.chatbox_view)
 *     chatboxView.controller.addEventCallbacks(object : CrispEventCallbacks {
 *         override fun onMessageReceived(message: Message) {
 *             when (val content = message.content) {
 *                 is MessageContent.TextContent -> analytics.track("crisp_text", content.text)
 *                 else -> analytics.track("crisp_other")
 *             }
 *         }
 *     })
 * }
 * ```
 *
 * A [CrispBottomSheetDialogFragment][im.crisp.sdk.ui.CrispBottomSheetDialogFragment] restored by Android has a
 * new default controller, without the callbacks registered on the previous one. To keep being notified, register
 * them when it is attached, before `super.onCreate()` which restores it:
 * ```
 * supportFragmentManager.addFragmentOnAttachListener { _, fragment ->
 *     if (fragment is CrispBottomSheetDialogFragment) {
 *         fragment.controller.addEventCallbacks(callbacks)
 *     }
 * }
 * ```
 *
 * ## `ViewModel`
 *
 * A controller held in a `ViewModel` outlives the `Activity`. Callbacks referencing the `Activity` must be removed
 * with it not to leak it, while those of the `ViewModel` itself can stay registered:
 * ```
 * class SupportActivity : AppCompatActivity() {
 *     private val viewModel: SupportViewModel by viewModels()
 *     private val callbacks = CrispEventCallbacks(
 *         onMessageReceived = { Toast.makeText(this, "New message", Toast.LENGTH_SHORT).show() }
 *     )
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         viewModel.controller.addEventCallbacks(callbacks)
 *     }
 *
 *     override fun onDestroy() {
 *         viewModel.controller.removeEventCallbacks(callbacks)
 *         super.onDestroy()
 *     }
 * }
 * ```
 *
 * ## Java
 *
 * Implement this interface, overriding the methods you need:
 * ```java
 * fragment.getController().addEventCallbacks(new CrispEventCallbacks() {
 *     @Override
 *     public void onSessionLoaded(@NonNull String sessionId) {
 *         Log.i("Support", "Session loaded: " + sessionId);
 *     }
 * });
 * ```
 *
 * The test app has [implementations](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/events/EventToasts.kt)
 * for every event.
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
