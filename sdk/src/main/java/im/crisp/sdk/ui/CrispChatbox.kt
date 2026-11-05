package im.crisp.sdk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import im.crisp.sdk.api.CrispChatboxController
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.api.CrispEventCallbacks
import im.crisp.sdk.internal.chatbox.CrispChatboxEvent
import im.crisp.sdk.internal.environment.findActivity
import im.crisp.sdk.internal.views.CrispSheetDragHost
import im.crisp.sdk.model.Message

/**
 * Creates and remembers a [CrispChatboxController], to pass to [CrispChatbox] or
 * [CrispBottomSheetModal] to drive their Web client. It is closed when it leaves the composition.
 *
 * [callbacks][CrispEventCallbacks] are notified of the chatbox events, e.g. with lambdas:
 * ```
 * val controller = rememberCrispChatboxController(
 *     callbacks = CrispEventCallbacks(
 *         onChatOpened = { ... },
 *         onMessageReceived = { message -> ... }
 *     )
 * )
 * ```
 * They are registered when the controller is created and the latest ones are notified, so they
 * can be created on each recomposition. To register callbacks on a controller created elsewhere,
 * e.g. in a `ViewModel`, see [addEventCallbacks][CrispChatboxController.addEventCallbacks].
 */
@Composable
fun rememberCrispChatboxController(callbacks: CrispEventCallbacks? = null): CrispChatboxController {
    val currentCallbacks by rememberUpdatedState(callbacks)
    val controller = remember {
        CrispChatboxController().apply {
            // Only affects this new controller, so it is safe to do in composition
            addEventCallbacks(object : CrispEventCallbacks {
                override fun onChatOpened() {
                    currentCallbacks?.onChatOpened()
                }

                override fun onChatClosed() {
                    currentCallbacks?.onChatClosed()
                }

                override fun onSessionLoaded(sessionId: String) {
                    currentCallbacks?.onSessionLoaded(sessionId)
                }

                override fun onMessageSent(message: Message) {
                    currentCallbacks?.onMessageSent(message)
                }

                override fun onMessageReceived(message: Message) {
                    currentCallbacks?.onMessageReceived(message)
                }
            })
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

/**
 * The chatbox, driven by [controller][CrispChatboxController].
 *
 * [onClose] is called when the user asks to close the chatbox from its Web client, unlike
 * [onChatClosed][CrispEventCallbacks.onChatClosed] which is called once it is no longer shown.
 */
@Composable
fun CrispChatbox(
    websiteId: String,
    modifier: Modifier = Modifier,
    tokenId: String? = null,
    edgeToEdge: EdgeToEdge = EdgeToEdge.NONE,
    controller: CrispChatboxController = rememberCrispChatboxController(),
    onClose: (() -> Unit)? = null
) {
    CrispChatbox(
        CrispConfig(websiteId = websiteId, tokenId = tokenId),
        modifier = modifier,
        edgeToEdge = edgeToEdge,
        controller = controller,
        onClose = onClose
    )
}

/**
 * The chatbox, driven by [controller][CrispChatboxController].
 *
 * [onClose] is called when the user asks to close the chatbox from its Web client, unlike
 * [onChatClosed][CrispEventCallbacks.onChatClosed] which is called once it is no longer shown.
 */
@Composable
fun CrispChatbox(
    config: CrispConfig,
    modifier: Modifier = Modifier,
    edgeToEdge: EdgeToEdge = EdgeToEdge.NONE,
    controller: CrispChatboxController = rememberCrispChatboxController(),
    onClose: (() -> Unit)? = null
) {
    CrispChatboxContent(
        config = config,
        modifier = modifier,
        edgeToEdge = edgeToEdge,
        controller = controller,
        onClose = onClose
    )
}

/**
 * The chatbox, swiped down to dismiss the [sheetDragHost] hosting it if any.
 *
 * Its WebView is created once [isAnimatingIn] is false, not to stall the host animation.
 */
@Composable
internal fun CrispChatboxContent(
    config: CrispConfig,
    modifier: Modifier = Modifier,
    edgeToEdge: EdgeToEdge = EdgeToEdge.NONE,
    controller: CrispChatboxController = rememberCrispChatboxController(),
    onClose: (() -> Unit)? = null,
    sheetDragHost: CrispSheetDragHost? = null,
    isAnimatingIn: Boolean = false
) {
    val currentOnClose by rememberUpdatedState(onClose)

    // The view pads itself by the window insets (IME, and navigation bar if needed), as outside Compose
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // When inside ModalBottomSheet, `context` may be the Dialog's ContextThemeWrapper.
            // Using context.findActivity() or ensuring the Activity's base context configuration is used
            val activityContext = context.findActivity() ?: context
            CrispChatboxView(activityContext).apply {
                this.controller = controller
                // Disable WindowInsets animation dispatch for this view in Compose
                ViewCompat.setWindowInsetsAnimationCallback(
                    this,
                    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                        override fun onProgress(
                            insets: WindowInsetsCompat,
                            runningAnimations: List<WindowInsetsAnimationCompat>
                        ): WindowInsetsCompat = insets
                    }
                )
                onEventListener = { event ->
                    when (event) {
                        is CrispChatboxEvent.Close -> currentOnClose?.invoke()
                        else -> {}
                    }
                }
                enableEdgeToEdge(edgeToEdge)
                load(config)
            }
        },
        update = { view ->
            view.controller = controller
            view.sheetDragHost = sheetDragHost
            view.isAnimatingIn = isAnimatingIn
            // Reloads when the config changes, e.g. to switch to another website
            if (view.config != config) {
                view.load(config)
            }
        },
        onRelease = { view ->
            view.sheetDragHost = null
            ViewCompat.setWindowInsetsAnimationCallback(view, null)
            view.onDestroy()
        }
    )
}