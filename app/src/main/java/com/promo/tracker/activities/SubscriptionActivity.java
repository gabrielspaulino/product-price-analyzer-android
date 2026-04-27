package com.promo.tracker.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.promo.tracker.R;
import com.promo.tracker.billing.SubscriptionManager;
import com.promo.tracker.models.User;
import com.promo.tracker.utils.SessionManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscriptionActivity extends AppCompatActivity {

    private SubscriptionManager subscriptionManager;
    private User currentUser;
    private Button btnAction;
    private ProgressBar progress;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subscription);

        subscriptionManager = SubscriptionManager.getInstance();
        currentUser = SessionManager.getInstance(this).getUser();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        TextView tvCurrentPlan = findViewById(R.id.tv_current_plan);
        TextView tvPlanPrice = findViewById(R.id.tv_plan_price);
        btnAction = findViewById(R.id.btn_action);
        progress = findViewById(R.id.progress);

        updateUI(tvCurrentPlan, tvPlanPrice);
    }

    @Override
    protected void onResume() {
        super.onResume();
        executor.execute(() -> {
            subscriptionManager.refresh(currentUser.getAccessToken(), currentUser.getId());
            runOnUiThread(() -> {
                TextView tvCurrentPlan = findViewById(R.id.tv_current_plan);
                TextView tvPlanPrice = findViewById(R.id.tv_plan_price);
                updateUI(tvCurrentPlan, tvPlanPrice);
            });
        });
    }

    private void updateUI(TextView tvCurrentPlan, TextView tvPlanPrice) {
        boolean premium = subscriptionManager.isPremium();

        if (premium) {
            tvCurrentPlan.setText(getString(R.string.subscription_current_plan,
                    getString(R.string.subscription_premium_name)));
            tvPlanPrice.setText(R.string.subscription_premium_price);
            btnAction.setText(R.string.subscription_manage_btn);
            btnAction.setOnClickListener(v -> openPortal());
        } else {
            tvCurrentPlan.setText(getString(R.string.subscription_current_plan,
                    getString(R.string.subscription_free_name)));
            tvPlanPrice.setVisibility(View.GONE);
            btnAction.setText(R.string.subscription_subscribe_btn);
            btnAction.setOnClickListener(v -> startCheckout());
        }
    }

    private void startCheckout() {
        progress.setVisibility(View.VISIBLE);
        btnAction.setEnabled(false);
        executor.execute(() -> {
            try {
                String url = subscriptionManager.getCheckoutUrl(
                        currentUser.getAccessToken(), currentUser.getId());
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnAction.setEnabled(true);
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnAction.setEnabled(true);
                    Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void openPortal() {
        progress.setVisibility(View.VISIBLE);
        btnAction.setEnabled(false);
        executor.execute(() -> {
            try {
                String url = subscriptionManager.getPortalUrl(
                        currentUser.getAccessToken(), currentUser.getId());
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnAction.setEnabled(true);
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnAction.setEnabled(true);
                    Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}
