package im.crisp.catalog.controller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import im.crisp.catalog.settings.ChatboxPreferences
import im.crisp.catalog.settings.rememberTokenId
import im.crisp.catalog.settings.rememberWebsiteId
import im.crisp.sdk.api.CrispChatboxController
import im.crisp.sdk.api.CrispSessionController
import im.crisp.sdk.model.MessageContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Selects the `websiteId` and sets the `tokenId` of the [chatboxes][ChatboxPreferences], reloading them,
 * then lists every [CrispChatboxController] method, each with its parameters prefilled and a button to call it.
 *
 * Setters are in [CrispSessionController], see [SessionControllerForm].
 *
 * Calls run in [scope][CoroutineScope], which should outlive the form, and wait until the chatbox is ready.
 *
 * @param controller returns the controller to call, invoked on each call.
 */
@Composable
fun ChatboxControllerForm(
    controller: () -> CrispChatboxController,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val context = LocalContext.current
        val method = ChatboxMethodScope(controller, scope)

        Section("config")
        WebsiteField(
            selected = rememberWebsiteId(),
            onSelect = { ChatboxPreferences.setWebsiteId(context, it) }
        )
        // Not a CrispChatboxController method, as the tokenId is part of the CrispConfig they load
        TokenIdCard(
            name = "session.setTokenId",
            savedTokenId = rememberTokenId(),
            pending = "Waiting for the chatbox…",
            onSave = { tokenId ->
                delay(CALL_DELAY)
                ChatboxPreferences.setTokenId(context, tokenId)
                "Saved, reloading the chatbox"
            }
        )

        // TODO: restore with CrispChatboxController.chat
        // Section("chat")
        // method.Call("chat.show") { chat.show() }

        Section("session")
        method.Call("session.isOngoing") { session.isOngoing() }
        method.Call("session.getIdentifier") { session.getIdentifier() }
        val getDataKey = rememberInput("key", "sdk-version")
        method.Call("session.getData", getDataKey) { session.getData(getDataKey.text) }

        Section("user")
        method.Call("user.getEmail") { user.getEmail() }
        method.Call("user.getPhone") { user.getPhone() }
        method.Call("user.getNickname") { user.getNickname() }
        method.Call("user.getAvatar") { user.getAvatar() }
        method.Call("user.getCompany") { user.getCompany() }

        Section("message")
        val messageText = rememberInput("text", "Hello from the Crisp SDK Catalog!")
        method.Call("message.show", messageText) { message.show(MessageContent.TextContent(messageText.text)) }

        Section("helpdesk")
        method.Call("helpdesk.search") { helpdesk.search() }
        val locale = rememberInput("locale", "en")
        val slug = rememberInput("slug", "1ujgyqf")
        val title = rememberInput("title", "Crisp Android SDK")
        val category = rememberInput("category", "Widget")
        method.Call("helpdesk.openArticle", locale, slug, title, category) {
            helpdesk.openArticle(locale.text, slug.text, title.textOrNull, category.textOrNull)
        }

        Section("bot")
        val scenarioId = rememberInput("id", "scenario_0af8fc58-efbb-442d-a9d7-786e3b481d56")
        method.Call("bot.runScenario", scenarioId) { bot.runScenario(scenarioId.text) }
    }
}

private class ChatboxMethodScope(
    val controller: () -> CrispChatboxController,
    val scope: CoroutineScope
) {
    /** Calls a [CrispChatboxController] method, once the chatbox is ready. */
    @Composable
    fun Call(
        name: String,
        vararg parameters: Parameter,
        block: suspend CrispChatboxController.() -> Any?
    ) = MethodCard(name, parameters, "Waiting for the chatbox…", controller, scope, block)
}
