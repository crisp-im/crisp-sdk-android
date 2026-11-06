package im.crisp.catalog

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import im.crisp.catalog.controller.showChatboxControllerDialog
import im.crisp.catalog.controller.showSessionControllerDialog
import im.crisp.catalog.events.ToastEventCallbacks
import im.crisp.catalog.settings.ChatboxPreferences
import im.crisp.sdk.ui.CrispChatboxView

class XmlViewActivity: AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_view_xml)

        val chatboxView = findViewById<CrispChatboxView>(R.id.chatbox_view)
        // Overrides the config of the XML attributes, before it is loaded once started.
        // Done on each creation, as the XML attributes are read again when the view is recreated.
        chatboxView.load(ChatboxPreferences.getConfig(this))
        // Reloads the new config in the same WebView, the cheapest way, keeping the controller
        ChatboxPreferences.observeConfig(this, this) { chatboxView.load(it) }
        // The default controller is closed with the view, which unregisters its callbacks
        chatboxView.controller.addEventCallbacks(ToastEventCallbacks(this, "XML View"))
        findViewById<FloatingActionButton>(R.id.chatbox_controller_fab).setOnClickListener {
            showChatboxControllerDialog(chatboxView.controller)
        }
        findViewById<FloatingActionButton>(R.id.session_controller_fab).setOnClickListener {
            showSessionControllerDialog()
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            // Use systemBars() instead of navigationBars() which correctly maps to systemWindowInsets on API < 26
            val systemBottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            if (view.paddingBottom != systemBottomInset) {
                view.updatePadding(bottom = systemBottomInset)
            }
            insets
        }
    }
}
