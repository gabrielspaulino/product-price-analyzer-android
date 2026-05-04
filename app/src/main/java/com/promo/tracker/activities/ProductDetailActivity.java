package com.promo.tracker.activities;

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
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.promo.tracker.R;
import com.promo.tracker.adapters.SnapshotAdapter;
import com.promo.tracker.billing.SubscriptionManager;
import com.promo.tracker.models.PriceSnapshot;
import com.promo.tracker.models.Product;
import com.promo.tracker.models.User;
import com.promo.tracker.services.GrokSearchService;
import com.promo.tracker.services.SupabaseService;
import com.promo.tracker.utils.NonScrollableLinearLayoutManager;
import com.promo.tracker.utils.SessionManager;
import com.promo.tracker.work.OnDemandAnalysisWorker;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private boolean isAnalyzing;

    private TextView tvProductName, tvCurrentPrice, tvLastUpdated,
            tvStatusBadge, tvTargetCurrent;
    private MaterialButton btnAnalyze, btnSaveTarget, btnClearTarget, btnSortDate, btnSortPrice;
    private TextInputEditText etTargetPrice;
    private ProgressBar progressAnalyze, progressHistory;
    private LinearLayout layoutEmptyHistory;
    private RecyclerView recyclerHistory;
    private LineChart chartPrice;
    private View cardChart;
    private SnapshotAdapter snapshotAdapter;
    private final List<PriceSnapshot> snapshots = new ArrayList<>();
    private SortMode sortMode = SortMode.DATE;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private User currentUser;
    private SubscriptionManager subscriptionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_detail);

        currentUser = SessionManager.getInstance(this).getUser();
        subscriptionManager = SubscriptionManager.getInstance();

        productId    = getIntent().getStringExtra("product_id");
        productName  = getIntent().getStringExtra("product_name");
        watchId      = getIntent().getStringExtra("watch_id");
        currentPrice = getIntent().getDoubleExtra("current_price", -1);
        targetPrice  = getIntent().getDoubleExtra("target_price", -1);
        lastUpdated  = getIntent().getStringExtra("last_updated");
        isAnalyzing  = getIntent().getBooleanExtra("is_analyzing", false);

        bindViews();
        populateHeader();
        loadHistory();

        if (isAnalyzing) {
            btnAnalyze.setEnabled(false);
            btnAnalyze.setText("Analisando…");
            progressAnalyze.setVisibility(View.VISIBLE);
            String workRequestIdStr = getIntent().getStringExtra("work_request_id");
            if (workRequestIdStr != null) {
                observePendingWork(java.util.UUID.fromString(workRequestIdStr));
            }
        }
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
        chartPrice       = findViewById(R.id.chart_price);
        cardChart        = findViewById(R.id.card_chart);

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
            tvCurrentPrice.setText(GrokSearchService.formatPrice(currentPrice));
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
            tvTargetCurrent.setText("Preço alvo atual: " + GrokSearchService.formatPrice(targetPrice));
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
        if (isAnalyzing) return;
        if (!subscriptionManager.canAnalyze()) {
            showUpgradeDialog();
            return;
        }
        isAnalyzing = true;
        btnAnalyze.setEnabled(false);
        btnAnalyze.setText("Analisando…");
        progressAnalyze.setVisibility(View.VISIBLE);

        executor.execute(() -> subscriptionManager.recordAnalysis(
                currentUser.getAccessToken(), currentUser.getId()));

        Product product = new Product();
        product.setId(productId);
        product.setName(productName);
        product.setLastUpdated(lastUpdated);

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OnDemandAnalysisWorker.class)
                .setInputData(OnDemandAnalysisWorker.buildInputData(product))
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        WorkManager.getInstance(this).enqueue(request);
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.getId())
                .observe(this, this::handleWorkInfoUpdate);
    }

    private void observePendingWork(java.util.UUID workId) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId)
                .observe(this, this::handleWorkInfoUpdate);
    }

    private void handleWorkInfoUpdate(WorkInfo workInfo) {
        if (workInfo == null) return;
        if (!workInfo.getState().isFinished()) return;

        isAnalyzing = false;
        progressAnalyze.setVisibility(View.GONE);
        btnAnalyze.setEnabled(true);
        btnAnalyze.setText("Analisar Agora");

        if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
            double price = workInfo.getOutputData().getDouble(
                    OnDemandAnalysisWorker.KEY_RESULT_PRICE, 0);
            if (price > 0) {
                currentPrice = price;
                tvCurrentPrice.setText(GrokSearchService.formatPrice(price));
                tvStatusBadge.setText("Atualizado");
                tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_success);
            }
            tvLastUpdated.setText("Atualizado: agora");
            tvLastUpdated.setVisibility(View.VISIBLE);
            loadHistory();
            toast("Análise concluída!");
        } else if (workInfo.getState() == WorkInfo.State.FAILED) {
            String error = workInfo.getOutputData().getString(
                    OnDemandAnalysisWorker.KEY_RESULT_ERROR);
            toast("Erro: " + (error != null ? error : "Erro inesperado"));
        }
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
                    tvTargetCurrent.setText("Preço alvo: " + GrokSearchService.formatPrice(finalTarget));
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
        updateChart(loaded);
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

    private void updateChart(List<PriceSnapshot> data) {
        List<PriceSnapshot> withDates = new ArrayList<>();
        for (PriceSnapshot s : data) {
            String dateStr = s.getTweetDate() != null ? s.getTweetDate() : s.getCapturedAt();
            if (dateStr != null && !dateStr.isEmpty()
                    && s.getPrice() > 0 && parseSortableDate(dateStr) != Long.MIN_VALUE) {
                withDates.add(s);
            }
        }

        if (withDates.size() < 2) {
            cardChart.setVisibility(View.GONE);
            return;
        }

        Map<LocalDate, Double> lowestPerDay = new LinkedHashMap<>();
        for (PriceSnapshot s : withDates) {
            String dateStr = s.getTweetDate() != null ? s.getTweetDate() : s.getCapturedAt();
            long millis = parseSortableDate(dateStr);
            LocalDate day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
            Double existing = lowestPerDay.get(day);
            if (existing == null || s.getPrice() < existing) {
                lowestPerDay.put(day, s.getPrice());
            }
        }

        if (lowestPerDay.size() < 2) {
            cardChart.setVisibility(View.GONE);
            return;
        }

        LocalDate firstDay = lowestPerDay.keySet().iterator().next();
        long baseTime = firstDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : lowestPerDay.entrySet()) {
            int dayOffset = (int) (e.getKey().toEpochDay() - firstDay.toEpochDay());
            entries.add(new Entry(dayOffset, e.getValue().floatValue()));
        }

        int accentColor = androidx.core.content.ContextCompat.getColor(this, R.color.accent_primary);
        int textColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary);

        LineDataSet dataSet = new LineDataSet(entries, "");
        dataSet.setColor(accentColor);
        dataSet.setCircleColor(accentColor);
        dataSet.setCircleRadius(4f);
        dataSet.setLineWidth(2f);
        dataSet.setDrawValues(true);
        dataSet.setValueTextSize(10f);
        dataSet.setValueTextColor(accentColor);
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return GrokSearchService.formatPrice(value);
            }
        });
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(accentColor);
        dataSet.setFillAlpha(30);

        LineData lineData = new LineData(dataSet);
        chartPrice.setData(lineData);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault());
        XAxis xAxis = chartPrice.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(textColor);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                long millis = baseTime + (long) (value * 1000L * 60 * 60 * 24);
                try {
                    return Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .format(fmt);
                } catch (Exception e) {
                    return "";
                }
            }
        });

        chartPrice.getAxisLeft().setTextColor(textColor);
        chartPrice.getAxisLeft().setDrawGridLines(true);
        chartPrice.getAxisLeft().setGridColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.divider));
        chartPrice.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return "R$" + String.format(Locale.getDefault(), "%.0f", value);
            }
        });
        chartPrice.getAxisRight().setEnabled(false);
        chartPrice.getDescription().setEnabled(false);
        chartPrice.getLegend().setEnabled(false);
        chartPrice.setTouchEnabled(true);
        chartPrice.setDragEnabled(true);
        chartPrice.setScaleEnabled(false);
        chartPrice.setPinchZoom(false);
        chartPrice.setExtraBottomOffset(8f);
        chartPrice.invalidate();

        cardChart.setVisibility(View.VISIBLE);
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

    private void showUpgradeDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.PromoTrackerDialog)
                .setTitle(R.string.paywall_title)
                .setMessage(getString(R.string.paywall_msg_analyses,
                        SubscriptionManager.FREE_MAX_WEEKLY_ANALYSES))
                .setPositiveButton(R.string.paywall_upgrade, (d, w) -> startUpgrade())
                .setNegativeButton(R.string.paywall_cancel, null)
                .show();
    }

    private void startUpgrade() {
        progressAnalyze.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String url = subscriptionManager.getCheckoutUrl(
                        currentUser.getAccessToken(), currentUser.getId());
                runOnUiThread(() -> {
                    progressAnalyze.setVisibility(View.GONE);
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressAnalyze.setVisibility(View.GONE);
                    toast("Erro: " + e.getMessage());
                });
            }
        });
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() { returnResult(); super.onBackPressed(); }
    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
