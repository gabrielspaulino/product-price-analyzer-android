package com.promo.tracker.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.promo.tracker.models.Product;
import com.promo.tracker.models.User;
import com.promo.tracker.services.SupabaseService;
import com.promo.tracker.utils.ProductAnalysisManager;
import com.promo.tracker.utils.SessionManager;

public class OnDemandAnalysisWorker extends Worker {

    private static final String TAG = "OnDemandAnalysisWorker";

    public static final String KEY_PRODUCT_ID = "product_id";
    public static final String KEY_PRODUCT_NAME = "product_name";
    public static final String KEY_LAST_UPDATED = "last_updated";
    public static final String KEY_RESULT_SUCCESS = "result_success";
    public static final String KEY_RESULT_PRICE = "result_price";
    public static final String KEY_RESULT_ERROR = "result_error";

    public OnDemandAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String productId = getInputData().getString(KEY_PRODUCT_ID);
        String productName = getInputData().getString(KEY_PRODUCT_NAME);
        String lastUpdated = getInputData().getString(KEY_LAST_UPDATED);

        if (productId == null || productName == null) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_RESULT_ERROR, "Dados do produto inválidos")
                    .build());
        }

        SessionManager session = SessionManager.getInstance(getApplicationContext());
        User user = refreshUser(session);
        if (user == null) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_RESULT_ERROR, "Sessão expirada")
                    .build());
        }

        Product product = new Product();
        product.setId(productId);
        product.setName(productName);
        product.setLastUpdated(lastUpdated);

        ProductAnalysisManager manager = new ProductAnalysisManager(getApplicationContext(), user);
        ProductAnalysisManager.Result result = manager.analyzeProduct(product);

        if (result.success) {
            return Result.success(new Data.Builder()
                    .putBoolean(KEY_RESULT_SUCCESS, true)
                    .putDouble(KEY_RESULT_PRICE, result.lowestPrice)
                    .putString(KEY_PRODUCT_ID, productId)
                    .build());
        } else {
            return Result.failure(new Data.Builder()
                    .putBoolean(KEY_RESULT_SUCCESS, false)
                    .putString(KEY_RESULT_ERROR, result.errorMessage)
                    .putString(KEY_PRODUCT_ID, productId)
                    .build());
        }
    }

    private User refreshUser(SessionManager session) {
        User savedUser = session.getUser();
        if (savedUser == null) return null;

        String refreshToken = savedUser.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) return savedUser;

        try {
            User refreshed = SupabaseService.getInstance().refreshToken(refreshToken);
            if (refreshed.getId() == null) refreshed.setId(savedUser.getId());
            if (refreshed.getEmail() == null) refreshed.setEmail(savedUser.getEmail());
            if (refreshed.getRefreshToken() == null || refreshed.getRefreshToken().isEmpty())
                refreshed.setRefreshToken(savedUser.getRefreshToken());
            session.saveUser(refreshed);
            return refreshed;
        } catch (Exception e) {
            Log.w(TAG, "Token refresh failed, using saved user", e);
            return savedUser;
        }
    }

    public static Data buildInputData(Product product) {
        Data.Builder builder = new Data.Builder()
                .putString(KEY_PRODUCT_ID, product.getId())
                .putString(KEY_PRODUCT_NAME, product.getName());
        if (product.getLastUpdated() != null) {
            builder.putString(KEY_LAST_UPDATED, product.getLastUpdated());
        }
        return builder.build();
    }
}
