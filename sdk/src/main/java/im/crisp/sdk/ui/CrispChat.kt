package im.crisp.sdk.ui

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

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

    fun show(
        activity: FragmentActivity,
        websiteId: String,
        tokenId: String? = null
    ): CrispBottomSheetDialogFragment = show(activity.supportFragmentManager, websiteId, tokenId)
}
