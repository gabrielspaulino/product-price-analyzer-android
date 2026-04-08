package com.owly.pricetracker.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.owly.pricetracker.R;
import com.owly.pricetracker.adapters.SnapshotAdapter;
import com.owly.pricetracker.models.PriceSnapshot;
import com.owly.pricetracker.models.User;
import com.owly.pricetracker.services.SerperApiService;
import com.owly.pricetracker.services.SupabaseService;
import com.owly.pricetracker.utils.NonScrollableLinearLayoutManager;
import com.owly.pricetracker.utils.SessionManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProductDetailActivity extends AppCompatActivity {
    private enum SortMode {
        DATE,
        LOWEST_PRICE
    }

    private String productId, productName, watchId, lastUpdated;
    private double currentPrice, targetPrice;

    private TextView tvProductName, tvCurrentPrice, tvLastUpdated,
            tvStatusBadge, tvTargetCurrent;
    private MaterialButton btnAnalyze, btnSaveTarget, btnClearTarget, btnSortDate, btnSortPrice;
    private TextInputEditText etTargetPrice;
    private ProgressBar progressAnalyze, progressHistory;
    private LinearLayout layoutEmptyHistory;
    private RecyclerView recyclerHistory;
    private SnapshotAdapter snapshotAdapter;
    private final List<PriceSnapshot> snapshots = new ArrayList<>();
    private SortMode sortMode = SortMode.DATE;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private User currentUser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_detail);

        currentUser = SessionManager.getInstance(this).getUser();

        productId    = getIntent().getStringExtra("product_id");
        productName  = getIntent().getStringExtra("product_name");
        watchId      = getIntent().getStringExtra("watch_id");
        currentPrice = getIntent().getDoubleExtra("current_price", -1);
        targetPrice  = getIntent().getDoubleExtra("target_price", -1);
        lastUpdated  = getIntent().getStringExtra("last_updated");

        bindViews();
        populateHeader();
        loadHistory();
    }

    private void bindViews() {
        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        tvProductName    = findViewById(R.id.tv_product_name);
        tvCurrentPrice   = findViewById(R.id.tv_current_price);
        tvLastUpdated    = findViewById(R.id.tv_last_updated);
        tvStatusBadge    = findViewById(R.id.tv_status_badge);
        tvTargetCurrent  = findViewById(R.id.tv_target_current);
        btnAnalyze       = findViewById(R.id.btn_analyze);
        btnSaveTarget    = findViewById(R.id.btn_save_target);
        btnClearTarget   = findViewById(R.id.btn_clear_target);
        btnSortDate      = findViewById(R.id.btn_sort_date);
        btnSortPrice     = findViewById(R.id.btn_sort_price);
        etTargetPrice    = findViewById(R.id.et_target_price);
        progressAnalyze  = findViewById(R.id.progress_analyze);
        progressHistory  = findViewById(R.id.progress_history);
        layoutEmptyHistory = findViewById(R.id.layout_empty_history);
        recyclerHistory  = findViewById(R.id.recycler_history);

        snapshotAdapter = new SnapshotAdapter(snapshots);
        recyclerHistory.setLayoutManager(new NonScrollableLinearLayoutManager(this));
        recyclerHistory.setAdapter(snapshotAdapter);
        // nestedScrollingEnabled=false lets NestedScrollView handle scrolling
        // and forces RecyclerView to measure and render ALL items at once
        recyclerHistory.setNestedScrollingEnabled(false);
        recyclerHistory.setHasFixedSize(false);

        findViewById(R.id.tv_header_title).setVisibility(View.GONE); // we show in card

        btnAnalyze.setOnClickListener(v -> analyzeNow());
        btnSaveTarget.setOnClickListener(v -> saveTarget());
        btnClearTarget.setOnClickListener(v -> clearTarget());
        setupSortSelector();
    }

    private void populateHeader() {
        tvProductName.setText(productName);

        if (currentPrice > 0) {
            tvCurrentPrice.setText(SerperApiService.formatPrice(currentPrice));
            tvStatusBadge.setText("Atualizado");
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_success);
        } else {
            tvCurrentPrice.setText("—");
            tvStatusBadge.setText("Aguardando");
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_idle);
        }

        if (lastUpdated != null) {
            tvLastUpdated.setText("Atualizado: " + formatDate(lastUpdated));
            tvLastUpdated.setVisibility(View.VISIBLE);
        }

        if (targetPrice > 0) {
            tvTargetCurrent.setText("Preço alvo atual: " + SerperApiService.formatPrice(targetPrice));
            tvTargetCurrent.setVisibility(View.VISIBLE);
        }
    }

    private void loadHistory() {
        progressHistory.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                List<PriceSnapshot> loaded = SupabaseService.getInstance()
                        .getSnapshots(currentUser.getAccessToken(), productId);
                runOnUiThread(() -> {
                    progressHistory.setVisibility(View.GONE);
                    setSnapshots(loaded);
                    layoutEmptyHistory.setVisibility(snapshots.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressHistory.setVisibility(View.GONE);
                    layoutEmptyHistory.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void analyzeNow() {
        if (!SerperApiService.getInstance().hasApiKey()) {
            toast("Configure a chave Serper em Configurações");
            return;
        }
        btnAnalyze.setEnabled(false);
        btnAnalyze.setText("Analisando…");
        progressAnalyze.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                // Twitter-only search, matching the web app behaviour
                List<PriceSnapshot> all = new ArrayList<>(
                        SerperApiService.getInstance().searchTwitterPrices(productName, lastUpdated));

                double lowest = Double.MAX_VALUE;
                for (PriceSnapshot s : all) {
                    s.setProductId(productId);
                    SupabaseService.getInstance().saveSnapshot(currentUser.getAccessToken(), s);
                    if (s.getPrice() < lowest) lowest = s.getPrice();
                }
                if (lowest < Double.MAX_VALUE) {
                    SupabaseService.getInstance().updateProductPrice(
                            currentUser.getAccessToken(), productId, lowest);
                    currentPrice = lowest;
                }

                List<PriceSnapshot> refreshed = SupabaseService.getInstance()
                        .getSnapshots(currentUser.getAccessToken(), productId);

                double finalLowest = lowest;
                runOnUiThread(() -> {
                    progressAnalyze.setVisibility(View.GONE);
                    btnAnalyze.setEnabled(true);
                    btnAnalyze.setText("Analisar Agora");
                    if (finalLowest < Double.MAX_VALUE) {
                        tvCurrentPrice.setText(SerperApiService.formatPrice(finalLowest));
                        tvStatusBadge.setText("Atualizado");
                        tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_success);
                        tvLastUpdated.setText("Atualizado: agora");
                        tvLastUpdated.setVisibility(View.VISIBLE);
                    }
                    setSnapshots(refreshed);
                    layoutEmptyHistory.setVisibility(snapshots.isEmpty() ? View.VISIBLE : View.GONE);
                    toast("Análise concluída!");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressAnalyze.setVisibility(View.GONE);
                    btnAnalyze.setEnabled(true);
                    btnAnalyze.setText("Analisar Agora");
                    toast("Erro: " + e.getMessage());
                });
            }
        });
    }

    private void saveTarget() {
        String val = etTargetPrice.getText() != null
                ? etTargetPrice.getText().toString().trim() : "";
        if (val.isEmpty()) { clearTarget(); return; }
        Double newTarget;
        try { newTarget = Double.parseDouble(val.replace(",", ".")); }
        catch (NumberFormatException e) { toast("Valor inválido"); return; }

        executor.execute(() -> {
            try {
                SupabaseService.getInstance()
                        .updateTargetPrice(currentUser.getAccessToken(), watchId, newTarget);
                double finalTarget = newTarget;
                runOnUiThread(() -> {
                    targetPrice = finalTarget;
                    tvTargetCurrent.setText("Preço alvo: " + SerperApiService.formatPrice(finalTarget));
                    tvTargetCurrent.setVisibility(View.VISIBLE);
                    etTargetPrice.setText("");
                    toast("Preço alvo salvo!");
                    returnResult();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Erro: " + e.getMessage()));
            }
        });
    }

    private void clearTarget() {
        executor.execute(() -> {
            try {
                SupabaseService.getInstance()
                        .updateTargetPrice(currentUser.getAccessToken(), watchId, null);
                runOnUiThread(() -> {
                    targetPrice = -1;
                    tvTargetCurrent.setVisibility(View.GONE);
                    etTargetPrice.setText("");
                    toast("Preço alvo removido");
                    returnResult();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Erro: " + e.getMessage()));
            }
        });
    }

    private void returnResult() {
        Intent data = new Intent();
        data.putExtra("watch_id", watchId);
        data.putExtra("target_price", targetPrice);
        setResult(RESULT_OK, data);
    }

    private void setSortMode(SortMode newMode) {
        if (sortMode == newMode) return;
        sortMode = newMode;
        sortSnapshots();
        snapshotAdapter.notifyDataSetChanged();
        updateSortSelector();
    }

    private void setSnapshots(List<PriceSnapshot> loaded) {
        snapshots.clear();
        snapshots.addAll(loaded);
        sortSnapshots();
        snapshotAdapter.notifyDataSetChanged();
        updateSortSelector();
    }

    private void sortSnapshots() {
        if (sortMode == SortMode.LOWEST_PRICE) {
            snapshots.sort(Comparator.comparingDouble(PriceSnapshot::getPrice));
            return;
        }

        snapshots.sort((left, right) -> compareDatesDesc(left.getTweetDate(), right.getTweetDate()));
    }

    private int compareDatesDesc(String left, String right) {
        long leftValue = parseSortableDate(left);
        long rightValue = parseSortableDate(right);
        return Long.compare(rightValue, leftValue);
    }

    private long parseSortableDate(String value) {
        if (value == null || value.isEmpty()) return Long.MIN_VALUE;
        try {
            return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try {
                return java.time.Instant.parse(value).toEpochMilli();
            } catch (Exception ignoredAgain) {
                return Long.MIN_VALUE;
            }
        }
    }

    private void setupSortSelector() {
        btnSortDate.setOnClickListener(v -> setSortMode(SortMode.DATE));
        btnSortPrice.setOnClickListener(v -> setSortMode(SortMode.LOWEST_PRICE));
        updateSortSelector();
    }

    private void updateSortSelector() {
        boolean byDate = sortMode == SortMode.DATE;
        styleSortButton(btnSortDate, byDate);
        styleSortButton(btnSortPrice, !byDate);
    }

    private void styleSortButton(MaterialButton button, boolean selected) {
        if (selected) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this, R.color.accent_primary)
            ));
            button.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.accent_primary_text));
            button.setStrokeWidth(0);
        } else {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this, android.R.color.transparent)
            ));
            button.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.btn_outline_text));
            button.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.sort_button_stroke_width));
            button.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this, R.color.btn_outline_stroke)
            ));
        }
        button.setEnabled(true);
    }

    private String formatDate(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(iso.split("\\.")[0].replace("Z", ""));
            SimpleDateFormat out = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            return out.format(date);
        } catch (Exception e) { return iso.length() > 10 ? iso.substring(0, 10) : iso; }
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() { returnResult(); super.onBackPressed(); }
    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
