package im.crisp.sdk.internal.notification

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import im.crisp.sdk.R
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.PickerClickEvent
import im.crisp.sdk.internal.bridge.types.ConversationMessageContent
import im.crisp.sdk.internal.bridge.types.GenericMessage
import im.crisp.sdk.internal.bridge.types.Message
import im.crisp.sdk.internal.bridge.types.MessageFingerprint
import im.crisp.sdk.internal.bridge.types.MessageSender
import im.crisp.sdk.internal.bridge.types.Origin
import im.crisp.sdk.internal.bridge.types.Participant
import im.crisp.sdk.internal.bridge.types.PickerId
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.findActivity
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.internal.permission.CrispPermissionIntent
import im.crisp.sdk.internal.permission.CrispPermissionManager
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prompts the user to enable notifications in the chat, as the iOS SDK and SDK 2.x do,
 * when [CrispNotificationPrompt] decides it, then handles their choice.
 *
 * The prompt is a message injected in the page, so it is not kept by the Web client:
 * whether it was shown and answered is recorded per session ([CrispNotificationSession.promptShown] and
 * [CrispNotificationSession.promptAnswered]).
 */
internal class CrispNotificationPrompter(
    private val context: Context,
    private val chatboxStore: CrispChatboxStore,
    private val permissionManager: CrispPermissionManager,
    private val store: CrispNotificationStore = CrispNotificationStore.get(context),
) {
    /**
     * Shows a message prompting the user to enable notifications,
     * if notifications are enabled for the website but not allowed by the user yet (or its channel turned off),
     * and the user did not answer it on this session: below the identity request of the Web client,
     * or standalone once the user sent a message, see [CrispNotificationPrompt] (told of the messages sent).
     *
     * Only from Android 13, where notifications need the `POST_NOTIFICATIONS` runtime permission.
     *
     * Runs as long as the page, until shown or not needed anymore,
     * checking the page every [CrispNotificationPrompt.POLLING] while its session is joined.
     */
    suspend fun promptWhenNeeded(config: CrispConfig, bridge: CrispClientBridge, prompt: CrispNotificationPrompt) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!CrispFirebase.isAvailable(context)) return
        if (!isPermissionDeclared()) {
            if (isUndeclaredLogged.compareAndSet(false, true)) {
                log(
                    Level.ERROR,
                    LOG_TAG,
                    "Cannot ask for notifications: android.permission.POST_NOTIFICATIONS must be declared " +
                        "in AndroidManifest.xml",
                )
            }
            return
        }
        while (true) {
            // Not while disconnected, e.g. in background
            val sessionId = chatboxStore.awaitJoined()
            val session = promptableSession(config, sessionId) ?: return
            val decision = prompt.decide(bridge.getIdentityPromptState(PROMPT_FINGERPRINT), session.promptShown)
            if (decision == CrispNotificationPrompt.Decision.WAIT) {
                delay(CrispNotificationPrompt.POLLING)
                continue
            }
            if (decision != CrispNotificationPrompt.Decision.DONE) {
                val isStandalone = decision == CrispNotificationPrompt.Decision.SHOW_STANDALONE
                log(
                    Level.DEBUG,
                    LOG_TAG,
                    "Prompting to enable notifications on session ${sessionId.short()}, standalone $isStandalone",
                )
                bridge.injectMessage(promptMessage(isStandalone))
                // Shown again on the next loads until answered
                store.update(config.websiteId) { record ->
                    val shown = record.session?.takeIf { it.sessionId == sessionId } ?: return@update record
                    record.copy(session = shown.copy(promptShown = true))
                }
            }
            return
        }
    }

    /**
     * The recorded session of the joined [sessionId] if the prompt can be shown,
     * not answered, and notifications not allowed yet or the channel of the website turned off.
     */
    private fun promptableSession(config: CrispConfig, sessionId: String): CrispNotificationSession? {
        val record = store.record(config.websiteId)
        val session = record.session?.takeIf { it.sessionId == sessionId } ?: return null
        val isAllowed = isPermissionGranted() && CrispNotificationDisplay.isChannelEnabled(context, config.websiteId)
        return session.takeIf { record.enabled && !it.promptAnswered && !it.revoked && !isAllowed }
    }

    /**
     * Handles the choice of the user in the prompt, asking for the permission if accepted,
     * then calling [onGranted] (e.g. to register the `notificationId`).
     * If already granted, the channel of the website is turned off, so the notification settings of the app
     * are opened instead, checked again once the chatbox is resumed.
     *
     * Right away, even while the socket is disconnected: the choices of the prompt have a handler,
     * so the Web client does not update the message on the Crisp servers
     * (see `onMessagePickerButtonClick` of `crisp-client`).
     */
    suspend fun onPickerClick(
        config: CrispConfig,
        event: PickerClickEvent,
        onGranted: () -> Unit,
    ) {
        if (event.fingerprint != PROMPT_FINGERPRINT) return
        val loaded = chatboxStore.awaitLoaded()
        log(Level.DEBUG, LOG_TAG, "Prompt answered on session ${loaded.short()}: ${event.choiceValue}")
        store.update(config.websiteId) { record ->
            val session = record.session?.takeIf { it.sessionId == loaded } ?: return@update record
            record.copy(session = session.copy(promptAnswered = true))
        }
        when (event.choiceValue) {
            CHOICE_ENABLE -> if (isPermissionGranted()) {
                openNotificationSettings()
            } else {
                permissionManager.requestNativePermissions(CrispPermissionIntent.NOTIFICATIONS) {
                    if (it && isPermissionGranted()) onGranted()
                }
            }

            CHOICE_SKIP -> Unit
            else -> log(Level.WARN, LOG_TAG, "Unknown choice '${event.choiceValue}' in the notification prompt")
        }
    }

    /**
     * Opens the notification settings of the app, listing the channel of each website,
     * as the Settings button of the permission request does.
     */
    @SuppressLint("InlinedApi") // Only prompted from Android 13, see promptWhenNeeded
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try {
            context.findActivity()?.startActivity(intent)
                ?: context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            log(Level.WARN, LOG_TAG, "Cannot open the notification settings", e)
        }
    }

    private fun isPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("InlinedApi") // Only checked from Android 13, see promptWhenNeeded
    private fun isPermissionDeclared(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun promptMessage(isStandalone: Boolean): Message {
        val ask = context.getString(R.string.crisp_sdk_prompt_notification_ask)
        // crisp-client ships both strings as plain text, so the emphasis is applied here, as on iOS
        val text = if (isStandalone) {
            "**${context.getString(R.string.crisp_sdk_prompt_notification_reply)}**\n_${ask}_"
        } else {
            "_${ask}_"
        }
        return Message(
            generic = GenericMessage(
                content = ConversationMessageContent.PickerContent(
                    id = PickerId(""),
                    text = text,
                    choices = listOf(
                        ConversationMessageContent.PickerContent.Choice(
                            label = context.getString(R.string.crisp_sdk_action_notifications_enable),
                            selected = false,
                            value = CHOICE_ENABLE,
                        ),
                        ConversationMessageContent.PickerContent.Choice(
                            label = context.getString(R.string.crisp_sdk_action_deny),
                            selected = false,
                            value = CHOICE_SKIP,
                        ),
                    ),
                    required = false,
                ),
                origin = Origin.CHAT,
                timestamp = System.currentTimeMillis(),
                from = MessageSender.OPERATOR,
                user = Participant(nickname = "System"),
                automated = true,
            ),
            fingerprint = PROMPT_FINGERPRINT,
        )
    }

    companion object {
        private const val LOG_TAG = "CrispNotifications"

        /** Of the injected prompt, the same as on iOS. */
        val PROMPT_FINGERPRINT = MessageFingerprint(-10006)

        private const val CHOICE_ENABLE = "enable"
        private const val CHOICE_SKIP = "skip"

        private val isUndeclaredLogged = AtomicBoolean(false)
    }
}
