package com.owly.pricetracker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.owly.pricetracker.models.Product;
import com.owly.pricetracker.models.User;
import com.owly.pricetracker.services.SerperApiService;
import com.owly.pricetracker.services.SupabaseService;
import com.owly.pricetracker.utils.NotificationHelper;
import com.owly.pricetracker.utils.ProductAnalysisManager;
import com.owly.pricetracker.utils.SessionManager;

import java.util.List;

public class ProductAnalysisWorker extends Worker {

    public ProductAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SessionManager session = SessionManager.getInstance(context);
        User currentUser = session.getUser();
        if (currentUser == null) return Result.success();

        String serperKey = session.getSerperKey();
        if (serperKey == null || serperKey.isEmpty()) return Result.success();
        SerperApiService.getInstance().setApiKey(serperKey);

        NotificationHelper.createNotificationChannel(context);

        try {
            List<Product> watchlist = SupabaseService.getInstance()
                    .getUserWatchlist(currentUser.getAccessToken(), currentUser.getId());
            ProductAnalysisManager manager = new ProductAnalysisManager(context, currentUser);
            for (Product product : watchlist) {
                manager.analyzeProduct(product);
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
