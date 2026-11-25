package im.crisp.catalog.old

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import im.crisp.client.external.ChatActivity
import im.crisp.client.external.Crisp
import java.util.UUID

/**
 * Sets the websiteId, tokenId and session of pre3.x SDK, then opens its chatbox,
 * to test their migration once `app` is installed over this app.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OldCatalogScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OldCatalogScreen() {
    val context = LocalContext.current
    val websiteIds = stringArrayResource(R.array.crisp_websites_id)
    val websiteNames = stringArrayResource(R.array.crisp_websites_name)
    // The websiteId and tokenId passed to pre-3.x SDK
    var websiteId by remember { mutableStateOf(ChatboxPreferences.getWebsiteId(context)) }
    var tokenId by remember { mutableStateOf(ChatboxPreferences.getTokenId(context)) }
    var selectedWebsite by rememberSaveable {
        mutableStateOf(websiteIds.indexOf(websiteId).coerceAtLeast(0))
    }
    var isWebsiteMenuExpanded by remember { mutableStateOf(false) }
    var tokenInput by rememberSaveable { mutableStateOf(tokenId.orEmpty()) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text("SDK ${Crisp.getSDKVersion()}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Website: ${websiteId ?: "not configured"}\nToken: ${tokenId ?: "none"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            enabled = websiteId != null,
            onClick = { context.startActivity(Intent(context, ChatActivity::class.java)) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Open chatbox")
        }

        HorizontalDivider()
        Text("Website", style = MaterialTheme.typography.titleMedium)
        ExposedDropdownMenuBox(
            expanded = isWebsiteMenuExpanded,
            onExpandedChange = { isWebsiteMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = websiteNames[selectedWebsite],
                onValueChange = {},
                readOnly = true,
                label = { Text("websiteId") },
                supportingText = { Text(websiteIds[selectedWebsite]) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isWebsiteMenuExpanded) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = isWebsiteMenuExpanded,
                onDismissRequest = { isWebsiteMenuExpanded = false }
            ) {
                websiteNames.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            selectedWebsite = index
                            isWebsiteMenuExpanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
        Button(onClick = {
            val id = websiteIds[selectedWebsite]
            Crisp.configure(context.applicationContext, id)
            ChatboxPreferences.setWebsiteId(context, id)
            websiteId = id
            toast("Configured")
        }) {
            Text("Crisp.configure")
        }

        HorizontalDivider()
        Text("Token", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("tokenId") },
            placeholder = { Text("none") },
            trailingIcon = {
                if (tokenInput.isNotEmpty()) {
                    IconButton(onClick = { tokenInput = "" }) {
                        Icon(painter = painterResource(R.drawable.ic_clear), contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = websiteId != null,
                onClick = {
                    // Unset if empty
                    val id = tokenInput.trim().ifEmpty { null }
                    Crisp.setTokenID(context, id)
                    ChatboxPreferences.setTokenId(context, id)
                    tokenInput = id.orEmpty()
                    tokenId = id
                    toast(if (id == null) "Token unset" else "Token set")
                }
            ) {
                Text("Crisp.setTokenID")
            }
            OutlinedButton(onClick = { tokenInput = UUID.randomUUID().toString() }) {
                Text("Generate")
            }
        }

        HorizontalDivider()
        Text("Session", style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = websiteId != null,
            onClick = {
                Crisp.resetChatSession(context)
                toast("Session reset")
            }
        ) {
            Text("Crisp.resetChatSession")
        }
    }
}
