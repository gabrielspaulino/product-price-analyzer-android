package com.owly.pricetracker.utils;

import android.content.Context;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessaging;
import com.owly.pricetracker.BuildConfig;
import com.owly.pricetracker.models.User;
import com.owly.pricetracker.services.SupabaseService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PushTokenManager {
    private static final String TAG = "PushTokenManager";

    private PushTokenManager() {
    }

    public static void syncToken(Context context, User user) {
        if (user == null || !BuildConfig.FIREBASE_ENABLED) return;
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> registerToken(context, user, token))
                .addOnFailureListener(error -> Log.e(TAG, "Failed to get FCM token", error));
    }

    public static void registerToken(Context context, User user, String token) {
        if (user == null || token == null || token.isEmpty()) return;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                SupabaseService.getInstance().registerDeviceToken(user.getAccessToken(), token);
            } catch (Exception e) {
                Log.e(TAG, "Failed to register device token", e);
            } finally {
                executor.shutdown();
            }
        });
    }

    public static void unregisterCurrentToken(User user) {
        if (user == null || !BuildConfig.FIREBASE_ENABLED) return;
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        try {
                            SupabaseService.getInstance().deleteDeviceToken(user.getAccessToken(), token);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to unregister device token", e);
                        } finally {
                            executor.shutdown();
                        }
                    });
                })
                .addOnFailureListener(error -> Log.e(TAG, "Failed to get FCM token for unregister", error));
    }
}
