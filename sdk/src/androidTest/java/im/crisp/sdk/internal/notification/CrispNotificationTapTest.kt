package im.crisp.sdk.internal.notification

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Taps the notifications in the shade, checking where the chatbox opens and where going back leads,
 * which Android decides from the tasks of the app (not simulated by Robolectric).
 *
 * The app is this test APK, whose launcher `Activity` is one of [LAUNCHERS], see the manifest of the tests.
 * Its process keeps running (it runs the tests): a killed process, its task kept in the recents, is to try by hand.
 *
 * Named in camelCase, as names with spaces are not allowed in the DEX files before Android 11.
 * Each checks the tasks of the app once opened, then once going back from the chatbox.
 */
@RunWith(AndroidJUnit4::class)
class CrispNotificationTapTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    /** The tasks of the app, from the top one, as their Activities from the bottom one (only the ends are known). */
    private data class Task(val base: String?, val top: String?, val count: Int)

    @Before fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        closeApp()
        CrispNotificationStore.get(context).update(WEBSITE_ID) {
            CrispNotificationRecord(
                enabled = true,
                session = CrispNotificationSession(SESSION_ID, tokenId = null, notificationId = "fcm"),
            )
        }
    }

    @After fun tearDown() {
        // Also removes the notification, if not tapped
        CrispNotificationStore.get(context).update(WEBSITE_ID) { CrispNotificationRecord() }
        device.pressHome()
        closeApp()
        LAUNCHERS.forEach { setEnabled(it, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAlwaysFinishActivities(false)
    }

    // region Standard launcher

    @Test fun inForeground_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestLauncherActivity::class.java, inBackground = false)

    @Test fun inBackground_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestLauncherActivity::class.java, inBackground = true)

    @Test fun inBackground_withoutKeptActivities_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestLauncherActivity::class.java, inBackground = true, keepActivities = false)

    @Test fun notRunning_opensAboveLauncher() =
        assertOpensAboveLauncher(TestLauncherActivity::class.java)

    // endregion

    // region singleTask launcher

    @Test fun singleTaskLauncher_inForeground_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestSingleTaskLauncherActivity::class.java, inBackground = false)

    @Test fun singleTaskLauncher_inBackground_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestSingleTaskLauncherActivity::class.java, inBackground = true)

    @Test fun singleTaskLauncher_inBackground_withoutKeptActivities_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestSingleTaskLauncherActivity::class.java, inBackground = true, keepActivities = false)

    @Test fun singleTaskLauncher_notRunning_opensAboveLauncher() =
        assertOpensAboveLauncher(TestSingleTaskLauncherActivity::class.java)

    // endregion

    // region singleInstance launcher, its screens being in another task

    @Test fun singleInstanceLauncher_inForeground_opensAboveCurrentScreen() =
        assertSingleInstanceOpensAboveScreen(inBackground = false)

    @Test fun singleInstanceLauncher_inBackground_opensAboveCurrentScreen() =
        assertSingleInstanceOpensAboveScreen(inBackground = true)

    @Test fun singleInstanceLauncher_inBackground_withoutKeptActivities_opensAboveCurrentScreen() =
        assertSingleInstanceOpensAboveScreen(inBackground = true, keepActivities = false)

    private fun assertSingleInstanceOpensAboveScreen(inBackground: Boolean, keepActivities: Boolean = true) {
        val launcher = TestSingleInstanceLauncherActivity::class.java
        useLauncher(launcher)
        openScreen(launcher, inBackground, keepActivities)

        tapNotification()

        // Behind the task of the screen, of the same affinity, the one of the launcher may be removed by Android
        // ("remove-hidden-task"), the recents only keeping a task per affinity
        val alone = Task(launcher.name, launcher.name, 1)
        val screen = TestScreenActivity::class.java.name
        assertTasks(listOf(Task(screen, CrispNotificationActivity::class.java.name, 2)), ignoring = alone)
        device.pressBack()
        waitForResumed(TestScreenActivity::class.java)
        assertTasks(listOf(Task(screen, screen, 1)), ignoring = alone)
    }

    @Test fun singleInstanceLauncher_notRunning_opensAboveLauncher() {
        val launcher = TestSingleInstanceLauncherActivity::class.java
        useLauncher(launcher)
        device.pressHome()

        tapNotification()

        val alone = Task(launcher.name, launcher.name, 1)
        val chatbox = CrispNotificationActivity::class.java.name
        assertTasks(listOf(Task(chatbox, chatbox, 1), alone))
        device.pressBack()
        waitForResumed(launcher)
        assertTasks(listOf(alone))
    }

    // endregion

    // region Launcher with its own taskAffinity

    @Test fun affinityLauncher_inForeground_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestAffinityLauncherActivity::class.java, inBackground = false)

    @Test fun affinityLauncher_inBackground_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestAffinityLauncherActivity::class.java, inBackground = true)

    @Test fun affinityLauncher_inBackground_withoutKeptActivities_opensAboveCurrentScreen() =
        assertOpensAboveScreen(TestAffinityLauncherActivity::class.java, inBackground = true, keepActivities = false)

    @Test fun affinityLauncher_notRunning_opensAboveLauncher() =
        assertOpensAboveLauncher(TestAffinityLauncherActivity::class.java)

    // endregion

    /**
     * Without [keepActivities] ("Don't keep activities" developer option), the screens of the app are destroyed in the
     * background, its tasks kept: recreated once going back to them, as once its process is killed.
     */
    private fun assertOpensAboveScreen(launcher: Class<out Activity>, inBackground: Boolean, keepActivities: Boolean = true) {
        useLauncher(launcher)
        openScreen(launcher, inBackground, keepActivities)

        tapNotification()

        assertTasks(listOf(Task(launcher.name, CrispNotificationActivity::class.java.name, 3)))
        device.pressBack()
        waitForResumed(TestScreenActivity::class.java)
        assertTasks(listOf(Task(launcher.name, TestScreenActivity::class.java.name, 2)))
    }

    private fun assertOpensAboveLauncher(launcher: Class<out Activity>) {
        useLauncher(launcher)
        device.pressHome()

        tapNotification()

        assertTasks(listOf(Task(launcher.name, CrispNotificationActivity::class.java.name, 2)))
        device.pressBack()
        waitForResumed(launcher)
        assertTasks(listOf(Task(launcher.name, launcher.name, 1)))
    }

    // region App

    /** Opens the screen of the app above its [launcher], then goes to the home screen if [inBackground]. */
    private fun openScreen(launcher: Class<out Activity>, inBackground: Boolean, keepActivities: Boolean) {
        if (!keepActivities) {
            assumeTrue("Shell permissions from Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            setAlwaysFinishActivities(true)
        }
        val opened = openApp(launcher)
        opened.startActivity(Intent(opened, TestScreenActivity::class.java))
        waitForResumed(TestScreenActivity::class.java)
        if (inBackground) device.pressHome()
        if (!keepActivities) waitUntil("the screens of the app are destroyed") { aliveActivities().isEmpty() }
    }

    /**
     * Sets the "Don't keep activities" developer option, applied right away, as the developer options do (the setting
     * alone is only read once Android starts): with the `SET_ALWAYS_FINISH` permission of the shell, from Android 10.
     */
    private fun setAlwaysFinishActivities(enabled: Boolean) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        try {
            val service = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            service.javaClass.getMethod("setAlwaysFinish", Boolean::class.javaPrimitiveType).invoke(service, enabled)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    /** Enables [launcher] as the launcher `Activity` of the app, disabling the others. */
    private fun useLauncher(launcher: Class<out Activity>) {
        LAUNCHERS.forEach {
            setEnabled(
                it,
                if (it == launcher) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            )
        }
        assertEquals(launcher.name, CrispNotificationDisplay.launchIntent(context)?.component?.className)
    }

    private fun setEnabled(activity: Class<out Activity>, state: Int) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, activity),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Opens the app as from the home screen. */
    private fun openApp(launcher: Class<out Activity>): Activity {
        val intent = CrispNotificationDisplay.launchIntent(context)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return waitForResumed(launcher)
    }

    /** Closes all the tasks of the app, as swiped away from the recents. */
    private fun closeApp() {
        activityManager.appTasks.forEach { it.finishAndRemoveTask() }
        waitUntil("the app is closed") { tasks().isEmpty() && resumedActivities().isEmpty() }
    }

    // endregion

    // region Notification

    /** Shows a notification, then taps it in the shade (or its heads-up), waiting for the chatbox to open. */
    private fun tapNotification() {
        val title = "Crisp test ${SystemClock.uptimeMillis()}"
        assertTrue(CrispNotificationDisplay.show(context, CrispNotificationPayload(WEBSITE_ID, SESSION_ID, title, "Hello"), null, true))
        device.openNotification()
        val notification = device.wait(Until.findObject(By.text(title)), TIMEOUT_MS)
        assertNotNull("The notification is not in the shade", notification)
        notification.click()
        waitForResumed(CrispNotificationActivity::class.java)
    }

    // endregion

    // region State

    private fun tasks(): List<Task> = activityManager.appTasks.mapNotNull { task ->
        // Removed meanwhile
        val info = runCatching { task.taskInfo }.getOrNull() ?: return@mapNotNull null
        Task(info.baseActivity?.className, info.topActivity?.className, info.numActivities)
            .takeIf { it.count > 0 }
    }

    private fun resumedActivities(): List<Activity> {
        var activities = emptyList<Activity>()
        instrumentation.runOnMainSync {
            activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
        }
        return activities
    }

    /** The Activities of the app not destroyed. */
    private fun aliveActivities(): List<Activity> {
        var activities = emptyList<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            activities = (Stage.entries - Stage.DESTROYED).flatMap { monitor.getActivitiesInStage(it) }
        }
        return activities
    }

    private fun <T : Activity> waitForResumed(activity: Class<T>): T {
        var resumed: T? = null
        waitUntil("${activity.simpleName} is resumed") {
            resumed = resumedActivities().filterIsInstance(activity).firstOrNull()
            resumed != null
        }
        return resumed!!
    }

    /** Asserts the tasks of the app (but [ignoring], if any), in any order, as they are once the Activities finishing are removed. */
    private fun assertTasks(expected: List<Task>, ignoring: Task? = null) {
        val actual = { (tasks() - ignoring).toSet() }
        val reached = runCatching { waitUntil("the tasks are $expected") { actual() == expected.toSet() } }.isSuccess
        if (!reached) assertEquals(expected.toSet(), actual())
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("Timed out waiting until $what")
            SystemClock.sleep(100)
        }
    }

    // endregion

    private companion object {
        const val WEBSITE_ID = "crisp-sdk-tap-test"
        const val SESSION_ID = "session_tap_test"
        const val TIMEOUT_MS = 10_000L
        val LAUNCHERS = listOf(
            TestLauncherActivity::class.java,
            TestSingleTaskLauncherActivity::class.java,
            TestSingleInstanceLauncherActivity::class.java,
            TestAffinityLauncherActivity::class.java,
        )
    }
}
