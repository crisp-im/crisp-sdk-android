package im.crisp.sdk.internal.views

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_NO
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import im.crisp.sdk.R

internal class CrispLoadingView @JvmOverloads constructor(
    hostContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
): CrispNativeView(hostContext, attrs, defStyleAttr) {
    var onCancelClickListener: (() -> Unit)? = null

    val loadingIndicator = CrispLoadingIndicator(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    val loadingContainer = FrameLayout(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            0
        ).apply {
            weight = 1f
        }

        addView(loadingIndicator)
    }

    val cancelButton = MaterialButton(
        ContextThemeWrapper(
            context,
            R.style.ThemeOverlay_Crisp_SDK_Button_Outlined
        ),
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        .apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        text = context.getString(R.string.crisp_sdk_action_cancel)
        isVisible = false
        setOnClickListener { onCancelClickListener?.invoke() }
    }

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )

        setBackgroundResource(R.color.crisp_sdk_chatbox_background)
        setPadding(getPadding())

        orientation = VERTICAL

        addView(loadingContainer)
        addView(cancelButton)
    }

    override fun getPadding(): Int {
        return resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_loading_padding)
    }

    fun setCancelable(cancelable: Boolean) {
        cancelButton.isVisible = cancelable
    }
}

@Preview(widthDp = 360, heightDp = 640, uiMode = UI_MODE_NIGHT_NO)
@Preview(widthDp = 360, heightDp = 640, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun CrispLoadingViewPreview() {
    AndroidView(
        factory = { context ->
            CrispLoadingView(context).apply {
                setCancelable(true)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.crisp_sdk_chatbox_background))
    )
}
