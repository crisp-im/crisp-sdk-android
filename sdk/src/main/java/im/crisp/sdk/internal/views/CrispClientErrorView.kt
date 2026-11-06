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
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_NO
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import im.crisp.sdk.R
import im.crisp.sdk.internal.webview.CrispClientException
import im.crisp.sdk.internal.webview.CrispWebViewSupport
import im.crisp.sdk.internal.webview.CrispWebViewVersion

internal class CrispClientErrorView @JvmOverloads constructor(
    hostContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CrispNativeView(hostContext, attrs, defStyleAttr) {
    var onCancelClickListener: (() -> Unit)? = null
    var onRetryClickListener: (() -> Unit)? = null

    val titleText = MaterialTextView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Crisp_SDK_Error_Title)
        text = context.getString(R.string.crisp_sdk_alert_cannot_start)
    }

    val messageText = MaterialTextView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin =
                resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_error_message_margin_top)
        }
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Crisp_SDK_Error_Message)
        text = context.getString(R.string.crisp_sdk_alert_cannot_connect)
    }

    val textsContainer = LinearLayout(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        orientation = VERTICAL

        addView(titleText)
        addView(messageText)
    }

    val cancelButton = MaterialButton(
        ContextThemeWrapper(
            context,
            R.style.ThemeOverlay_Crisp_SDK_Button_Text
        )
    ).apply {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )

        text = context.getString(R.string.crisp_sdk_action_cancel)
        isVisible = false
        setOnClickListener { onCancelClickListener?.invoke() }
    }

    val positiveButton = MaterialButton(
        ContextThemeWrapper(
            context,
            R.style.ThemeOverlay_Crisp_SDK_Button_Tonal
        ),
        null,
        com.google.android.material.R.attr.materialButtonTonalStyle
    ).apply {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
            .apply {
                marginStart =
                    resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_error_button_margin_start)
            }
    }

    val buttonsContainer = LinearLayout(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin =
                resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_error_buttons_margin_top)
            setHorizontalGravity(Gravity.END)
        }

        addView(cancelButton)
        addView(positiveButton)
    }

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )

        setBackgroundResource(R.color.crisp_sdk_chatbox_background)
        setPadding(getPadding())
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL

        addView(textsContainer)
        addView(buttonsContainer)
    }

    override fun getPadding(): Int {
        return resources.getDimensionPixelSize(R.dimen.crisp_sdk_chatbox_error_padding)
    }

    /**
     * Shows [error]'s message and main action: an unsupported WebView is fixed by updating it
     * (checked again when the screen comes back), anything else by trying again.
     */
    fun setError(error: Throwable) {
        val webViewMessage = when (error) {
            is CrispClientException.WebViewTooOld -> context.getString(
                R.string.crisp_sdk_alert_android_webview_too_old,
                error.installed,
                error.required,
            )

            is CrispClientException.WebViewUnavailable -> context.getString(R.string.crisp_sdk_alert_android_webview_unavailable)
            else -> null
        }

        if (webViewMessage != null) {
            messageText.text = webViewMessage
            positiveButton.text = context.getString(R.string.crisp_sdk_alert_android_webview_update)
            positiveButton.setOnClickListener { CrispWebViewSupport.openWebViewUpdate(context) }
            // Without a Play Store, the message is shown alone
            positiveButton.isVisible = CrispWebViewSupport.canOpenWebViewUpdate(context)
        } else {
            messageText.text =
                context.getString(R.string.crisp_sdk_alert_cannot_connect)
            positiveButton.text =
                context.getString(R.string.crisp_sdk_action_try_again)
            positiveButton.setOnClickListener { onRetryClickListener?.invoke() }
            positiveButton.isVisible = true
        }
    }

    fun setCancelable(cancelable: Boolean) {
        cancelButton.isVisible = cancelable
    }
}

@Preview(widthDp = 360, heightDp = 640, uiMode = UI_MODE_NIGHT_NO)
@Preview(widthDp = 360, heightDp = 640, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun CrispClientErrorViewPreview() {
    AndroidView(
        factory = { context ->
            CrispClientErrorView(context).apply {
                setCancelable(true)
                //setError(CrispClientException.LoadingTimeout())
                //setError(CrispClientException.WebViewUnavailable())
                setError(
                    CrispClientException.WebViewTooOld(
                        "66",
                        CrispWebViewVersion.MIN_WEBVIEW_MAJOR
                    )
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.crisp_sdk_chatbox_background))
    )
}
