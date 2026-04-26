package com.promo.tracker;

import android.app.Application;

import com.promo.tracker.utils.NotificationHelper;

public class PromoTrackerApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createNotificationChannel(this);
    }
}
