package im.crisp.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import im.crisp.catalog.controller.WithControllerFabs
import im.crisp.catalog.events.rememberEventToaster
import im.crisp.catalog.settings.rememberChatboxConfig
import im.crisp.catalog.ui.theme.CatalogTheme
import im.crisp.sdk.api.CrispEventCallbacks
import im.crisp.sdk.ui.CrispChatbox
import im.crisp.sdk.ui.rememberCrispChatboxController
import im.crisp.sdk.ui.EdgeToEdge

class AppBarComposeActivity: ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            CatalogTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Crisp") },
                            navigationIcon = {
                                IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_navigation),
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    val toastEvent = rememberEventToaster("AppBar")
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding())
                    ) {
                        CrispChatbox(
                            config = rememberChatboxConfig(),
                            modifier = Modifier.fillMaxSize(),
                            edgeToEdge = EdgeToEdge.BOTTOM,
                            controller = controller,
                        )
                    }
                }
            }
        }
    }
}
