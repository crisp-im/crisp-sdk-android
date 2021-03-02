package im.crisp.sample;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import im.crisp.client.ChatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        findViewById(R.id.crisp_button).setOnClickListener(v -> {
            Intent crispIntent = new Intent(MainActivity.this, ChatActivity.class);
            startActivity(crispIntent);
        });
    }
}
