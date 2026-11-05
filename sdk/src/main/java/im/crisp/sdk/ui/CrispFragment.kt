package im.crisp.sdk.ui

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import im.crisp.sdk.R
import im.crisp.sdk.api.CrispChatboxController
import im.crisp.sdk.api.CrispConfig

class CrispFragment : Fragment() {
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

    private var chatboxView: CrispChatboxView? = null

    /** @suppress */
    override fun onInflate(context: Context, attrs: AttributeSet, savedInstanceState: Bundle?) {
        super.onInflate(context, attrs, savedInstanceState)

        val typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.CrispFragment
        )
        try {
            val websiteId = typedArray.getString(
                R.styleable.CrispFragment_crisp_sdk_website_id
            )
            val tokenId = typedArray.getString(
                R.styleable.CrispFragment_crisp_sdk_token_id
            )
            val edgeToEdgeFlags = typedArray.getInt(
                R.styleable.CrispFragment_crisp_sdk_edge_to_edge,
                EdgeToEdge.NONE_ATTR_VALUE
            )

            val args = arguments ?: Bundle().also { arguments = it }
            if (websiteId != null && !args.containsKey(ARGUMENT_WEBSITE_ID)) {
                args.putString(ARGUMENT_WEBSITE_ID, websiteId)
            }
            if (tokenId != null && !args.containsKey(ARGUMENT_TOKEN_ID)) {
                args.putString(ARGUMENT_TOKEN_ID, tokenId)
            }
            if (!args.containsKey(ARGUMENT_EDGE_TO_EDGE)) {
                args.putInt(ARGUMENT_EDGE_TO_EDGE, edgeToEdgeFlags)
            }
        } finally {
            typedArray.recycle()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): CrispChatboxView? {
        chatboxView = CrispChatboxView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            controller = this@CrispFragment.controller

            val edgeToEdgeFlags = arguments?.getInt(
                ARGUMENT_EDGE_TO_EDGE,
                EdgeToEdge.NONE_ATTR_VALUE
            ) ?: EdgeToEdge.NONE_ATTR_VALUE
            enableEdgeToEdge(EdgeToEdge.fromFlags(edgeToEdgeFlags))

            val websiteId = arguments?.getString(ARGUMENT_WEBSITE_ID)
            val tokenId = arguments?.getString(ARGUMENT_TOKEN_ID)
            if (websiteId != null) {
                load(CrispConfig(websiteId = websiteId, tokenId = tokenId))
            }
        }
        return chatboxView
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

    override fun getView(): CrispChatboxView? {
        return chatboxView
    }

    companion object {
        const val TAG = "im.crisp.sdk.ui.CrispFragment"
        const val ARGUMENT_WEBSITE_ID = "im.crisp.sdk.ui.CrispFragment.website_id"
        const val ARGUMENT_TOKEN_ID = "im.crisp.sdk.ui.CrispFragment.token_id"
        const val ARGUMENT_EDGE_TO_EDGE = "im.crisp.sdk.ui.CrispFragment.edge_to_edge"

        fun newInstance(websiteId: String, tokenId: String? = null,
                        edgeToEdge: EdgeToEdge = EdgeToEdge.NONE): CrispFragment {
            return CrispFragment().apply {
                arguments = Bundle().apply {
                    putString(ARGUMENT_WEBSITE_ID, websiteId)
                    putString(ARGUMENT_TOKEN_ID, tokenId)
                    putInt(ARGUMENT_EDGE_TO_EDGE, edgeToEdge.toFlags())
                }
            }
        }
    }
}
