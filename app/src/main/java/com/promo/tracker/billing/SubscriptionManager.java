package com.promo.tracker.billing;

import android.util.Log;

import com.promo.tracker.services.SupabaseService;

import java.io.IOException;

public class SubscriptionManager {
    private static final String TAG = "SubscriptionManager";

    public static int FREE_MAX_WATCHED_PRODUCTS = 3;
    public static int FREE_MAX_WEEKLY_ANALYSES = 3;

    private static SubscriptionManager instance;
    private final BillingProvider billingProvider;

    private volatile boolean premium = false;
    private volatile int weeklyAnalysisCount = 0;

    private SubscriptionManager() {
        this.billingProvider = new StripeBillingProvider();
    }

    public static synchronized SubscriptionManager getInstance() {
        if (instance == null) instance = new SubscriptionManager();
        return instance;
    }

    public void refresh(String token, String userId) {
        try {
            String slug = SupabaseService.getInstance().getUserPlanSlug(token, userId);
            this.premium = "premium".equals(slug);
        } catch (IOException e) {
            Log.e(TAG, "Failed to fetch subscription: " + e.getMessage());
        }

        try {
            this.weeklyAnalysisCount = SupabaseService.getInstance()
                    .getWeeklyAnalysisCount(token, userId);
        } catch (IOException e) {
            Log.e(TAG, "Failed to fetch analysis count: " + e.getMessage());
        }
    }

    public boolean isPremium() {
        return premium;
    }

    public boolean canAddProduct(int currentWatchCount) {
        if (premium) return true;
        return currentWatchCount < FREE_MAX_WATCHED_PRODUCTS;
    }

    public boolean canAnalyze() {
        if (premium) return true;
        return weeklyAnalysisCount < FREE_MAX_WEEKLY_ANALYSES;
    }

    public int getRemainingAnalyses() {
        if (premium) return Integer.MAX_VALUE;
        return Math.max(0, FREE_MAX_WEEKLY_ANALYSES - weeklyAnalysisCount);
    }

    public void recordAnalysis(String token, String userId) {
        try {
            SupabaseService.getInstance().logAnalysis(token, userId);
        } catch (IOException e) {
            Log.e(TAG, "Failed to log analysis: " + e.getMessage());
        }
        weeklyAnalysisCount++;
    }

    public String getCheckoutUrl(String token, String userId) throws IOException {
        return billingProvider.createCheckoutUrl(token, userId);
    }

    public String getPortalUrl(String token, String userId) throws IOException {
        return billingProvider.createPortalUrl(token, userId);
    }
}
