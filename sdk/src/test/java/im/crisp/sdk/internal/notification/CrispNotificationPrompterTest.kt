package im.crisp.sdk.internal.notification

import android.Manifest
import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.PickerClickEvent
import im.crisp.sdk.internal.bridge.types.PickerId
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.FakeSharedPreferences
import im.crisp.sdk.internal.permission.CrispPermissionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class CrispNotificationPrompterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val chatboxStore = CrispChatboxStore()
    private val store = CrispNotificationStore(FakeSharedPreferences())
    private val prompter = CrispNotificationPrompter(context, chatboxStore, CrispPermissionManager(context), store)

    private val config = CrispConfig("website", "user_a")
    private val registered = CrispNotificationSession("session_a", "user_a", notificationId = "fcm_1")
    private var granted = 0

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        chatboxStore.onSessionLoaded("session_a")
    }

    private fun answer(choice: String) = prompter.onPickerClick(
        config,
        PickerClickEvent(CrispNotificationPrompter.PROMPT_FINGERPRINT, PickerId("notifications"), choice),
    ) { granted++ }

    private fun turnChannelOff() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder("crisp_chatbox_website", NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("Crisp")
                .build()
        )
    }

    @Test fun `a choice in the prompt is handled right away, even while disconnected`() = runTest {
        answer("skip")

        assertTrue(store.record("website").session?.promptAnswered == true)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun `enabling with the permission granted opens the notification settings, for the channel turned off`() =
        runTest {
            turnChannelOff()

            answer("enable")

            val settings = shadowOf(context).nextStartedActivity
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
            assertEquals(context.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            // Registered once resumed, if turned back on
            assertEquals(0, granted)
            assertTrue(store.record("website").session?.promptAnswered == true)
        }

    @Test @Config(sdk = [Build.VERSION_CODES.S])
    fun `enabling before Android 13 opens the notification settings, for the app or channel turned off`() = runTest {
        // No permission to ask for
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        answer("enable")

        val settings = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(context.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(0, granted)
    }

    @Test @Config(sdk = [Build.VERSION_CODES.N])
    fun `enabling on Android 7 opens the details of the app, without notification settings screen`() = runTest {
        answer("enable")

        val settings = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${context.packageName}", settings.dataString)
        assertEquals(0, granted)
    }

    @Test fun `a choice in a page being replaced is ignored, not to answer the session of the next page`() = runTest {
        // E.g. unregistering before loading another tokenId, the page still shown
        chatboxStore.onPageStarted()

        answer("enable")
        chatboxStore.onSessionLoaded("session_b")

        assertEquals(registered, store.record("website").session)
        assertEquals(0, granted)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun `skipping never opens the settings`() = runTest {
        turnChannelOff()

        answer("skip")

        assertTrue(store.record("website").session?.promptAnswered == true)
        assertEquals(0, granted)
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
