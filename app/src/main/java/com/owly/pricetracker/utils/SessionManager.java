package com.owly.pricetracker.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.owly.pricetracker.models.User;

public class SessionManager {
    private static final String PREF = "owly_session";
    private static final String KEY_ID            = "user_id";
    private static final String KEY_EMAIL         = "email";
    private static final String KEY_ACCESS_TOKEN  = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_SERPER_KEY    = "serper_key";

    private final SharedPreferences prefs;
    private static SessionManager instance;

    private SessionManager(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized SessionManager getInstance(Context ctx) {
        if (instance == null) instance = new SessionManager(ctx);
        return instance;
    }

    public void saveUser(User user) {
        prefs.edit()
             .putString(KEY_ID, user.getId())
             .putString(KEY_EMAIL, user.getEmail())
             .putString(KEY_ACCESS_TOKEN, user.getAccessToken())
             .putString(KEY_REFRESH_TOKEN, user.getRefreshToken())
             .apply();
    }

    public User getUser() {
        String id = prefs.getString(KEY_ID, null);
        if (id == null) return null;
        return new User(id,
                prefs.getString(KEY_EMAIL, null),
                prefs.getString(KEY_ACCESS_TOKEN, null),
                prefs.getString(KEY_REFRESH_TOKEN, null));
    }

    public void updateTokens(String access, String refresh) {
        prefs.edit()
             .putString(KEY_ACCESS_TOKEN, access)
             .putString(KEY_REFRESH_TOKEN, refresh)
             .apply();
    }

    public void clearSession() {
        prefs.edit()
             .remove(KEY_ID).remove(KEY_EMAIL)
             .remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN)
             .apply();
    }

    public boolean isLoggedIn() {
        return prefs.getString(KEY_ID, null) != null;
    }

    public void saveSerperKey(String key) {
        prefs.edit().putString(KEY_SERPER_KEY, key).apply();
    }

    public String getSerperKey() {
        return prefs.getString(KEY_SERPER_KEY, null);
    }
}
