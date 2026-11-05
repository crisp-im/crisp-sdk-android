package im.crisp.sdk.internal.views

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.setPadding
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import im.crisp.sdk.R
import im.crisp.sdk.internal.environment.openExternalUrl

internal class CrispSDKErrorView @JvmOverloads constructor(
    hostContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
): CrispNativeView(hostContext, attrs, defStyleAttr) {
    companion object {
        val setupGuideUrl: String = "https://docs.crisp.chat/guides/chatbox-sdks/android-sdk/"
    }

    var onSetupGuideClickListener: (() -> Unit)? = null

    val titleText = MaterialTextView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Crisp_SDK_Error_Title_Error)
        text = "Missing Website ID!"
    }

    val messageText = MaterialTextView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_error_message_margin_top)
        }
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Crisp_SDK_Error_Message_Error)
        text = "Please read our setup guide"
    }

    val textsContainer = LinearLayout(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        orientation = VERTICAL

        addView(titleText)
        addView(messageText)
    }

    val setupGuideButton = MaterialButton(
        ContextThemeWrapper(
            context,
            R.style.ThemeOverlay_Crisp_SDK_Button_Tonal_Error
        ),
        null,
        com.google.android.material.R.attr.materialButtonTonalStyle
    ).apply {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_error_buttons_margin_top)
            gravity = Gravity.END
        }

        text = "Open Setup Guide"
        setOnClickListener { openExternalUrl(context, setupGuideUrl) }
    }

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )

        setBackgroundResource(R.color.crisp_sdk_red)
        setPadding(getPadding())
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL

        addView(textsContainer)
        addView(setupGuideButton)
    }

    override fun getPadding(): Int {
        return resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_error_padding)
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun CrispSDKErrorViewPreview() {
    AndroidView(
        factory = { context ->
            CrispSDKErrorView(context)
        },
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.crisp_sdk_base_normal))
    )
}
