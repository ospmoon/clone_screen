package osp.moon.clonescreen;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.server_button).setOnClickListener(v -> startServer());
        findViewById(R.id.client_button).setOnClickListener(v -> startClient());
    }

    private void startClient() {
        Intent intent = new Intent(this, ClientActivity.class);
        startActivity(intent);
    }

    private void startServer() {
        Intent intent = new Intent(this, ServerActivity.class);
        startActivity(intent);
    }
}
