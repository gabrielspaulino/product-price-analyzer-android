package com.owly.pricetracker;

import android.app.Application;

import com.owly.pricetracker.work.ProductAnalysisScheduler;

public class OwlyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ProductAnalysisScheduler.schedule(this);
    }
}
