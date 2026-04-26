package com.promo.tracker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.promo.tracker.models.Product;
import com.promo.tracker.models.User;
import com.promo.tracker.services.SupabaseService;
import com.promo.tracker.utils.NotificationHelper;
import com.promo.tracker.utils.ProductAnalysisManager;
import com.promo.tracker.utils.SessionManager;

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
        User currentUser = getRefreshedUser(session);
        if (currentUser == null) return Result.success();

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

    private User getRefreshedUser(SessionManager session) {
        User savedUser = session.getUser();
        if (savedUser == null) return null;

        String refreshToken = savedUser.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            return savedUser;
        }

        try {
            User refreshedUser = SupabaseService.getInstance().refreshToken(refreshToken);
            if (refreshedUser.getId() == null) {
                refreshedUser.setId(savedUser.getId());
            }
            if (refreshedUser.getEmail() == null) {
                refreshedUser.setEmail(savedUser.getEmail());
            }
            if (refreshedUser.getRefreshToken() == null || refreshedUser.getRefreshToken().isEmpty()) {
                refreshedUser.setRefreshToken(savedUser.getRefreshToken());
            }
            session.saveUser(refreshedUser);
            return refreshedUser;
        } catch (Exception ignored) {
            return savedUser;
        }
    }
}
