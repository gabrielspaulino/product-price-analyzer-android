package com.promo.tracker.activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.textfield.TextInputEditText;
import com.promo.tracker.BuildConfig;
import com.promo.tracker.R;
import com.promo.tracker.utils.LogoLoader;
import com.promo.tracker.models.User;
import com.promo.tracker.services.SupabaseService;
import com.promo.tracker.utils.PushTokenManager;
import com.promo.tracker.utils.SessionManager;

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

    private GoogleSignInClient googleSignInClient;
    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                try {
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    String idToken = account.getIdToken();
                    if (idToken != null) {
                        signInWithGoogle(idToken);
                    } else {
                        showError("Não foi possível obter o token do Google");
                        setLoading(false);
                    }
                } catch (ApiException e) {
                    Log.e("AuthActivity", "Google sign-in failed: " + e.getStatusCode(), e);
                    setLoading(false);
                    if (e.getStatusCode() != 12501) {
                        showError("Erro Google (código " + e.getStatusCode() + "): " + e.getMessage());
                    }
                }
            });

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

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        Button btnGoogle = findViewById(R.id.btn_google);
        btnGoogle.setOnClickListener(v -> {
            setLoading(true);
            tvError.setVisibility(View.GONE);
            googleSignInClient.signOut().addOnCompleteListener(t ->
                    googleSignInLauncher.launch(googleSignInClient.getSignInIntent()));
        });

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
                PushTokenManager.syncToken(this, user);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setLoading(false); showError(e.getMessage()); });
            }
        });
    }

    private void signInWithGoogle(String idToken) {
        executor.execute(() -> {
            try {
                User user = SupabaseService.getInstance().signInWithIdToken(idToken);
                SessionManager.getInstance(this).saveUser(user);
                PushTokenManager.syncToken(this, user);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception e) {
                Log.e("AuthActivity", "Google sign-in Supabase error", e);
                runOnUiThread(() -> { setLoading(false); showError("Google SSO: " + e.getMessage()); });
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
