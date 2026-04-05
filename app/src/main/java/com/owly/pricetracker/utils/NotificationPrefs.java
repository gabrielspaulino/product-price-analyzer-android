package com.owly.pricetracker.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class NotificationPrefs {
    private static final String PREF_NAME = "owly_notifications";
    private static final String KEY_PERMISSION_REQUESTED = "permission_requested";
    private static final String KEY_LAST_SNAPSHOT_PREFIX = "last_snapshot_";

    private final SharedPreferences prefs;

    public NotificationPrefs(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isPermissionRequested() {
        return prefs.getBoolean(KEY_PERMISSION_REQUESTED, false);
    }

    public void markPermissionRequested() {
        prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply();
    }

    public void saveLastSnapshotId(String productId, String snapshotId) {
        if (productId == null || snapshotId == null) return;
        prefs.edit().putString(KEY_LAST_SNAPSHOT_PREFIX + productId, snapshotId).apply();
    }

    public String getLastSnapshotId(String productId) {
        if (productId == null) return null;
        return prefs.getString(KEY_LAST_SNAPSHOT_PREFIX + productId, null);
    }

    public boolean markSnapshot(String productId, String snapshotKey) {
        if (productId == null || snapshotKey == null) return false;
        String stored = prefs.getString(KEY_LAST_SNAPSHOT_PREFIX + productId, null);
        if (snapshotKey.equals(stored)) return false;
        prefs.edit().putString(KEY_LAST_SNAPSHOT_PREFIX + productId, snapshotKey).apply();
        return true;
    }
}
