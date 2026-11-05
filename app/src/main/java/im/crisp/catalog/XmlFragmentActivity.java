package im.crisp.catalog;

import static im.crisp.catalog.controller.ControllerDialogKt.showChatboxControllerDialog;
import static im.crisp.catalog.controller.ControllerDialogKt.showSessionControllerDialog;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import im.crisp.catalog.events.ToastEventCallbacks;
import im.crisp.catalog.settings.ChatboxPreferences;
import im.crisp.sdk.api.CrispConfig;
import im.crisp.sdk.ui.CrispChatboxView;
import im.crisp.sdk.ui.CrispFragment;

public final class XmlFragmentActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_fragment_xml);

        Toolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        CrispFragment fragment = (CrispFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_container_view);
        // Overrides the config of the XML attributes, before the fragment creates its view.
        // Arguments are saved with the fragment, so it is kept when restored.
        setConfig(fragment, ChatboxPreferences.getConfig(this));
        // Reloads the new config in the same WebView, the cheapest way, keeping the controller.
        // The arguments are updated too, for the view recreated from them (e.g. on rotation).
        ChatboxPreferences.observeConfig(this, this, config -> {
            setConfig(fragment, config);
            CrispChatboxView chatboxView = fragment.getView();
            if (chatboxView != null) {
                chatboxView.load(config);
            }
        });
        findViewById(R.id.chatbox_controller_fab).setOnClickListener(
                v -> showChatboxControllerDialog(
                        XmlFragmentActivity.this,
                        fragment.getController()
                )
        );
        findViewById(R.id.session_controller_fab).setOnClickListener(
                v -> showSessionControllerDialog(XmlFragmentActivity.this)
        );

        // Only the needed events are overridden, the others do nothing by default.
        // The default controller is closed with the fragment, which unregisters its callbacks.
        fragment.getController().addEventCallbacks(new ToastEventCallbacks(this, "XML Fragment"));
    }

    private static void setConfig(CrispFragment fragment, CrispConfig config) {
        Bundle arguments = fragment.requireArguments();
        arguments.putString(CrispFragment.ARGUMENT_WEBSITE_ID, config.getWebsiteId());
        arguments.putString(CrispFragment.ARGUMENT_TOKEN_ID, config.getTokenId());
    }
}
