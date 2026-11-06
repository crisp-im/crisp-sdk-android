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

/**
 * The chatbox as a `Fragment`, embedded in your layout.
 *
 * In XML, with the `app:crisp_sdk_website_id`, `app:crisp_sdk_token_id` and `app:crisp_sdk_edge_to_edge` attributes:
 * ```xml
 * <androidx.fragment.app.FragmentContainerView
 *     android:id="@+id/chatbox_fragment"
 *     android:name="im.crisp.sdk.ui.CrispFragment"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     app:crisp_sdk_website_id="@string/crisp_website_id"
 *     app:crisp_sdk_edge_to_edge="all" />
 * ```
 *
 * In code, with the arguments of [newInstance]. Only add it once, as Android restores it afterward:
 * ```
 * if (savedInstanceState == null) {
 *     supportFragmentManager.beginTransaction()
 *         .replace(
 *             R.id.fragment_container,
 *             CrispFragment.newInstance(
 *                 websiteId = "YOUR_WEBSITE_ID",
 *                 tokenId = user.id,
 *                 edgeToEdge = EdgeToEdge.ALL
 *             ),
 *             CrispFragment.TAG
 *         )
 *         .commit()
 * }
 * ```
 *
 * The [controller] drives its chatbox. The default one lives as long as this fragment,
 * so register its callbacks on each creation of your `Activity`:
 * ```
 * val fragment = supportFragmentManager.findFragmentById(R.id.chatbox_fragment) as CrispFragment
 * fragment.controller.addEventCallbacks(CrispEventCallbacks(onChatOpened = { ... }))
 * ```
 * To keep the pending calls too when the fragment is recreated, e.g. on rotation, set your own from
 * a `ViewModel`, as [CrispBottomSheetDialogFragment] details.
 *
 * The test app has samples [in XML](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/XmlFragmentActivity.java)
 * and [in code, with a `ViewModel`](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/ProgrammaticFragmentActivity.kt).
 *
 * @see EdgeToEdge
 * @see im.crisp.sdk.api.CrispChatboxController
 */
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
