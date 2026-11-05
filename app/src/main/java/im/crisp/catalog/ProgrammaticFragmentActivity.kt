package im.crisp.catalog

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import im.crisp.catalog.controller.showChatboxControllerDialog
import im.crisp.catalog.controller.showSessionControllerDialog
import im.crisp.catalog.events.ToastEventCallbacks
import im.crisp.catalog.settings.ChatboxPreferences
import im.crisp.sdk.api.CrispChatboxController
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.api.CrispEventCallbacks
import im.crisp.sdk.ui.CrispFragment
import im.crisp.sdk.ui.EdgeToEdge

/**
 * Keeps the [controller][CrispChatboxController] across configuration changes,
 * so that pending calls survive them.
 *
 * Closed with this [ViewModel], as it is passed to its constructor.
 */
class ChatboxViewModel(val controller: CrispChatboxController = CrispChatboxController()) : ViewModel(controller)

class ProgrammaticFragmentActivity : AppCompatActivity() {
    private val viewModel: ChatboxViewModel by viewModels()
    // The controller outlives this Activity, so its callbacks are removed in onDestroy() not to leak it
    private val eventCallbacks: CrispEventCallbacks = ToastEventCallbacks(this, "Programmatic Fragment")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()

        // Registered before super.onCreate(), so that it also sets the controller of restored fragments
        supportFragmentManager.addFragmentOnAttachListener { _, fragment ->
            if (fragment is CrispFragment) {
                fragment.controller = viewModel.controller
            }
        }

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_programmatic)

        if (savedInstanceState == null) {
            showChatbox(ChatboxPreferences.getConfig(this))
        }
        /** Recreates the fragment for the new config, which gets the same controller (see above).
         * Costlier than loading it in the same WebView (see [XmlFragmentActivity]), as it creates a new one,
         * but starts from a clean chatbox and also suits changing other fragment arguments (e.g. edgeToEdge).
         */
        ChatboxPreferences.observeConfig(this, this) { showChatbox(it) }
        findViewById<FloatingActionButton>(R.id.chatbox_controller_fab).setOnClickListener {
            showChatboxControllerDialog(viewModel.controller)
        }
        findViewById<FloatingActionButton>(R.id.session_controller_fab).setOnClickListener {
            showSessionControllerDialog()
        }
        viewModel.controller.addEventCallbacks(eventCallbacks)
    }

    private fun showChatbox(config: CrispConfig) {
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                CrispFragment.newInstance(
                    websiteId = config.websiteId,
                    tokenId = config.tokenId,
                    edgeToEdge = EdgeToEdge.ALL
                ),
                CrispFragment.TAG
            )
            .commit()
    }

    override fun onDestroy() {
        viewModel.controller.removeEventCallbacks(eventCallbacks)
        super.onDestroy()
    }
}
