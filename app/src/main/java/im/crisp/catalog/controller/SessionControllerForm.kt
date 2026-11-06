package im.crisp.catalog.controller

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import im.crisp.catalog.settings.ChatboxPreferences
import im.crisp.sdk.api.Crisp
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.api.CrispSessionController
import im.crisp.sdk.model.Company
import im.crisp.sdk.model.SessionEventColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Selects the `websiteId` and sets the `tokenId` of the [CrispSessionController] to call,
 * initially the ones of the [chatboxes][ChatboxPreferences], without changing them,
 * then lists every [CrispSessionController] method, each with its parameters prefilled and a button to call it.
 *
 * Calls run in [scope][CoroutineScope], which should outlive the form,
 * and are saved for that config until dispatched to a chatbox loading it.
 */
@Composable
fun SessionControllerForm(
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val context = LocalContext.current
        var websiteId by rememberSaveable { mutableStateOf(ChatboxPreferences.getWebsiteId(context)) }
        var tokenId by rememberSaveable { mutableStateOf(ChatboxPreferences.getTokenId(context)) }
        // Instantiated again with each new config, for the next calls
        val controller = remember(context, websiteId, tokenId) {
            CrispSessionController(context, CrispConfig(websiteId, tokenId))
        }
        val method = SessionMethodScope({ controller }, scope)

        Section("config")
        WebsiteField(
            selected = websiteId,
            onSelect = { websiteId = it }
        )
        TokenIdCard(
            name = "setTokenId",
            savedTokenId = tokenId,
            pending = "Saving…",
            onSave = { newTokenId ->
                tokenId = newTokenId
                "Saved, for the next calls"
            }
        )

        Section("session")
        // Postponed, so that the chatbox can be acted on (e.g. closing this dialog) before it is reset
        method.Set("session.reset") {
            delay(CALL_DELAY)
            session.reset()
        }
        val dataKeys = rememberInput("keys (comma separated)", "sdk-version, os-version, device")
        val dataValues = rememberInput("values (comma separated, string, number or boolean)", "${Crisp.version}, ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER + ' ' + Build.MODEL}")
        method.Set("session.setData", dataKeys, dataValues) {
            session.setData(sessionData(dataKeys, dataValues))
        }
        val segments = rememberInput("segments (comma separated)", "app, android")
        val overwrite = rememberToggle("overwrite", false)
        method.Set("session.setSegments", segments, overwrite) {
            session.setSegments(segments.list, overwrite.checked)
        }
        val eventName = rememberInput("name", "sdk-test")
        val eventDataKeys = rememberInput("data keys (comma separated)", "sdk-source, sdk-version")
        val eventDataValues = rememberInput("data values (comma separated)", "android, ${Crisp.version}")
        val eventColor = rememberChoice("color", listOf(null) + SessionEventColor.entries, SessionEventColor.GREEN)
        method.Set("session.pushEvent", eventName, eventDataKeys, eventDataValues, eventColor) {
            session.pushEvent(
                name = eventName.text,
                data = sessionData(eventDataKeys, eventDataValues).takeIf { it.isNotEmpty() },
                color = eventColor.selected
            )
        }

        Section("user")
        val email = rememberInput("email", "john.doe@example.com")
        val signature = rememberInput("signature", "")
        method.Set("user.setEmail", email, signature) { user.setEmail(email.text, signature.textOrNull) }
        val phone = rememberInput("phone", "+33 6 12 34 56 78")
        method.Set("user.setPhone", phone) { user.setPhone(phone.text) }
        val nickname = rememberInput("nickname", "John Doe")
        method.Set("user.setNickname", nickname) { user.setNickname(nickname.text) }
        val avatar = rememberInput("url", "https://developer.android.com/static/images/brand/android-head_3D.png")
        method.Set("user.setAvatar", avatar) { user.setAvatar(avatar.text) }
        val companyName = rememberInput("name", "Crisp")
        val companyUrl = rememberInput("url", "https://crisp.chat")
        val companyDescription = rememberInput("description", "Customer messaging platform")
        val employmentTitle = rememberInput("employment title", "CTO")
        val employmentRole = rememberInput("employment role", "Engineering")
        val city = rememberInput("city", "Nantes")
        val country = rememberInput("country", "FR")
        method.Set(
            "user.setCompany",
            companyName, companyUrl, companyDescription, employmentTitle, employmentRole, city, country
        ) {
            user.setCompany(
                Company(
                    name = companyName.text,
                    url = companyUrl.textOrNull,
                    description = companyDescription.textOrNull,
                    employment = Company.Employment(employmentRole.text, employmentTitle.textOrNull),
                    geolocation = Company.Geolocation(city.textOrNull, country.textOrNull)
                )
            )
        }
    }
}

private class SessionMethodScope(
    val controller: () -> CrispSessionController,
    val scope: CoroutineScope
) {
    /** Calls a [CrispSessionController] setter, saved until dispatched. */
    @Composable
    fun Set(
        name: String,
        vararg parameters: Parameter,
        block: suspend CrispSessionController.() -> Unit
    ) = MethodCard(name, parameters, "Saving…", controller, scope, block)
}
