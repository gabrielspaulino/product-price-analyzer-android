package com.owly.pricetracker.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.owly.pricetracker.utils.LogoLoader;
import com.owly.pricetracker.utils.SessionManager;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.owly.pricetracker.R.layout.activity_splash);
        android.widget.ImageView ivLogo = findViewById(com.owly.pricetracker.R.id.iv_logo);
        if (ivLogo != null) LogoLoader.load(this, ivLogo);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean loggedIn = SessionManager.getInstance(this).isLoggedIn();
            Intent intent = new Intent(this,
                    loggedIn ? MainActivity.class : AuthActivity.class);
            startActivity(intent);
            finish();
        }, 1200);
    }
}
