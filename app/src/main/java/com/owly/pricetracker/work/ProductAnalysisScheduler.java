package com.owly.pricetracker.work;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class ProductAnalysisScheduler {

    private static final String UNIQUE_WORK_NAME = "product_analysis_hourly";

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                        ProductAnalysisWorker.class,
                        1,
                        TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }
}
