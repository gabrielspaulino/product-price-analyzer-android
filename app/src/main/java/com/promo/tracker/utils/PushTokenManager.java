package com.promo.tracker.utils;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.promo.tracker.BuildConfig;
import com.promo.tracker.models.User;
import com.promo.tracker.services.SupabaseService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PushTokenManager {
    private static final String TAG = "PushTokenManager";

    private PushTokenManager() {
    }

    public static void syncToken(Context context, User user) {
        if (user == null || !BuildConfig.FIREBASE_ENABLED) return;

        int playStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        if (playStatus != ConnectionResult.SUCCESS) {
            Log.w(TAG, "Google Play Services not available, status=" + playStatus);
            return;
        }

        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.w(TAG, "Firebase not initialized");
            return;
        }

        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> registerToken(context, user, token))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to get FCM token", e));
    }

    public static void registerToken(Context context, User user, String token) {
        if (user == null || token == null || token.isEmpty()) return;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                SupabaseService.getInstance().registerDeviceToken(user.getAccessToken(), token);
                Log.i(TAG, "Device token registered successfully");
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
                .addOnFailureListener(e -> Log.e(TAG, "Failed to get FCM token for unregister", e));
    }
}
