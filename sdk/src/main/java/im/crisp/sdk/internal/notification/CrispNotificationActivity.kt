package im.crisp.sdk.internal.notification

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import im.crisp.sdk.api.notification.CrispNotifications
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.ui.CrispBottomSheetDialogFragment

/**
 * Shows the chatbox of a notification tapped, for apps opening it automatically (see
 * [CrispNotifications.handleNotification]). It is presented by a [CrispBottomSheetDialogFragment],
 * above the `Activity` it was opened from (translucent, as the `ChatActivity` of the pre-3.x SDK),
 * finishing once the sheet is dismissed. The notifications tapped meanwhile reload the sheet shown.
 *
 * By default, opened in the task of the app, above its current screen, see [CrispNotificationDisplay].
 * Alone in its task, it opens itself again in the other task of the app if any
 * (its `taskAffinity` is not the package, e.g. set on its launcher `Activity`),
 * or above the launcher `Activity` if the app was not running,
 * so that the sheet is not shown over the home screen and going back opens the app.
 *
 * Not exported, only started by the notifications of the SDK.
 * Shows the [config][CrispNotifications.configFrom] of the session of the notification,
 * or finishes if it cannot be shown anymore (e.g. revoked meanwhile).
 */
internal class CrispNotificationActivity : FragmentActivity() {
    private val sheetCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentDestroyed(fm: FragmentManager, fragment: Fragment) {
            // Dismissed, rather than destroyed with this Activity (e.g. recreated, restoring it)
            if (fragment is CrispBottomSheetDialogFragment && !isChangingConfigurations) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTaskRoot && !intent.getBooleanExtra(EXTRA_ABOVE_APP, false)) {
            reopenAboveApp()
            return
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(sheetCallbacks, false)
        // Restored with this Activity, otherwise
        if (savedInstanceState == null) {
            show(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(sheetCallbacks)
        super.onDestroy()
    }

    /**
     * Opens this `Activity` again above the current screen of the other task of the app, removing this task,
     * or otherwise the launcher `Activity`, then this one again above it, in this task, then finishes before being shown.
     * Without flags: [Intent.FLAG_ACTIVITY_CLEAR_TOP] would bring back this instance, finishing.
     *
     * A `singleInstance` launcher `Activity` being alone in its task, this one is then opened in its own task
     * above it, with [EXTRA_ABOVE_APP], not to be opened again.
     */
    private fun reopenAboveApp() {
        val chatbox = Intent(intent).setFlags(0)
        val appTask = otherAppTask()
        if (appTask != null) {
            appTask.startActivity(this, chatbox, null)
            finishAndRemoveTask()
            return
        }
        val launchIntent = CrispNotificationDisplay.launchIntent(this)
        if (launchIntent == null) {
            log(Level.WARN, LOG_TAG, "The app has no launcher Activity to open the chatbox above, closing")
            finish()
        } else if (isSingleInstance(launchIntent)) {
            // Alone in its task, the chatbox then started in this one, getting to this instance finishing (singleTop):
            // opened in its own task above it instead, removing this one, left empty
            startActivities(
                arrayOf(
                    Intent(launchIntent).setFlags(0),
                    chatbox.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        .putExtra(EXTRA_ABOVE_APP, true),
                ),
            )
            finishAndRemoveTask()
        } else {
            startActivities(arrayOf(Intent(launchIntent).setFlags(0), chatbox))
            finish()
        }
    }

    private fun isSingleInstance(launchIntent: Intent): Boolean =
        packageManager.resolveActivity(launchIntent, 0)?.activityInfo?.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE

    /**
     * The last task of the app other than this one,
     * with Activities (not restored after a reboot, without any),
     * e.g. started by a launcher `Activity` whose `taskAffinity` is not the package, which this one has.
     */
    private fun otherAppTask(): ActivityManager.AppTask? =
        getSystemService(ActivityManager::class.java).appTasks.firstOrNull { task ->
            // Removed meanwhile
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@firstOrNull false
            info.persistentTaskId != taskId && info.numActivities > 0
        }

    /** The identifier of the task, as [getTaskId], whether running or not. */
    private val ActivityManager.RecentTaskInfo.persistentTaskId: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) taskId else @Suppress("DEPRECATION") persistentId

    /** Shows the sheet of the notification of [intent], or loads it in the sheet shown, if any. */
    private fun show(intent: Intent) {
        val config = CrispNotifications.configFrom(this, intent)
        if (config == null) {
            log(Level.WARN, LOG_TAG, "Cannot open the chatbox of the notification anymore, closing")
            finish()
            return
        }
        val shown = supportFragmentManager.findFragmentByTag(CrispBottomSheetDialogFragment.TAG)
            as? CrispBottomSheetDialogFragment
        if (shown == null) {
            CrispBottomSheetDialogFragment.newInstance(config.websiteId, config.tokenId)
                .show(supportFragmentManager, CrispBottomSheetDialogFragment.TAG)
        } else if (shown.config?.session != config.session) {
            // Not for the same session, e.g. a notification tapped again, not to reload it
            shown.load(config.websiteId, config.tokenId)
        }
    }

    private companion object {
        private const val LOG_TAG = "CrispNotifications"

        /** Opened above the app in its own task, see [reopenAboveApp]. */
        private const val EXTRA_ABOVE_APP = "im.crisp.sdk.notification.above_app"
    }
}
