package com.owly.pricetracker.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.owly.pricetracker.R;
import com.owly.pricetracker.services.SerperApiService;
import com.owly.pricetracker.services.SupabaseService;
import com.owly.pricetracker.utils.SessionManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private TextInputEditText etSerperKey;
    private TextView tvCurrentKey;
    private SessionManager session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        session = SessionManager.getInstance(this);

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        etSerperKey  = findViewById(R.id.et_serper_key);
        tvCurrentKey = findViewById(R.id.tv_current_key);
        Button btnSave   = findViewById(R.id.btn_save_key);
        Button btnLogout = findViewById(R.id.btn_logout);

        btnSave.setOnClickListener(v -> saveKey());
        btnLogout.setOnClickListener(v -> logout());

        loadCurrentKey();
    }

    private void loadCurrentKey() {
        String key = session.getSerperKey();
        if (key != null && !key.isEmpty()) {
            String masked = key.length() > 8
                    ? key.substring(0, 4) + "••••" + key.substring(key.length() - 4) : "••••";
            tvCurrentKey.setText("Chave atual: " + masked);
            tvCurrentKey.setVisibility(View.VISIBLE);
        }
    }

    private void saveKey() {
        String key = etSerperKey.getText() != null
                ? etSerperKey.getText().toString().trim() : "";
        if (key.isEmpty()) { toast("Digite a chave"); return; }

        session.saveSerperKey(key);
        SerperApiService.getInstance().setApiKey(key);

        var user = session.getUser();
        if (user != null) {
            executor.execute(() -> {
                try {
                    SupabaseService.getInstance().saveApiCredentials(
                            user.getAccessToken(), user.getId(), key);
                } catch (Exception ignored) {}
            });
        }
        etSerperKey.setText("");
        toast("✓ Chave salva!");
        loadCurrentKey();
    }

    private void logout() {
        session.clearSession();
        Intent intent = new Intent(this, AuthActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
