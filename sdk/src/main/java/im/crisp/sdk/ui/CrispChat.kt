package im.crisp.sdk.ui

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * The shortest way to present the chatbox: one call shows it in a modal bottom sheet.
 *
 * Works from any UI toolkit, with a `FragmentActivity` (such as an `AppCompatActivity`) or a `FragmentManager`:
 * ```
 * button.setOnClickListener {
 *     CrispChat.show(activity = this, websiteId = "YOUR_WEBSITE_ID")
 * }
 * ```
 *
 * The returned [CrispBottomSheetDialogFragment] gives access to its controller:
 * ```
 * val sheet = CrispChat.show(activity = this, websiteId = "YOUR_WEBSITE_ID", tokenId = user.id)
 * sheet.controller.addEventCallbacks(CrispEventCallbacks(onMessageSent = { message -> ... }))
 * // Made once the chatbox is ready
 * sheet.controller.bot.runScenario("YOUR_SCENARIO_ID", completion = null)
 * ```
 * This controller does not survive the sheet being recreated, e.g. on rotation. To keep it or its callbacks,
 * see [CrispBottomSheetDialogFragment].
 */
object CrispChat {
    /**
     * Shows the chatbox in a bottom sheet, or returns the one already shown.
     * Its [controller][CrispBottomSheetDialogFragment.controller] drives its Web client.
     */
    fun show(
        fragmentManager: FragmentManager,
        websiteId: String,
        tokenId: String? = null
    ): CrispBottomSheetDialogFragment {
        val shown = fragmentManager.findFragmentByTag(CrispBottomSheetDialogFragment.TAG)
        return shown as? CrispBottomSheetDialogFragment
            ?: CrispBottomSheetDialogFragment.newInstance(websiteId, tokenId).also {
                it.show(fragmentManager, CrispBottomSheetDialogFragment.TAG)
            }
    }

    /**
     * Shows the chatbox in a bottom sheet of [activity], or returns the one already shown.
     * Its [controller][CrispBottomSheetDialogFragment.controller] drives its Web client.
     */
    fun show(
        activity: FragmentActivity,
        websiteId: String,
        tokenId: String? = null
    ): CrispBottomSheetDialogFragment = show(activity.supportFragmentManager, websiteId, tokenId)
}
