package im.crisp.catalog.controller

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.crisp.catalog.R
import im.crisp.catalog.ui.theme.CatalogTheme
import im.crisp.sdk.api.CrispChatboxController
import kotlinx.coroutines.CoroutineScope

// region Compose

/** A FAB opening the [ChatboxControllerForm], above a FAB opening the [SessionControllerForm]. */
@Composable
fun ControllerFabs(
    onChatboxClick: () -> Unit,
    onSessionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmallFloatingActionButton(onClick = onChatboxClick) {
            Icon(
                painter = painterResource(R.drawable.ic_chatbox),
                contentDescription = stringResource(R.string.chatbox_controller_fab_description)
            )
        }
        SmallFloatingActionButton(onClick = onSessionClick) {
            Icon(
                painter = painterResource(R.drawable.ic_user),
                contentDescription = stringResource(R.string.session_controller_fab_description)
            )
        }
    }
}

/**
 * Shows [content] with [ControllerFabs] on its end side,
 * opening a [ChatboxControllerDialog] for [controller] or a [SessionControllerDialog].
 */
@Composable
fun WithControllerFabs(
    controller: CrispChatboxController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isChatboxDialogShown by rememberSaveable { mutableStateOf(false) }
    var isSessionDialogShown by rememberSaveable { mutableStateOf(false) }
    // Outlives the dialogs, so that pending calls are not canceled when they are closed
    val scope = rememberCoroutineScope()

    Box(modifier = modifier) {
        content()
        ControllerFabs(
            onChatboxClick = { isChatboxDialogShown = true },
            onSessionClick = { isSessionDialogShown = true },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp)
        )
    }

    if (isChatboxDialogShown) {
        ChatboxControllerDialog(
            controller = { controller },
            scope = scope,
            onDismissRequest = { isChatboxDialogShown = false }
        )
    }
    if (isSessionDialogShown) {
        SessionControllerDialog(
            scope = scope,
            onDismissRequest = { isSessionDialogShown = false }
        )
    }
}

@Composable
fun ChatboxControllerDialog(
    controller: () -> CrispChatboxController,
    scope: CoroutineScope,
    onDismissRequest: () -> Unit,
    header: @Composable () -> Unit = {}
) {
    FormDialog(R.string.chatbox_controller_dialog_title, onDismissRequest) {
        Column {
            header()
            ChatboxControllerForm(controller = controller, scope = scope)
        }
    }
}

@Composable
fun SessionControllerDialog(
    scope: CoroutineScope,
    onDismissRequest: () -> Unit
) {
    FormDialog(R.string.session_controller_dialog_title, onDismissRequest) {
        SessionControllerForm(scope = scope)
    }
}

@Composable
private fun FormDialog(
    @StringRes title: Int,
    onDismissRequest: () -> Unit,
    form: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(title)) },
        text = form,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.controller_dialog_close))
            }
        }
    )
}

// endregion

// region Views

/** Shows the [ChatboxControllerForm] of [controller] in a dialog, from a View-based activity. */
fun ComponentActivity.showChatboxControllerDialog(controller: CrispChatboxController) {
    // Outlives the dialog, so that pending calls are not canceled when it is closed
    showFormDialog(R.string.chatbox_controller_dialog_title) { modifier ->
        ChatboxControllerForm(controller = { controller }, scope = lifecycleScope, modifier = modifier)
    }
}

/** Shows the [SessionControllerForm] in a dialog, from a View-based activity. */
fun ComponentActivity.showSessionControllerDialog() {
    // Outlives the dialog, so that pending calls are not canceled when it is closed
    showFormDialog(R.string.session_controller_dialog_title) { modifier ->
        SessionControllerForm(scope = lifecycleScope, modifier = modifier)
    }
}

private fun ComponentActivity.showFormDialog(
    @StringRes title: Int,
    form: @Composable (Modifier) -> Unit
) {
    val view = ComposeView(this).apply {
        setContent {
            CatalogTheme {
                form(Modifier.padding(horizontal = 24.dp))
            }
        }
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(view)
        .setPositiveButton(R.string.controller_dialog_close, null)
        .show()
}

// endregion
