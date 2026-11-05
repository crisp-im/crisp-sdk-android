package im.crisp.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import im.crisp.catalog.controller.WithControllerFabs
import im.crisp.catalog.events.rememberEventToaster
import im.crisp.catalog.settings.rememberChatboxConfig
import im.crisp.catalog.ui.theme.CatalogTheme
import im.crisp.sdk.api.CrispEventCallbacks
import im.crisp.sdk.ui.CrispChatbox
import im.crisp.sdk.ui.rememberCrispChatboxController
import im.crisp.sdk.ui.EdgeToEdge

class FullscreenComposeActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            CatalogTheme {
                val toastEvent = rememberEventToaster("Fullscreen")
                val controller = rememberCrispChatboxController(
                    callbacks = CrispEventCallbacks(
                        onChatOpened = { toastEvent(R.string.event_chat_opened) },
                        onChatClosed = { toastEvent(R.string.event_chat_closed) },
                        onSessionLoaded = { toastEvent(R.string.event_session_loaded, it) },
                        onMessageSent = { toastEvent(R.string.event_message_sent, toastEvent.describe(it)) },
                        onMessageReceived = { toastEvent(R.string.event_message_received, toastEvent.describe(it)) }
                    )
                )
                WithControllerFabs(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CrispChatbox(
                        config = rememberChatboxConfig(),
                        modifier = Modifier.fillMaxSize(),
                        edgeToEdge = EdgeToEdge.ALL,
                        controller = controller
                    )
                }
            }
        }
    }
}
