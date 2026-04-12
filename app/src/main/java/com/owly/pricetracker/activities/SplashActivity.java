package com.owly.pricetracker.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.owly.pricetracker.models.User;
import com.owly.pricetracker.services.SupabaseService;
import com.owly.pricetracker.utils.LogoLoader;
import com.owly.pricetracker.utils.PushTokenManager;
import com.owly.pricetracker.utils.SessionManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SplashActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.owly.pricetracker.R.layout.activity_splash);
        android.widget.ImageView ivLogo = findViewById(com.owly.pricetracker.R.id.iv_logo);
        if (ivLogo != null) LogoLoader.load(this, ivLogo);
        new Handler(Looper.getMainLooper()).postDelayed(this::restoreSessionAndNavigate, 1200);
    }

    private void restoreSessionAndNavigate() {
        SessionManager session = SessionManager.getInstance(this);
        User savedUser = session.getUser();
        if (!session.isLoggedIn() || savedUser == null) {
            goToAuth();
            return;
        }

        String refreshToken = savedUser.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            session.clearSession();
            goToAuth();
            return;
        }

        executor.execute(() -> {
            try {
                User refreshedUser = SupabaseService.getInstance().refreshToken(refreshToken);
                User mergedUser = mergeUser(savedUser, refreshedUser);
                session.saveUser(mergedUser);
                PushTokenManager.syncToken(this, mergedUser);
                runOnUiThread(this::goToMain);
            } catch (Exception e) {
                session.clearSession();
                runOnUiThread(this::goToAuth);
            }
        });
    }

    private User mergeUser(User savedUser, User refreshedUser) {
        if (refreshedUser.getId() == null) {
            refreshedUser.setId(savedUser.getId());
        }
        if (refreshedUser.getEmail() == null) {
            refreshedUser.setEmail(savedUser.getEmail());
        }
        if (refreshedUser.getRefreshToken() == null || refreshedUser.getRefreshToken().isEmpty()) {
            refreshedUser.setRefreshToken(savedUser.getRefreshToken());
        }
        return refreshedUser;
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void goToAuth() {
        startActivity(new Intent(this, AuthActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
