package im.crisp.catalog.controller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import im.crisp.catalog.R
import im.crisp.sdk.api.CrispConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Delay before calls acting on the chatbox, so that it can be acted on first (e.g. closing the dialog). */
internal val CALL_DELAY = 2.seconds

// region Parameters

internal sealed interface Parameter

internal class Input(val label: String, default: String) : Parameter {
    var text by mutableStateOf(default)
    val textOrNull: String? get() = text.takeIf { it.isNotBlank() }
    val list: List<String> get() = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

internal class Toggle(val label: String, default: Boolean) : Parameter {
    var checked by mutableStateOf(default)
}

/** One of [options], `null` meaning none. */
internal class Choice<T>(val label: String, val options: List<T?>, default: T?) : Parameter {
    var selected by mutableStateOf(default)
}

@Composable
internal fun rememberInput(label: String, default: String) = remember { Input(label, default) }

@Composable
internal fun <T> rememberChoice(label: String, options: List<T?>, default: T?) =
    remember { Choice(label, options, default) }

@Composable
internal fun rememberToggle(label: String, default: Boolean) = remember { Toggle(label, default) }

/** Pairs [keys] with [values] in order, ignoring keys without a value. */
internal fun sessionData(keys: Input, values: Input): Map<String, Any> =
    keys.list.zip(values.list) { key, value -> key to value.toSessionValue() }.toMap()

/** Session values are typed: `true`/`false` are booleans and numbers are numbers. */
private fun String.toSessionValue(): Any = toBooleanStrictOrNull() ?: toLongOrNull() ?: toDoubleOrNull() ?: this

// endregion

// region Layout

/** Selects the `websiteId` of the [CrispConfig] by name, among `R.array.crisp_websites_id`. */
@Composable
internal fun WebsiteField(selected: String, onSelect: (String) -> Unit) {
    val ids = stringArrayResource(R.array.crisp_websites_id).toList()
    val names = stringArrayResource(R.array.crisp_websites_name)

    DropdownField(
        label = "websiteId",
        options = ids,
        selected = selected,
        // A saved ID that is no longer listed is shown as is
        optionText = { id -> names.getOrNull(ids.indexOf(id)) ?: id },
        onSelect = onSelect
    )
}

/**
 * Sets the `tokenId` of the [CrispConfig], initially [savedTokenId], reset when it changes,
 * with a button named [name].
 *
 * Shows [pending] while [onSave] runs, then the result it returns.
 */
@Composable
internal fun TokenIdCard(
    name: String,
    savedTokenId: String?,
    pending: String,
    onSave: suspend (tokenId: String?) -> String
) {
    val scope = rememberCoroutineScope()
    var tokenId by remember(savedTokenId) { mutableStateOf(savedTokenId.orEmpty()) }
    var result by remember { mutableStateOf<String?>(null) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = tokenId,
                onValueChange = { tokenId = it },
                label = { Text("tokenId") },
                placeholder = { Text("none") },
                trailingIcon = {
                    if (tokenId.isNotEmpty()) {
                        IconButton(onClick = { tokenId = "" }) {
                            Icon(painter = painterResource(R.drawable.ic_clear), contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { tokenId = UUID.randomUUID().toString() }) {
                Text("Generate")
            }
            Button(
                onClick = {
                    val newTokenId = tokenId.trim().ifEmpty { null }
                    if (newTokenId == savedTokenId) {
                        result = "Unchanged"
                    } else {
                        result = pending
                        scope.launch { result = onSave(newTokenId) }
                    }
                }
            ) {
                Text(text = name, fontFamily = FontFamily.Monospace)
            }
            result?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun Section(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * A card with the [parameters] of a method and a button named [name] to call it,
 * running [block] on the [target] read at click time, in [scope].
 *
 * Shows [pending] until [block] returns, then its result.
 */
@Composable
internal fun <T> MethodCard(
    name: String,
    parameters: Array<out Parameter>,
    pending: String,
    target: () -> T,
    scope: CoroutineScope,
    block: suspend T.() -> Any?
) {
    var result by remember { mutableStateOf<String?>(null) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            parameters.forEach { parameter ->
                when (parameter) {
                    is Input -> OutlinedTextField(
                        value = parameter.text,
                        onValueChange = { parameter.text = it },
                        label = { Text(parameter.label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    is Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = parameter.checked,
                            onCheckedChange = { parameter.checked = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(parameter.label)
                    }
                    is Choice<*> -> ChoiceField(parameter)
                }
            }
            Button(
                onClick = {
                    result = pending
                    val receiver = target()
                    scope.launch {
                        val value = receiver.block()
                        result = if (value == Unit) "Done" else "Result: $value"
                    }
                }
            ) {
                Text(text = name, fontFamily = FontFamily.Monospace)
            }
            result?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun <T> ChoiceField(choice: Choice<T>) {
    DropdownField(
        label = choice.label,
        options = choice.options,
        selected = choice.selected,
        optionText = { it.toOptionText() },
        onSelect = { choice.selected = it }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T,
    optionText: (T) -> String,
    onSelect: (T) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = isExpanded,
        onExpandedChange = { isExpanded = it }
    ) {
        OutlinedTextField(
            value = optionText(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        onSelect(option)
                        isExpanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

private fun Any?.toOptionText(): String = this?.toString() ?: "none"

// endregion
