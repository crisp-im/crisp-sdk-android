package im.crisp.sdk.internal.upload

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.crisp.sdk.R
import im.crisp.sdk.internal.environment.applyCrispSystemBarsConfiguration

internal enum class CrispUploadAction {
    LIBRARY,
    PHOTO,
    VIDEO,
    FILES
}

internal class CrispUploadBottomSheet(
    context: Context,
    private val onActionSelected: (CrispUploadAction) -> Unit
) : BottomSheetDialog(context, R.style.Theme_Crisp_SDK_BottomSheetDialog) {

    init {
        val paddingVertical = context.resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_radius_top)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, paddingVertical, 0, paddingVertical)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navigationInsetsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = paddingVertical + navigationInsetsBottom)
            insets
        }

        addItem(
            parent = root,
            titleRes = R.string.crisp_sdk_action_upload_library,
            iconRes = R.drawable.crisp_sdk_action_upload_library,
            option = CrispUploadAction.LIBRARY
        )

        addItem(
            parent = root,
            titleRes = R.string.crisp_sdk_action_upload_photo,
            iconRes = R.drawable.crisp_sdk_action_upload_photo,
            option = CrispUploadAction.PHOTO
        )

        addItem(
            parent = root,
            titleRes = R.string.crisp_sdk_action_upload_video,
            iconRes = R.drawable.crisp_sdk_action_upload_video,
            option = CrispUploadAction.VIDEO
        )

        addItem(
            parent = root,
            titleRes = R.string.crisp_sdk_action_upload_files,
            iconRes = R.drawable.crisp_sdk_action_upload_files,
            option = CrispUploadAction.FILES
        )

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()

        applyCrispSystemBarsConfiguration()
    }

    private fun addItem(
        parent: LinearLayout,
        titleRes: Int,
        iconRes: Int,
        option: CrispUploadAction
    ) {
        val itemHeight = context.resources.getDimensionPixelSize(R.dimen.crisp_sdk_upload_item_height)
        val itemPaddingHorizontal = context.resources.getDimensionPixelSize(R.dimen.crisp_sdk_upload_item_padding_horizontal)
        val iconSize = context.resources.getDimensionPixelSize(R.dimen.crisp_sdk_upload_icon_size)
        val iconMarginEnd = context.resources.getDimensionPixelSize(R.dimen.crisp_sdk_upload_icon_margin_end)

        val itemView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                itemHeight
            )
            val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            val selectableBackground = typedArray.getDrawable(0)
            typedArray.recycle()
            background = selectableBackground
            isClickable = true
            isFocusable = true
            setPadding(itemPaddingHorizontal, 0, itemPaddingHorizontal, 0)
            gravity = Gravity.CENTER_VERTICAL

            setOnClickListener {
                dismiss()
                onActionSelected(option)
            }
        }

        val imageView = AppCompatImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconMarginEnd
            }
            setImageDrawable(ContextCompat.getDrawable(context, iconRes))
            imageTintList = ContextCompat.getColorStateList(context, R.color.crisp_sdk_on_surface)
        }

        val textView = AppCompatTextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setText(titleRes)
            TextViewCompat.setTextAppearance(
                this,
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
            )
            setTextColor(ContextCompat.getColor(context, R.color.crisp_sdk_on_surface))
        }

        itemView.addView(imageView)
        itemView.addView(textView)
        parent.addView(itemView)
    }
}
