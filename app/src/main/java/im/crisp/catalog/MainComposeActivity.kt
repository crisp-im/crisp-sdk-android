package im.crisp.catalog

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.FragmentActivity
import im.crisp.catalog.controller.ChatboxControllerDialog
import im.crisp.catalog.controller.ControllerFabs
import im.crisp.catalog.controller.SessionControllerDialog
import im.crisp.catalog.controller.SessionControllerForm
import im.crisp.catalog.controller.WithControllerFabs
import im.crisp.catalog.events.ToastEventCallbacks
import im.crisp.catalog.events.rememberEventToaster
import im.crisp.catalog.settings.rememberChatboxConfig
import im.crisp.catalog.ui.theme.CatalogTheme
import im.crisp.sdk.api.Crisp
import im.crisp.sdk.api.CrispEventCallbacks
import im.crisp.sdk.ui.CrispBottomSheetModal
import im.crisp.sdk.ui.CrispChat
import im.crisp.sdk.ui.CrispChatbox
import im.crisp.sdk.ui.EdgeToEdge
import im.crisp.sdk.ui.rememberCrispChatboxController
import kotlinx.coroutines.launch

class MainComposeActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CatalogTheme {
                CatalogApp()
            }
        }
        // With the UI thread tasks, once the first frame is drawn not to delay it
        window.decorView.doOnPreDraw { it.post { Crisp.preheat(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreenSizes
@Composable
fun CatalogApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = rememberChatboxConfig()
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.SHEET) }
    val composeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showComposeSheet by rememberSaveable { mutableStateOf(false) }
    val toastSheetEvent = rememberEventToaster("Compose sheet")
    val sheetController = rememberCrispChatboxController(
        callbacks = CrispEventCallbacks(
            onChatOpened = { toastSheetEvent(R.string.event_chat_opened) },
            onChatClosed = { toastSheetEvent(R.string.event_chat_closed) },
            onSessionLoaded = { toastSheetEvent(R.string.event_session_loaded, it) },
            onMessageSent = { toastSheetEvent(R.string.event_message_sent, toastSheetEvent.describe(it)) },
            onMessageReceived = { toastSheetEvent(R.string.event_message_received, toastSheetEvent.describe(it)) }
        )
    )
    var isSheetControllerDialogShown by rememberSaveable { mutableStateOf(false) }
    var isSessionControllerDialogShown by rememberSaveable { mutableStateOf(false) }
    var sheetControllerTarget by rememberSaveable { mutableStateOf(SheetTarget.COMPOSE) }
    // Added to the controller of each shown DialogFragment, closed (and cleared) with it.
    // Adding them again to an already shown one does nothing.
    val fragmentSheetCallbacks = remember(context) { ToastEventCallbacks(context, "DialogFragment") }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            painterResource(it.icon),
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        when (currentDestination) {
            AppDestinations.SHEET -> Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Compose",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 1: CrispChatbox below AppBar
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, AppBarComposeActivity::class.java))
                    }) {
                        Text("Open AppBar")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 3: CrispChatbox in Fullscreen
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, FullscreenComposeActivity::class.java))
                    }) {
                        Text("Open Fullscreen")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 4: CrispChatbox hosted in ModalBottomSheet
                    Button(onClick = { showComposeSheet = true }) {
                        Text("Open BottomSheet")
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "View/Fragment",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 5: CrispChatboxView inflated from XML Layout
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, XmlViewActivity::class.java))
                    }) {
                        Text("Open View from XML Layout")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 6: CrispChatboxView hosted in CrispFragment inflated from XML Layout
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, XmlFragmentActivity::class.java))
                    }) {
                        Text("Open Fragment from XML Layout")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 7: CrispChatboxView hosted in CrispFragment instantiated programmatically
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, ProgrammaticFragmentActivity::class.java))
                    }) {
                        Text("Open Programmatic Fragment")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 8: CrispChatboxView hosted in BottomSheetDialog
                    OutlinedButton(onClick = {

                        (context as? FragmentActivity)?.let { activity ->
                            CrispChat.show(
                                activity,
                                websiteId = config.websiteId,
                                tokenId = config.tokenId
                            ).controller.also { it.addEventCallbacks(fragmentSheetCallbacks) }
                        }
                    }) {
                        Text("Open BottomSheet")
                    }
                }

                // Sheets cover the FABs, so calling a chatbox method opens the selected sheet
                ControllerFabs(
                    onChatboxClick = { isSheetControllerDialogShown = true },
                    onSessionClick = { isSessionControllerDialogShown = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(16.dp)
                )
            }

            AppDestinations.INLINE -> {
                // Option 2: CrispChatbox above NavigationBar
                val inlineController = rememberCrispChatboxController()
                // The standard Compose listener pattern, which also suits a controller created elsewhere
                // (e.g. in a ViewModel). Here, the controller is closed on dispose anyway.
                DisposableEffect(inlineController) {
                    val callbacks = ToastEventCallbacks(context, "Inline")
                    inlineController.addEventCallbacks(callbacks)
                    onDispose { inlineController.removeEventCallbacks(callbacks) }
                }
                WithControllerFabs(
                    controller = inlineController,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CrispChatbox(
                        config = config,
                        modifier = Modifier.fillMaxSize(),
                        edgeToEdge = EdgeToEdge.TOP,
                        controller = inlineController
                    )
                }
            }

            AppDestinations.SESSION -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp)
            ) {
                // Same title as the SessionControllerDialog
                Text(
                    text = stringResource(R.string.session_controller_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                SessionControllerForm(
                    scope = scope,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (isSheetControllerDialogShown) {
            ChatboxControllerDialog(
                controller = {
                    when (sheetControllerTarget) {
                        SheetTarget.COMPOSE -> {
                            showComposeSheet = true
                            sheetController
                        }

                        SheetTarget.FRAGMENT -> {
                            val activity = context as FragmentActivity
                            CrispChat.show(
                                activity,
                                websiteId = config.websiteId,
                                tokenId = config.tokenId
                            ).controller.also { it.addEventCallbacks(fragmentSheetCallbacks) }
                        }
                    }
                },
                scope = scope,
                onDismissRequest = { isSheetControllerDialogShown = false },
                header = {
                    SingleChoiceSegmentedButtonRow {
                        SheetTarget.entries.forEachIndexed { index, target ->
                            SegmentedButton(
                                selected = target == sheetControllerTarget,
                                onClick = { sheetControllerTarget = target },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index,
                                    SheetTarget.entries.size
                                )
                            ) {
                                Text(target.label)
                            }
                        }
                    }
                    Text(
                        text = "Calling a method opens this sheet, the call runs once it is ready.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            )
        }

        if (isSessionControllerDialogShown) {
            SessionControllerDialog(
                scope = scope,
                onDismissRequest = { isSessionControllerDialogShown = false }
            )
        }

        if (showComposeSheet) {
            CrispBottomSheetModal(
                config = config,
                controller = sheetController,
                onDismissRequest = {
                    scope.launch {
                        composeSheetState.hide()
                    }.invokeOnCompletion {
                        if (!composeSheetState.isVisible) {
                            showComposeSheet = false
                        }
                    }
                }
            )
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    SHEET("Sheet/Views", R.drawable.ic_sheet),
    INLINE("Inline", R.drawable.ic_inline),
    SESSION("Session", R.drawable.ic_user),
}

/** The sheet driven by the chatbox `controller` dialog of the [AppDestinations.SHEET] destination. */
enum class SheetTarget(val label: String) {
    COMPOSE("Compose modal"),
    FRAGMENT("DialogFragment"),
}

@Preview(showBackground = true)
@Composable
fun CatalogAppPreview() {
    CatalogTheme {
        CatalogApp()
    }
}
