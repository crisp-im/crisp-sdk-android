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
    private const val CHANNEL_NAME_PREFIX = "im.crisp.sdk.notification.channel."
    private const val CHANNEL_NAME_DEFAULT = "${CHANNEL_NAME_PREFIX}default"
    private const val ELLIPSIS = "[…]"

    /** Whether the user lets the app show notifications (the `POST_NOTIFICATIONS` permission from Android 13). */
    fun areEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Shows [payload], opening [backStack] (the launcher `Activity` of the app by default) with its extras when
     * tapped, then the chatbox in its own `Activity` if [opensChatbox].
     *
     * Returns whether it is shown.
     */
    @SuppressLint("MissingPermission") // Checked by areEnabled
    fun show(
        context: Context,
        payload: CrispNotificationPayload,
        backStack: TaskStackBuilder?,
        opensChatbox: Boolean,
    ): Boolean {
        if (!areEnabled(context)) {
            log(Level.WARN, LOG_TAG, "Notification not shown: notifications are disabled or not allowed by the user")
            return false
        }
        val channelId = createChannel(context, payload.websiteId)
        val manager = NotificationManagerCompat.from(context)
        val hasActive = manager.activeNotifications.any { it.tag == payload.websiteId }
        val body = if (hasActive) "$ELLIPSIS\n${payload.body.orEmpty()}" else payload.body
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(metaIcon(context))
            .setContentTitle(payload.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            // Hides the message on the lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent(context, payload, backStack, opensChatbox))
            // Before Android 8, no channel
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .apply {
                setColor(metaColor(context))
                setSound(soundUri(context))
            }
            .build()
        manager.notify(payload.websiteId, NOTIFICATION_ID, notification)
        return true
    }

    /**
     * Creates the channel of [websiteId], or renames it, see [channelName]. Does nothing before Android 8, which has
     * no channel.
     *
     * Its other settings are only set when created: the user may have changed them since, and Android keeps them.
     */
    private fun createChannel(context: Context, websiteId: String): String {
        val channelId = "$CHANNEL_ID_PREFIX$websiteId"
        val manager = NotificationManagerCompat.from(context)
        val name = channelName(context, websiteId)
        manager.getNotificationChannelCompat(channelId)?.let { existing ->
            // Renamed if the meta-data or the locale changed (or created by the pre-3.x SDK), keeping its settings
            if (existing.name?.toString() != name.toString()) {
                manager.createNotificationChannel(existing.toBuilder().setName(name).build())
            }
            return channelId
        }
        // Hidden on the lock screen by each notification (VISIBILITY_PRIVATE), as channels do not override it by default
        val channel = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(name)
            .setShowBadge(false)
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .apply {
                setLightColor(metaColor(context))
                setSound(
                    soundUri(context),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
            }
            .build()
        manager.createNotificationChannel(channel)
        return channelId
    }

    private fun pendingIntent(
        context: Context,
        payload: CrispNotificationPayload,
        backStack: TaskStackBuilder?,
        opensChatbox: Boolean,
    ): PendingIntent? {
        // Never the chatbox alone. Started as the root of a new task,
        // it would clear the one of the app (as a task stack does),
        // leaving its translucent sheet over the home screen. So not tappable without back stack
        val builder = backStack?.takeIf { it.intentCount > 0 } ?: defaultBackStack(context)
        if (builder == null) {
            log(Level.WARN, LOG_TAG, "The app has no launcher Activity to open when the notification is tapped")
            return null
        }
        if (opensChatbox) {
            builder.addNextIntent(
                CrispNotificationPayload.addExtras(
                    Intent(context, CrispNotificationActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    payload.websiteId,
                    payload.sessionId,
                )
            )
        } else {
            builder.editIntentAt(builder.intentCount - 1)?.let {
                CrispNotificationPayload.addExtras(it, payload.websiteId, payload.sessionId)
            }
        }
        // One per website, not to replace the extras of the notification of another website
        return builder.getPendingIntent(
            payload.websiteId.hashCode(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun defaultBackStack(context: Context): TaskStackBuilder? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return TaskStackBuilder.create(context).addNextIntent(launchIntent)
    }

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
     * The name of the channel of [websiteId], from the meta-data of the app, the first set of:
     * - `im.crisp.sdk.notification.channel.<websiteId>`, the `websiteId` as is (meta-data names take any character),
     * - `im.crisp.sdk.notification.channel.default`,
     * - otherwise "Crisp".
     *
     * Each as a string resource (`android:resource`), translated in the current locale, or a text (`android:value`).
     */
    private fun channelName(context: Context, websiteId: String): CharSequence {
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
        return named("$CHANNEL_NAME_PREFIX$websiteId")
            ?: named(CHANNEL_NAME_DEFAULT)
            ?: context.getText(R.string.crisp_sdk_notification_channel_default_name)
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
