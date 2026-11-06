package im.crisp.sdk.internal.permission

import android.app.Activity
import android.app.Dialog
import im.crisp.sdk.R
import im.crisp.sdk.internal.environment.crispAlertDialogBuilder

internal object CrispPermissionDialog {
    fun showRationaleDialog(
        activity: Activity,
        intent: CrispPermissionIntent,
        onRequestSystemPermission: () -> Unit,
        onCancel: () -> Unit,
    ): Dialog {
        val message = "${activity.getString(intent.messageRes)} ${activity.getString(R.string.crisp_sdk_alert_permission_instruction_system)}"
        return activity.crispAlertDialogBuilder()
            .setTitle(intent.titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onRequestSystemPermission()
            }
            .setNegativeButton(R.string.crisp_sdk_action_cancel) { _, _ ->
                onCancel()
            }
            .setOnCancelListener {
                onCancel()
            }
            .show()
    }

    fun showSettingsDialog(
        activity: Activity,
        intent: CrispPermissionIntent,
        onOpenSettings: () -> Unit,
        onCancel: () -> Unit,
    ): Dialog {
        val message = "${activity.getString(intent.messageRes)} ${activity.getString(R.string.crisp_sdk_alert_permission_instruction_settings)}"
        return activity.crispAlertDialogBuilder()
            .setTitle(intent.titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.crisp_sdk_alert_permission_settings) { _, _ ->
                onOpenSettings()
            }
            .setNegativeButton(R.string.crisp_sdk_action_cancel) { _, _ ->
                onCancel()
            }
            .setOnCancelListener {
                onCancel()
            }
            .show()
    }
}
