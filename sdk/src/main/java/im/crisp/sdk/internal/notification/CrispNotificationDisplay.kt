package im.crisp.sdk.internal.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import im.crisp.sdk.R
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log

/**
 * A notification sent by the Crisp servers through FCM, as data only (the app shows it), with these keys.
 * Same payload as for the pre-3.x SDK.
 */
internal data class CrispNotificationPayload(
    val websiteId: String,
    val sessionId: String,
    val title: String?,
    val body: String?,
) {

    companion object {
        private const val SENDER = "sender"
        private const val SENDER_CRISP = "crisp"
        private const val WEBSITE_ID = "website_id"
        private const val SESSION_ID = "session_id"
        private const val TITLE = "title"
        private const val BODY = "body"

        fun isCrisp(data: Map<String, String?>): Boolean = data[SENDER] == SENDER_CRISP

        fun from(data: Map<String, String?>): CrispNotificationPayload? {
            if (!isCrisp(data)) return null
            return CrispNotificationPayload(
                websiteId = data[WEBSITE_ID]?.takeIf { it.isNotBlank() } ?: return null,
                sessionId = data[SESSION_ID]?.takeIf { it.isNotBlank() } ?: return null,
                title = data[TITLE],
                body = data[BODY],
            )
        }

        /**
         * The website and session of a notification tapped,
         * from the extras added by [CrispNotificationDisplay].
         */
        fun fromIntent(intent: Intent): Pair<String, String>? {
            if (intent.getStringExtra(SENDER) != SENDER_CRISP) return null
            val websiteId = intent.getStringExtra(WEBSITE_ID)?.takeIf { it.isNotBlank() } ?: return null
            val sessionId = intent.getStringExtra(SESSION_ID)?.takeIf { it.isNotBlank() } ?: return null
            return websiteId to sessionId
        }

        fun addExtras(intent: Intent, websiteId: String, sessionId: String): Intent = intent
            .putExtra(SENDER, SENDER_CRISP)
            .putExtra(WEBSITE_ID, websiteId)
            .putExtra(SESSION_ID, sessionId)
    }
}

/**
 * Shows the notifications of the Crisp servers, as the pre-3.x SDK did: one per website (tagged with its id),
 * replaced by the next one, in a channel per website (`crisp_chatbox_<websiteId>`,
 * kept from the pre-3.x SDK so that the user settings of the channel are kept).
 *
 * Customized like Firebase notifications, with the `com.google.firebase.messaging.default_notification_icon`
 * and `default_notification_color` meta-data of the app, and a `crisp_chat_message_receive` raw sound.
 */
internal object CrispNotificationDisplay {
    private const val LOG_TAG = "CrispNotifications"
    private const val NOTIFICATION_ID = 0
    private const val CHANNEL_ID_PREFIX = "crisp_chatbox_"
    private const val FIREBASE_DEFAULT_ICON = "com.google.firebase.messaging.default_notification_icon"
    private const val FIREBASE_DEFAULT_COLOR = "com.google.firebase.messaging.default_notification_color"
    private const val LIGHTS_ON_MS = 500
    private const val LIGHTS_OFF_MS = 2000
    private const val CHANNEL_NAME_PREFIX = "im.crisp.sdk.notification.channel."
    private const val CHANNEL_NAME_DEFAULT = "${CHANNEL_NAME_PREFIX}default"
    private const val ELLIPSIS = "[…]"

    /** Whether the user lets the app show notifications (the `POST_NOTIFICATIONS` permission from Android 13). */
    fun areEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Removes the notification shown for [websiteId], if any, see [CrispNotificationStore]. */
    fun cancel(context: Context, websiteId: String) {
        NotificationManagerCompat.from(context).cancel(websiteId, NOTIFICATION_ID)
    }

    /**
     * The notification of [payload], opening the chatbox when tapped if [opensChatbox] (see [pendingIntent]),
     * to [notify], or `null` if it cannot be shown: not allowed by the user, or its channel turned off.
     *
     * Creates its channel if needed, see [createChannel].
     */
    fun buildNotification(
        context: Context,
        payload: CrispNotificationPayload,
        backStack: TaskStackBuilder?,
        opensChatbox: Boolean,
    ): Notification? {
        if (!areEnabled(context)) {
            log(Level.WARN, LOG_TAG, "Notification not shown: notifications are disabled or not allowed by the user")
            return null
        }
        val channelId = createChannel(context, payload.websiteId)
        val manager = NotificationManagerCompat.from(context)
        // Turned off by the user for this website: Android would drop it silently.
        // Before Android 8, no channel
        if (manager.getNotificationChannelCompat(channelId)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            log(Level.WARN, LOG_TAG, "Notification not shown: its channel is turned off by the user")
            return null
        }
        val hasActive = manager.activeNotifications.any { it.tag == payload.websiteId }
        val body = if (hasActive) "$ELLIPSIS\n${payload.body.orEmpty()}" else payload.body
        val color = metaColor(context)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(metaIcon(context))
            .setColor(color)
            .setContentTitle(payload.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent(context, payload, backStack, opensChatbox))
            // Hides the message on the lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // Before Android 8, no channel, so set on each notification.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri(context))
            // The default timings of Android (AOSP), not readable as internal, which devices rarely change
            .setLights(color, LIGHTS_ON_MS, LIGHTS_OFF_MS)
            // Not DEFAULT_SOUND nor DEFAULT_LIGHTS: Android would use its defaults instead of these, whatever the order
            .setDefaults(Notification.DEFAULT_VIBRATE)
            .build()
    }

    /**
     * Shows [notification] for [websiteId], built by [buildNotification],
     * replacing the one shown for it, if any.
     */
    @SuppressLint("MissingPermission") // Checked by buildNotification
    fun notify(context: Context, websiteId: String, notification: Notification) {
        NotificationManagerCompat.from(context).notify(websiteId, NOTIFICATION_ID, notification)
    }

    /**
     * Creates the channel of [websiteId], or renames it if the app names it, see [channelName]. Does nothing before
     * Android 8, which has no channel.
     *
     * Its other settings are only set when created: the user may have changed them since, and Android keeps them.
     */
    private fun createChannel(context: Context, websiteId: String): String {
        val channelId = "$CHANNEL_ID_PREFIX$websiteId"
        val manager = NotificationManagerCompat.from(context)
        val channelName = channelName(context, websiteId)
        manager.getNotificationChannelCompat(channelId)?.let { existing ->
            // Renamed if the meta-data or the locale changed, keeping its settings.
            // Otherwise kept, e.g. the "<website> chatbox" name of the pre-3.x SDK,
            // more telling than the default one
            if (channelName != null && existing.name?.toString() != channelName.toString()) {
                manager.createNotificationChannel(existing.toBuilder().setName(channelName).build())
            }
            return channelId
        }
        val name = channelName ?: context.getText(R.string.crisp_sdk_notification_channel_default_name)
        // Hidden on the lock screen by each notification (VISIBILITY_PRIVATE), as channels do not override it by default
        val channel = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(name)
            .setShowBadge(false)
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(metaColor(context))
            .setSound(soundUri(context), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            .build()
        manager.createNotificationChannel(channel)
        return channelId
    }

    /**
     * What tapping the notification of [payload] opens:
     * - by default, the chatbox above the current screen of the app, in [CrispNotificationActivity], which opens the
     *   launcher `Activity` below if the app is not running,
     * - with [backStack] from the app, its `Activity` stack (replacing the task of the app), then the chatbox above it,
     * - without [opensChatbox], [backStack] or the launcher `Activity`, its last `Intent` getting the extras of the
     *   notification, for the app to show the chatbox.
     *
     * Never the chatbox alone: it would leave its translucent sheet over the home screen.
     * So not tappable (`null`) for an app without launcher `Activity` nor [backStack].
     */
    private fun pendingIntent(
        context: Context,
        payload: CrispNotificationPayload,
        backStack: TaskStackBuilder?,
        opensChatbox: Boolean,
    ): PendingIntent? {
        // One per website, not to replace the extras of the notification of another website
        val requestCode = payload.websiteId.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // A copy, not to add to the one of the app, which may pass the same one for each notification
        val appBackStack = backStack?.takeIf { it.intentCount > 0 }?.copy(context)
        val launchIntent = launchIntent(context)
        if (appBackStack == null && launchIntent == null) {
            log(Level.WARN, LOG_TAG, "The app has no launcher Activity to open when the notification is tapped")
            return null
        }
        if (opensChatbox) {
            val chatbox = CrispNotificationPayload.addExtras(
                Intent(context, CrispNotificationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                payload.websiteId,
                payload.sessionId,
            )
            if (appBackStack == null) {
                // In the task of the app, brought to the front as is, or a new one
                return PendingIntent.getActivity(
                    context,
                    requestCode,
                    chatbox.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags,
                )
            }
            return appBackStack.addNextIntent(chatbox).getPendingIntent(requestCode, flags)
        }
        val builder = appBackStack
            ?: launchIntent?.let { TaskStackBuilder.create(context).addNextIntent(it) }
            ?: return null
        builder.editIntentAt(builder.intentCount - 1)?.let {
            CrispNotificationPayload.addExtras(it, payload.websiteId, payload.sessionId)
        }
        return builder.getPendingIntent(requestCode, flags)
    }

    private fun TaskStackBuilder.copy(context: Context): TaskStackBuilder =
        TaskStackBuilder.create(context).also { copy ->
            for (index in 0 until intentCount) {
                editIntentAt(index)?.let { copy.addNextIntent(Intent(it)) }
            }
        }

    /** The launcher `Activity` of the app, if any. */
    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)

    @DrawableRes
    private fun metaIcon(context: Context): Int =
        metaData(context)?.getInt(FIREBASE_DEFAULT_ICON, 0)?.takeIf { it != 0 } ?: R.drawable.crisp_sdk_notification_icon

    /**
     * The color of the notifications,
     * from the `com.google.firebase.messaging.default_notification_color` meta-data of the app
     * (resolved in the current theme, as the app may want it),
     * or the light Crisp blue, whatever the theme.
     */
    private fun metaColor(context: Context): Int {
        val colorRes = metaData(context)?.getInt(FIREBASE_DEFAULT_COLOR, 0)?.takeIf { it != 0 }
        if (colorRes != null) {
            try {
                return ContextCompat.getColor(context, colorRes)
            } catch (e: Resources.NotFoundException) {
                log(Level.WARN, LOG_TAG, "The $FIREBASE_DEFAULT_COLOR meta-data is not a color resource, ignored")
            }
        }
        return ContextCompat.getColor(context.lightConfigurationContext(), R.color.crisp_sdk_blue_crisp)
    }

    /** This context with the light theme, to resolve the light values of the resources whatever the theme. */
    private fun Context.lightConfigurationContext(): Context {
        val configuration = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        return createConfigurationContext(configuration)
    }

    /**
     * The name of the channel of [websiteId] from the meta-data of the app, the first set of:
     * - `im.crisp.sdk.notification.channel.<websiteId>`,
     *    the `websiteId` as is (meta-data names take any character),
     * - `im.crisp.sdk.notification.channel.default`,
     * - otherwise `null`, the channel being named "Crisp" when created.
     *
     * Each as a string resource (`android:resource`), translated in the current locale, or a text (`android:value`).
     */
    private fun channelName(context: Context, websiteId: String): CharSequence? {
        val metaData = metaData(context)
        // Either type, a resource (Int) or a text, while typed getters log a warning for the other one
        @Suppress("DEPRECATION")
        fun named(key: String): CharSequence? = when (val value = metaData?.get(key)) {
            is Int -> try {
                context.getText(value)
            } catch (e: Resources.NotFoundException) {
                log(Level.WARN, LOG_TAG, "The $key meta-data is not a string resource, ignored")
                null
            }
            is CharSequence -> value.takeIf { it.isNotBlank() }
            else -> null
        }
        return named("$CHANNEL_NAME_PREFIX$websiteId") ?: named(CHANNEL_NAME_DEFAULT)
    }

    private fun metaData(context: Context): Bundle? = try {
        context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        ).metaData
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * The sound of the notifications: `res/raw/crisp_chat_message_receive` of the SDK, or of the app, which overrides
     * it by declaring its own with the same name, as for the pre-3.x SDK.
     */
    private fun soundUri(context: Context): Uri =
        "android.resource://${context.packageName}/${R.raw.crisp_chat_message_receive}".toUri()
}
