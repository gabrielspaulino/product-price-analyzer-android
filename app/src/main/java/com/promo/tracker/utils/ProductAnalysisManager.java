package com.promo.tracker.utils;

import android.content.Context;

import com.promo.tracker.models.PriceSnapshot;
import com.promo.tracker.models.Product;
import com.promo.tracker.models.User;
import com.promo.tracker.services.GrokSearchService;
import com.promo.tracker.services.SupabaseService;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ProductAnalysisManager {

    private final Context context;
    private final User currentUser;
    private final NotificationPrefs notificationPrefs;

    public ProductAnalysisManager(Context context, User currentUser) {
        this.context = context.getApplicationContext();
        this.currentUser = currentUser;
        this.notificationPrefs = new NotificationPrefs(this.context);
        NotificationHelper.createNotificationChannel(this.context);
    }

    public Result analyzeProduct(Product product) {
        Result result = new Result();
        if (currentUser == null || product == null) {
            result.success = false;
            result.errorMessage = "Sessão inválida";
            return result;
        }

        try {
            List<PriceSnapshot> snapshots = new ArrayList<>(
                    GrokSearchService.getInstance().searchTwitterPrices(product.getName(), product.getLastUpdated(), currentUser.getAccessToken()));
            double lowest = Double.MAX_VALUE;
            for (PriceSnapshot snapshot : snapshots) {
                snapshot.setProductId(product.getId());
                SupabaseService.getInstance().saveSnapshot(
                        currentUser.getAccessToken(), snapshot);
                notifyIfNewSale(product, snapshot);
                if (snapshot.getPrice() < lowest) lowest = snapshot.getPrice();
            }

            double finalLowest = lowest == Double.MAX_VALUE ? 0 : lowest;
            if (finalLowest > 0) {
                SupabaseService.getInstance().updateProductPrice(
                        currentUser.getAccessToken(), product.getId(), finalLowest);
                product.setCurrentPrice(finalLowest);
                product.setLastUpdated(Instant.now().toString());
            }
            product.setAnalyzing(false);
            product.setStatus(finalLowest > 0 ? "success" : "idle");

            result.success = true;
            result.lowestPrice = finalLowest;
            return result;
        } catch (Exception e) {
            product.setAnalyzing(false);
            product.setStatus("error");
            result.success = false;
            result.errorMessage = e.getMessage();
            return result;
        }
    }

    private void notifyIfNewSale(Product product, PriceSnapshot snapshot) {
        Double targetPrice = product.getTargetPrice();
        if (targetPrice != null && snapshot.getPrice() > targetPrice) {
            return;
        }

        String snapshotKey = snapshot.getTweetUrl() != null
                ? snapshot.getTweetUrl()
                : snapshot.getCapturedAt();
        if (snapshotKey == null || snapshotKey.isEmpty()) {
            snapshotKey = product.getId() + ":" + snapshot.getPrice();
        }

        if (notificationPrefs.markSnapshot(product.getId(), snapshotKey)) {
            NotificationHelper.sendSaleNotification(context, product, snapshot);
        }
    }

    public static class Result {
        public boolean success;
        public double lowestPrice;
        public String errorMessage;
    }
}
