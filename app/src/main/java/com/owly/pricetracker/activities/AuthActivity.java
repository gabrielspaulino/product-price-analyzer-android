package com.owly.pricetracker.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.owly.pricetracker.R;
import com.owly.pricetracker.utils.LogoLoader;
import com.owly.pricetracker.models.User;
import com.owly.pricetracker.services.SupabaseService;
import com.owly.pricetracker.utils.SessionManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuthActivity extends AppCompatActivity {

    private TextInputEditText etEmail, etPassword, etPasswordConfirm;
    private Button btnPrimary, btnToggle;
    private TextView tvTitle, tvSubtitle, tvToggleLabel, tvError;
    private ProgressBar progressBar;
    private LinearLayout layoutConfirmPassword;

    private boolean isLogin = true;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        android.widget.ImageView ivLogo = findViewById(R.id.iv_logo);
        if (ivLogo != null) LogoLoader.load(this, ivLogo);

        etEmail              = findViewById(R.id.et_email);
        etPassword           = findViewById(R.id.et_password);
        etPasswordConfirm    = findViewById(R.id.et_password_confirm);
        btnPrimary           = findViewById(R.id.btn_primary);
        btnToggle            = findViewById(R.id.btn_toggle);
        tvTitle              = findViewById(R.id.tv_title);
        tvSubtitle           = findViewById(R.id.tv_subtitle);
        tvToggleLabel        = findViewById(R.id.tv_toggle_label);
        tvError              = findViewById(R.id.tv_error);
        progressBar          = findViewById(R.id.progress_bar);
        layoutConfirmPassword = findViewById(R.id.layout_confirm_password);

        btnPrimary.setOnClickListener(v -> submit());
        btnToggle.setOnClickListener(v -> { isLogin = !isLogin; updateUi(); tvError.setVisibility(View.GONE); });
        updateUi();
    }

    private void updateUi() {
        if (isLogin) {
            tvTitle.setText("Entrar na sua conta");
            tvSubtitle.setText("Bem-vindo de volta");
            btnPrimary.setText("Entrar");
            tvToggleLabel.setText("Não tem conta?");
            btnToggle.setText("Cadastre-se");
            layoutConfirmPassword.setVisibility(View.GONE);
        } else {
            tvTitle.setText("Criar nova conta");
            tvSubtitle.setText("Comece a rastrear preços");
            btnPrimary.setText("Criar Conta");
            tvToggleLabel.setText("Já tem conta?");
            btnToggle.setText("Entrar");
            layoutConfirmPassword.setVisibility(View.VISIBLE);
        }
    }

    private void submit() {
        String email = text(etEmail);
        String password = text(etPassword);

        if (email.isEmpty() || password.isEmpty()) { showError("Preencha email e senha"); return; }

        if (!isLogin) {
            String confirm = text(etPasswordConfirm);
            if (!password.equals(confirm)) { showError("As senhas não coincidem"); return; }
            if (password.length() < 6) { showError("Senha deve ter pelo menos 6 caracteres"); return; }
        }

        setLoading(true);
        executor.execute(() -> {
            try {
                User user = isLogin
                        ? SupabaseService.getInstance().signIn(email, password)
                        : SupabaseService.getInstance().signUp(email, password);
                SessionManager.getInstance(this).saveUser(user);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setLoading(false); showError(e.getMessage()); });
            }
        });
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean on) {
        progressBar.setVisibility(on ? View.VISIBLE : View.GONE);
        btnPrimary.setEnabled(!on);
        btnToggle.setEnabled(!on);
    }

    private String text(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
