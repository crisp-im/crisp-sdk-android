package im.crisp.sdk.ui

import android.app.Dialog
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.window.layout.WindowMetricsCalculator
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import im.crisp.sdk.R
import im.crisp.sdk.api.CrispChatboxController
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.api.Presentation
import im.crisp.sdk.internal.chatbox.CrispChatboxEvent
import im.crisp.sdk.internal.environment.applyCrispSystemBarsConfiguration
import im.crisp.sdk.internal.views.CrispSheetDrag
import im.crisp.sdk.internal.views.CrispSheetDragHost
import kotlin.math.roundToInt

class CrispBottomSheetDialogFragment : BottomSheetDialogFragment() {
    private var chatboxView: CrispChatboxView? = null

    /**
     * Drives the Web client of this chatbox, kept when the view is recreated.
     *
     * Set your own to keep it when this fragment is recreated too,
     * e.g. from a [FragmentOnAttachListener][androidx.fragment.app.FragmentOnAttachListener].
     *
     * The default one is closed when replaced or when this fragment is destroyed,
     * a replacing one is left to its owner.
     */
    var controller: CrispChatboxController = CrispChatboxController()
        set(value) {
            if (field !== value) {
                if (ownsController) {
                    field.close()
                    ownsController = false
                }
                field = value
                chatboxView?.controller = value
            }
        }
    private var ownsController = true

    // Swiped down out of the screen, then canceled
    private var isSwipingOut = false

    // region Lifecycle

    // Set as style rather than overriding `getTheme`, which would document an overridden `theme` property
    init {
        setStyle(STYLE_NORMAL, R.style.Theme_Crisp_SDK_BottomSheetDialog)
    }

    // Not canceled again (back press, tap outside) while swiped out
    /** @suppress */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        object : BottomSheetDialog(requireContext(), theme) {
            override fun cancel() {
                if (!isSwipingOut) {
                    super.cancel()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): CrispChatboxView {
        chatboxView = CrispChatboxView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            controller = this@CrispBottomSheetDialogFragment.controller
            onEventListener = { event ->
                when (event) {
                    is CrispChatboxEvent.Close -> if (!isSwipingOut) dismiss()
                    else -> {}
                }
            }

            val websiteId = arguments?.getString(ARGUMENT_WEBSITE_ID)
            val tokenId = arguments?.getString(ARGUMENT_TOKEN_ID)
            if (websiteId != null) {
                load(
                    CrispConfig(
                        websiteId = websiteId,
                        tokenId = tokenId,
                        presentation = Presentation.sheet
                    )
                )
            }

            enableEdgeToEdge(EdgeToEdge.BOTTOM)
        }
        return chatboxView!!;
    }

    /** @suppress */
    override fun onStart() {
        super.onStart()

        val dialog = (dialog as BottomSheetDialog)
        dialog.applyCrispSystemBarsConfiguration()

        val bottomSheet =
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return
        clipToTopCorners(bottomSheet)

        val bottomsheetBehavior = dialog.behavior
        bottomsheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomsheetBehavior.skipCollapsed = true
        // Swiped down by the chatbox instead, which knows when the page cannot scroll up anymore
        bottomsheetBehavior.isDraggable = false
        bottomsheetBehavior.isFitToContents = false
        bottomsheetBehavior.isHideable = true

        // Once per view, as the fragment can be started again
        if (chatboxView?.sheetDragHost == null) {
            chatboxView?.sheetDragHost = createSheetDragHost(dialog, bottomSheet)
        }

        applyWindowInsets(dialog, bottomSheet)
    }

    // The WebView is destroyed by the chatbox view itself, with the view lifecycle
    /** @suppress */
    override fun onDestroyView() {
        chatboxView = null
        super.onDestroyView()
    }

    /** @suppress */
    override fun onDestroy() {
        if (ownsController) {
            controller.close()
        }
        super.onDestroy()
    }

    // endregion

    override fun getView(): CrispChatboxView? {
        return chatboxView
    }

    /**
     * Clips the content of [bottomSheet] (e.g. the WebView, loading and error views) to its rounded top corners.
     *
     * Before Android 13, only rectangles and round rectangles can clip, not the outline of its background,
     * rounded at the top only: a round rectangle extended past its bottom is used instead.
     */
    private fun clipToTopCorners(bottomSheet: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val radius = resources.getDimension(R.dimen.crisp_sdk_chatbox_radius_top)
            bottomSheet.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
                }
            }
        }
        bottomSheet.clipToOutline = true
    }

    // Sizes the sheet below the status bar, and beside the navigation bar when it is on a side
    private fun applyWindowInsets(dialog: BottomSheetDialog, bottomSheet: View) {
        val window = dialog.window ?: return
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val screenHeight = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(requireActivity())
                .bounds.height()
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            // Beside the navigation bar when it is on a side, rather than padding the chatbox under it
            val sideInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val paddingTop =
                resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_modal_padding_top)
            val offset = topInset + paddingTop

            bottomSheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                height = screenHeight - offset
                leftMargin = sideInsets.left
                rightMargin = sideInsets.right
            }

            dialog.behavior.expandedOffset = offset

            insets.inset(sideInsets.left, 0, sideInsets.right, 0)
        }
        ViewCompat.requestApplyInsets(bottomSheet)
    }

    // Swiped as the bottom sheet behavior would if it was draggable, from its ViewDragHelper
    private fun createSheetDragHost(dialog: BottomSheetDialog, bottomSheet: View) =
        object : CrispSheetDragHost {
            private val viewConfiguration = ViewConfiguration.get(bottomSheet.context)

            // Not scaled by the animator duration scale (animations removed for accessibility), as the ViewDragHelper
            private val settleScroller =
                OverScroller(bottomSheet.context, CrispSheetDrag.materialSettleInterpolator)
            private var settleStep: Runnable? = null

            override fun onDragStart(): Float {
                settleStep?.let(bottomSheet::removeCallbacks)
                settleStep = null
                settleScroller.abortAnimation()
                isSwipingOut = false
                return bottomSheet.translationY
            }

            override fun onDrag(offsetY: Float) {
                bottomSheet.translationY = offsetY
            }

            override fun onRelease(
                offsetY: Float,
                velocityX: Float,
                velocityY: Float,
                onSettled: () -> Unit
            ) {
                val xVelocity = CrispSheetDrag.clampMaterialVelocity(
                    velocityX,
                    viewConfiguration.scaledMinimumFlingVelocity,
                    viewConfiguration.scaledMaximumFlingVelocity
                )
                val yVelocity = CrispSheetDrag.clampMaterialVelocity(
                    velocityY,
                    viewConfiguration.scaledMinimumFlingVelocity,
                    viewConfiguration.scaledMaximumFlingVelocity
                )
                val parent = bottomSheet.parent as View
                val isDismissed = CrispSheetDrag.shouldDismissMaterial(
                    sheetTop = bottomSheet.top + offsetY,
                    velocityX = xVelocity.toFloat(),
                    velocityY = yVelocity.toFloat(),
                    parentHeight = parent.height,
                    expandedOffset = dialog.behavior.expandedOffset,
                    velocityThreshold = dialog.behavior.significantVelocityThreshold
                )
                isSwipingOut = isDismissed
                val startOffsetY = offsetY.roundToInt()
                // Hidden once its top reaches the parent bottom
                val targetOffsetY = if (isDismissed) parent.height - bottomSheet.top else 0
                settleScroller.startScroll(
                    0,
                    startOffsetY,
                    0,
                    targetOffsetY - startOffsetY,
                    CrispSheetDrag.materialSettleDuration(
                        dy = targetOffsetY - startOffsetY,
                        velocityX = xVelocity,
                        velocityY = yVelocity,
                        parentWidth = parent.width,
                        dragRange = parent.height
                    )
                )
                // Stepped on each frame, as ViewDragHelper.continueSettling()
                settleStep = object : Runnable {
                    override fun run() {
                        val isMoving = settleScroller.computeScrollOffset()
                        bottomSheet.translationY = settleScroller.currY.toFloat()
                        if (isMoving && settleScroller.currY != settleScroller.finalY) {
                            bottomSheet.postOnAnimation(this)
                            return
                        }
                        settleStep = null
                        if (isDismissed) {
                            isSwipingOut = false
                            // As the dialog once its sheet is hidden
                            dialog.cancel()
                        } else {
                            onSettled()
                        }
                    }
                }.also { bottomSheet.postOnAnimation(it) }
            }
        }

    companion object {
        const val TAG = "im.crisp.sdk.ui.CrispBottomSheetDialogFragment"
        const val ARGUMENT_WEBSITE_ID = "im.crisp.sdk.ui.CrispBottomSheetDialogFragment.website_id"
        const val ARGUMENT_TOKEN_ID = "im.crisp.sdk.ui.CrispBottomSheetDialogFragment.token_id"

        fun newInstance(
            websiteId: String,
            tokenId: String? = null
        ): CrispBottomSheetDialogFragment {
            return CrispBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARGUMENT_WEBSITE_ID, websiteId)
                    putString(ARGUMENT_TOKEN_ID, tokenId)
                }
            }
        }
    }
}
