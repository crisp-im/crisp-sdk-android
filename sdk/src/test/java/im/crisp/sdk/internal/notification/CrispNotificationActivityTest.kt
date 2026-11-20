package im.crisp.sdk.internal.notification

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class CrispNotificationActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val launcher = ComponentName(context, "im.crisp.test.LauncherActivity")

    // As tapped on a notification opening the chatbox by default, see CrispNotificationDisplay
    private val intent = CrispNotificationPayload.addExtras(
        Intent(context, CrispNotificationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        "website",
        "session_a",
    )

    @Before fun setUp() {
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(launcher)
        packageManager.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        // The process-wide store, so set up in each test
        CrispNotificationStore.get(context).update("website") {
            CrispNotificationRecord(
                enabled = true,
                session = CrispNotificationSession("session_a", "user_a", notificationId = "fcm"),
            )
        }
    }

    private fun create(isTaskRoot: Boolean): CrispNotificationActivity {
        val controller = Robolectric.buildActivity(CrispNotificationActivity::class.java, intent)
        shadowOf(controller.get()).setIsTaskRoot(isTaskRoot)
        return controller.create().get()
    }

    @Test fun `alone in its task, it opens the launcher, then itself above it, never shown alone`() {
        val activity = create(isTaskRoot = true)

        val started = shadowOf(activity).nextStartedActivity
        val intents = listOfNotNull(started) + generateSequence { shadowOf(activity).nextStartedActivity }
        assertEquals(
            setOf(launcher.className, CrispNotificationActivity::class.java.name),
            intents.map { it.component?.className }.toSet(),
        )
        val reopened = intents.single { it.component?.className == CrispNotificationActivity::class.java.name }
        assertEquals("website" to "session_a", CrispNotificationPayload.fromIntent(reopened))
        // In this task, without bringing back this instance, finishing
        assertTrue(intents.all { it.flags == 0 })
        assertTrue(activity.isFinishing)
    }

    @Test fun `alone in its task without launcher, it closes`() {
        shadowOf(context.packageManager).removeActivity(launcher)

        val activity = create(isTaskRoot = true)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test fun `above the app, it does not open the launcher`() {
        val activity = create(isTaskRoot = false)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test fun `above the app, it closes if the session cannot be opened anymore`() {
        CrispNotificationStore.get(context).update("website") { CrispNotificationPolicy.revoke(it, "user_a") }

        val activity = create(isTaskRoot = false)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
