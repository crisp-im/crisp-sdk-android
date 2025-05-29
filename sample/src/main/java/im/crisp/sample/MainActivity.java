package im.crisp.sample;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import im.crisp.client.external.ChatActivity;
import im.crisp.client.external.Crisp;
import im.crisp.client.external.EventsCallback;
import im.crisp.client.external.data.message.Message;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Crisp Sample";
    private static final EventsCallback CRISP_EVENTS_CALLBACK = new EventsCallback() {
        @Override
        public void onSessionLoaded(@NonNull final String sessionId) {
            Log.i(TAG, "onSessionLoaded: " + sessionId);
        }

        @Override
        public void onChatOpened() {
            Log.i(TAG, "onChatOpened");
        }

        @Override
        public void onChatClosed() {
            Log.i(TAG, "onChatClosed");
        }

        @Override
        public void onMessageSent(@NonNull final Message message) {
            Log.i(TAG, "onMessageSent: " + message.toJSON());
        }

        @Override
        public void onMessageReceived(@NonNull final Message message) {
            Log.i(TAG, "onMessageReceived: " + message.toJSON());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        setSupportActionBar(toolbar);
        findViewById(R.id.crisp_button).setOnClickListener(v -> {
            Intent crispIntent = new Intent(MainActivity.this, ChatActivity.class);
            startActivity(crispIntent);
        });

        Crisp.addCallback(CRISP_EVENTS_CALLBACK);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Crisp.removeCallback(CRISP_EVENTS_CALLBACK);
    }
}
