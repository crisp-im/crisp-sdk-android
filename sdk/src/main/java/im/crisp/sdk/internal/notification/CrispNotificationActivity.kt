package im.crisp.sdk.internal.notification

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import im.crisp.sdk.api.CrispNotifications
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.ui.CrispBottomSheetDialogFragment

/**
 * Shows the chatbox of a notification tapped, for apps opening it automatically (see
 * [CrispNotifications.handleNotification]). It is presented by a [CrispBottomSheetDialogFragment],
 * above the `Activity` it was opened from (translucent, as the `ChatActivity` of the pre-3.x SDK),
 * finishing once the sheet is dismissed. The notifications tapped meanwhile reload the sheet shown.
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
    }
}
