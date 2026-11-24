package im.crisp.sdk.internal.notification

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(AndroidJUnit4::class)
class CrispNotificationDisplayTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = NotificationManagerCompat.from(context)
    private val shadowManager: ShadowNotificationManager
        get() = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
    private val payload = CrispNotificationPayload("website", "session_a", "Operator", "Hello")
    private val launcher = ComponentName(context, "im.crisp.test.LauncherActivity")

    @Before fun setUp() {
        addLauncher()
    }

    private fun addLauncher() {
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(launcher)
        packageManager.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun show(backStack: TaskStackBuilder? = null, opensChatbox: Boolean = true): Boolean {
        val notification = CrispNotificationDisplay.buildNotification(context, payload, backStack, opensChatbox)
            ?: return false
        CrispNotificationDisplay.notify(context, payload.websiteId, notification)
        return true
    }

    private val shown: Notification?
        get() = shadowManager.getNotification("website", 0)

    private fun intents(notification: Notification): List<Intent> =
        shadowOf(notification.contentIntent).savedIntents.toList()

    // region Display

    @Test fun `a notification is shown per website, in its channel`() {
        assertTrue(show())

        val notification = shown!!
        assertEquals("crisp_chatbox_website", notification.channelId)
        assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, manager.getNotificationChannelCompat("crisp_chatbox_website")?.importance)
        assertEquals("Hello", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun `a notification shows the time it was received`() {
        val before = System.currentTimeMillis()
        show()
        val after = System.currentTimeMillis()

        val notification = shown!!
        assertTrue(notification.`when` in before..after)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    }

    @Test fun `the next notification of a website replaces it, telling there are previous messages`() {
        show()
        show()

        assertEquals(1, shadowManager.allNotifications.size)
        assertEquals("[…]\nHello", shown!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
    // Before Android 8, which has no channel to set the sound
    @Config(sdk = [25])
    @Test fun `the Crisp sound is set without the default one, which would replace it before Android 8`() {
        show()

        assertEquals(0, shown!!.defaults and Notification.DEFAULT_SOUND)
        assertTrue(shown!!.sound.toString().startsWith("android.resource://${context.packageName}/"))
    }

    @Config(sdk = [25])
    @Test fun `the lights blink in the Crisp color without the default ones, which would replace them before Android 8`() {
        show()

        val notification = shown!!
        assertEquals(0, notification.defaults and Notification.DEFAULT_LIGHTS)
        assertTrue(notification.flags and Notification.FLAG_SHOW_LIGHTS != 0)
        assertEquals(context.getColor(im.crisp.sdk.R.color.crisp_sdk_blue_crisp), notification.ledARGB)
        assertEquals(500, notification.ledOnMS)
        assertEquals(2000, notification.ledOffMS)
    }

    @Test fun `not shown once notifications are not allowed`() {
        shadowManager.setNotificationsEnabled(false)

        assertFalse(show())
        assertNull(shown)
    }

    @Test fun `not shown once its channel is turned off by the user`() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder("crisp_chatbox_website", NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("Crisp")
                .build()
        )

        assertFalse(show())
        assertNull(shown)
    }

    @Test fun `a channel is enabled until turned off by the user, even before created`() {
        assertTrue(CrispNotificationDisplay.isChannelEnabled(context, "website"))

        manager.createNotificationChannel(
            NotificationChannelCompat.Builder("crisp_chatbox_website", NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("Crisp")
                .build()
        )

        assertFalse(CrispNotificationDisplay.isChannelEnabled(context, "website"))
        assertTrue(CrispNotificationDisplay.isChannelEnabled(context, "other_website"))
    }

    @Test fun `a notification is removed once cancelled`() {
        show()

        CrispNotificationDisplay.cancel(context, "website")

        assertNull(shown)
    }

    // endregion

    // region Tap

    @Test fun `by default, the chatbox opens above the current screen of the app`() {
        show()

        val intents = intents(shown!!)
        assertEquals(1, intents.size)
        val chatbox = intents.single()
        assertEquals(CrispNotificationActivity::class.java.name, chatbox.component?.className)
        assertTrue(chatbox.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // Not a TaskStackBuilder, which would clear the task of the app
        assertEquals(0, chatbox.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals("website" to "session_a", CrispNotificationPayload.fromIntent(chatbox))
    }

    @Test fun `with a back stack, the chatbox opens above it, without changing it`() {
        val backStack = TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, Activity::class.java))
        show(backStack)
        show(backStack)

        assertEquals(1, backStack.intentCount)
        val intents = intents(shown!!)
        assertEquals(listOf(Activity::class.java.name, CrispNotificationActivity::class.java.name), intents.map { it.component?.className })
        assertEquals("website" to "session_a", CrispNotificationPayload.fromIntent(intents.last()))
    }

    @Test fun `without opening the chatbox, the launcher gets the extras of the notification`() {
        show(opensChatbox = false)

        val intents = intents(shown!!)
        assertEquals(listOf(launcher.className), intents.map { it.component?.className })
        assertEquals("website" to "session_a", CrispNotificationPayload.fromIntent(intents.single()))
    }

    @Test fun `without opening the chatbox, the last Intent of the back stack gets the extras`() {
        val backStack = TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, Activity::class.java))
        show(backStack, opensChatbox = false)

        assertEquals("website" to "session_a", CrispNotificationPayload.fromIntent(intents(shown!!).last()))
        // Not added to the Intent of the app
        assertNull(CrispNotificationPayload.fromIntent(backStack.editIntentAt(0)!!))
    }

    @Test fun `not tappable without launcher nor back stack, as the chatbox never opens alone`() {
        shadowOf(context.packageManager).removeActivity(launcher)

        assertTrue(show())

        assertNull(shown!!.contentIntent)
    }

    // endregion
}
