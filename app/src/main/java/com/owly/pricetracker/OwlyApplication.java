package com.owly.pricetracker;

import android.app.Application;

import com.owly.pricetracker.utils.NotificationHelper;

public class OwlyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createNotificationChannel(this);
    }
}
