package com.promo.tracker.activities;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.promo.tracker.R;
import com.promo.tracker.adapters.ProductAdapter;
import com.promo.tracker.billing.SubscriptionManager;
import com.promo.tracker.models.Product;
import com.promo.tracker.models.User;
import com.promo.tracker.services.GrokSearchService;
import com.promo.tracker.services.SupabaseService;
import com.promo.tracker.utils.LogoLoader;
import com.promo.tracker.utils.NonScrollableLinearLayoutManager;
import com.promo.tracker.utils.NotificationHelper;
import com.promo.tracker.utils.NotificationPrefs;
import com.promo.tracker.utils.ProductAnalysisManager;
import com.promo.tracker.utils.PushTokenManager;
import com.promo.tracker.utils.SessionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements ProductAdapter.Listener {

    private static final String TAG = "MainActivity";

    // Header
    private android.widget.ImageView ivLogo;
    private TextView tvAvatar, tvUserEmail;
    private LinearLayout layoutUser;

    // Add product
    private AutoCompleteTextView etProductName;
    private Button btnAddProduct, btnAnalyzeAll;

    // List
    private RecyclerView recyclerProducts;
    private ProductAdapter adapter;
    private LinearLayout layoutEmpty, layoutTrending;
    private ChipGroup chipGroupTrending;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;

    private final List<Product> products = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private SessionManager session;
    private User currentUser;
    private NotificationPrefs notificationPrefs;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ProductAnalysisManager analysisManager;
    private SubscriptionManager subscriptionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        session = SessionManager.getInstance(this);
        currentUser = session.getUser();
        if (currentUser == null) { goToAuth(); return; }

        subscriptionManager = SubscriptionManager.getInstance();
        bindViews();
        setupHeader();
        setupAddProduct();
        setupRecycler();
        initNotificationSupport();
        analysisManager = new ProductAnalysisManager(this, currentUser);
        loadProducts();
        refreshSubscription();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentUser != null) refreshSubscription();
    }

    private void refreshSubscription() {
        executor.execute(() -> subscriptionManager.refresh(
                currentUser.getAccessToken(), currentUser.getId()));
    }

    private void bindViews() {
        ivLogo           = findViewById(R.id.iv_logo);
        tvAvatar         = findViewById(R.id.tv_avatar);
        tvUserEmail      = findViewById(R.id.tv_user_email);
        layoutUser       = findViewById(R.id.layout_user);
        etProductName    = findViewById(R.id.et_product_name);
        btnAddProduct    = findViewById(R.id.btn_add_product);
        btnAnalyzeAll    = findViewById(R.id.btn_analyze_all);
        recyclerProducts = findViewById(R.id.recycler_products);
        layoutEmpty      = findViewById(R.id.layout_empty);
        layoutTrending   = findViewById(R.id.layout_trending);
        chipGroupTrending = findViewById(R.id.chip_group_trending);
        progressBar      = findViewById(R.id.progress_bar);
        swipeRefresh     = findViewById(R.id.swipe_refresh);
    }

    private void setupHeader() {
        LogoLoader.load(this, ivLogo);
        tvAvatar.setText(currentUser.getInitial());
        tvUserEmail.setText(currentUser.getDisplayEmail());
        layoutUser.setOnClickListener(v -> showUserMenu());
    }

    private void setupAddProduct() {
        ArrayAdapter<String> autocompleteAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, new java.util.ArrayList<>());
        etProductName.setAdapter(autocompleteAdapter);
        etProductName.setThreshold(2);

        etProductName.addTextChangedListener(new android.text.TextWatcher() {
            private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            private Runnable runnable;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (runnable != null) handler.removeCallbacks(runnable);
            }

            @Override public void afterTextChanged(android.text.Editable s) {
                String query = s != null ? s.toString().trim() : "";
                if (query.length() < 2) return;

                runnable = () -> executor.execute(() -> {
                    try {
                        java.util.List<String> suggestions = SupabaseService.getInstance()
                                .searchProductNames(currentUser.getAccessToken(), query);
                        runOnUiThread(() -> {
                            autocompleteAdapter.clear();
                            autocompleteAdapter.addAll(suggestions);
                            autocompleteAdapter.notifyDataSetChanged();
                        });
                    } catch (Exception ignored) {}
                });
                handler.postDelayed(runnable, 300);
            }
        });

        btnAddProduct.setOnClickListener(v -> {
            String name = etProductName.getText() != null
                    ? etProductName.getText().toString().trim() : "";
            if (name.isEmpty()) {
                Toast.makeText(this, "Digite o nome do produto", Toast.LENGTH_SHORT).show();
                return;
            }
            etProductName.setText("");
            etProductName.dismissDropDown();
            addProduct(name, null);
        });

        etProductName.setOnEditorActionListener((v, actionId, event) -> {
            btnAddProduct.performClick();
            return true;
        });

        btnAnalyzeAll.setOnClickListener(v -> analyzeAll());
    }

    private void setupRecycler() {
        adapter = new ProductAdapter(products, this);
        recyclerProducts.setLayoutManager(new NonScrollableLinearLayoutManager(this));
        recyclerProducts.setAdapter(adapter);
        recyclerProducts.setNestedScrollingEnabled(false);
        recyclerProducts.setHasFixedSize(false);

        swipeRefresh.setColorSchemeResources(R.color.accent_primary_fallback);
        swipeRefresh.setOnRefreshListener(this::loadProducts);
    }

    private void initNotificationSupport() {
        notificationPrefs = new NotificationPrefs(this);
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    Log.d(TAG, "Notification permission granted: " + granted);
                    if (granted) {
                        PushTokenManager.syncToken(this, currentUser);
                    }
                });

        NotificationHelper.createNotificationChannel(this);

        if (NotificationHelper.needsRuntimePermission()
                && !NotificationHelper.canPostNotifications(this)) {
            requestNotificationPermission();
        } else {
            PushTokenManager.syncToken(this, currentUser);
        }
    }

    private void requestNotificationPermission() {
        if (!NotificationHelper.needsRuntimePermission()) return;
        if (NotificationHelper.canPostNotifications(this)) return;

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void loadProducts() {
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                List<Product> loaded = SupabaseService.getInstance()
                        .getUserWatchlist(currentUser.getAccessToken(), currentUser.getId());
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                    products.clear();
                    products.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                    if (products.isEmpty()) {
                        loadTrending();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                    toast("Erro ao carregar: " + e.getMessage());
                });
            }
        });
    }

    private void loadTrending() {
        executor.execute(() -> {
            try {
                List<Product> trending = SupabaseService.getInstance()
                        .getTrending(currentUser.getAccessToken());
                runOnUiThread(() -> {
                    chipGroupTrending.removeAllViews();
                    for (Product p : trending) {
                        Chip chip = new Chip(this);
                        chip.setText(p.getName());
                        chip.setCheckable(false);
                        chip.setOnClickListener(v -> addProduct(p.getName(), null));
                        chipGroupTrending.addView(chip);
                    }
                    layoutTrending.setVisibility(trending.isEmpty() ? View.GONE : View.VISIBLE);
                });
            } catch (Exception ignored) {}
        });
    }

    private void addProduct(String name, Double targetPrice) {
        if (!subscriptionManager.canAddProduct(products.size())) {
            showUpgradeDialog(getString(R.string.paywall_msg_products,
                    SubscriptionManager.FREE_MAX_WATCHED_PRODUCTS));
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                Product product = SupabaseService.getInstance()
                        .findOrCreateProduct(currentUser.getAccessToken(), name);

                boolean alreadyWatching = products.stream()
                        .anyMatch(p -> p.getId().equals(product.getId()));
                if (alreadyWatching) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        toast("Produto já está na lista");
                    });
                    return;
                }

                String watchId = SupabaseService.getInstance().addWatch(
                        currentUser.getAccessToken(), currentUser.getId(),
                        product.getId(), targetPrice);
                if (watchId != null) product.setWatchId(watchId);
                product.setTargetPrice(targetPrice);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    products.add(0, product);
                    adapter.notifyItemInserted(0);
                    recyclerProducts.scrollToPosition(0);
                    updateEmptyState();
                    toast("\"" + name + "\" adicionado!");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    toast("Erro: " + e.getMessage());
                });
            }
        });
    }

    private void analyze(Product product) {
        if (!subscriptionManager.canAnalyze()) {
            showUpgradeDialog(getString(R.string.paywall_msg_analyses,
                    SubscriptionManager.FREE_MAX_WEEKLY_ANALYSES));
            return;
        }
        markProductLoading(product);
        executor.execute(() -> {
            subscriptionManager.recordAnalysis(
                    currentUser.getAccessToken(), currentUser.getId());
            runAnalysisInBackground(product);
        });
    }

    private void analyzeAll() {
        if (products.isEmpty()) { toast("Nenhum produto para analisar"); return; }
        if (!subscriptionManager.canAnalyze()) {
            showUpgradeDialog(getString(R.string.paywall_msg_analyses,
                    SubscriptionManager.FREE_MAX_WEEKLY_ANALYSES));
            return;
        }
        toast("Analisando produto(s)…");
        executor.execute(() -> {
            List<Product> snapshot = new ArrayList<>(products);
            for (Product product : snapshot) {
                if (!subscriptionManager.canAnalyze()) {
                    runOnUiThread(() -> toast("Limite de análises atingido"));
                    break;
                }
                subscriptionManager.recordAnalysis(
                        currentUser.getAccessToken(), currentUser.getId());
                markProductLoading(product);
                runAnalysisInBackground(product);
            }
        });
    }

    private void markProductLoading(Product product) {
        runOnUiThread(() -> {
            int idx = products.indexOf(product);
            product.setAnalyzing(true);
            product.setStatus("loading");
            if (idx >= 0) adapter.notifyItemChanged(idx);
        });
    }

    private void runAnalysisInBackground(Product product) {
        ProductAnalysisManager.Result result = analysisManager.analyzeProduct(product);
        runOnUiThread(() -> {
            int idx = products.indexOf(product);
            if (idx >= 0) adapter.notifyItemChanged(idx);
            if (!result.success) {
                toast("Erro: " + (result.errorMessage != null ? result.errorMessage : "Erro inesperado"));
            } else if (product.isTargetReached()) {
                toast("🎯 " + product.getName() + " atingiu o preço alvo!");
            }
        });
    }

    @Override public void onAnalyze(Product p) { analyze(p); }

    @Override
    public void onRemove(Product p) {
        new AlertDialog.Builder(this, R.style.PromoTrackerDialog)
                .setTitle("Remover produto")
                .setMessage("Remover \"" + p.getName() + "\" da lista?")
                .setPositiveButton("Remover", (d, w) -> removeProduct(p))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    public void onCardClick(Product p) {
        Intent i = new Intent(this, ProductDetailActivity.class);
        i.putExtra("product_id",   p.getId());
        i.putExtra("product_name", p.getName());
        i.putExtra("watch_id",     p.getWatchId());
        i.putExtra("current_price", p.getCurrentPrice() != null ? p.getCurrentPrice() : -1.0);
        i.putExtra("target_price",  p.getTargetPrice()  != null ? p.getTargetPrice()  : -1.0);
        i.putExtra("last_updated",  p.getLastUpdated());
        startActivityForResult(i, 100);
    }

    @Override
    public void onSetTarget(Product p, Double newTarget) {
        executor.execute(() -> {
            try {
                SupabaseService.getInstance()
                        .updateTargetPrice(currentUser.getAccessToken(), p.getWatchId(), newTarget);
                runOnUiThread(() -> {
                    p.setTargetPrice(newTarget);
                    int i = products.indexOf(p);
                    if (i >= 0) adapter.notifyItemChanged(i);
                    toast("Preço alvo atualizado");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Erro: " + e.getMessage()));
            }
        });
    }

    private void removeProduct(Product p) {
        executor.execute(() -> {
            try {
                if (p.getWatchId() != null)
                    SupabaseService.getInstance()
                            .removeWatch(currentUser.getAccessToken(), p.getWatchId());
                runOnUiThread(() -> {
                    int idx = products.indexOf(p);
                    if (idx >= 0) { products.remove(idx); adapter.notifyItemRemoved(idx); }
                    updateEmptyState();
                    toast("\"" + p.getName() + "\" removido");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Erro: " + e.getMessage()));
            }
        });
    }

    private void showUserMenu() {
        new AlertDialog.Builder(this, R.style.PromoTrackerDialog)
                .setTitle(currentUser.getDisplayEmail())
                .setItems(new String[]{"Assinatura", "Configurações", "Sair"}, (d, which) -> {
                    if (which == 0) startActivity(new Intent(this, SubscriptionActivity.class));
                    else if (which == 1) startActivity(new Intent(this, SettingsActivity.class));
                    else logout();
                }).show();
    }

    private void logout() {
        PushTokenManager.unregisterCurrentToken(currentUser);
        session.clearSession();
        goToAuth();
    }

    private void updateEmptyState() {
        boolean empty = products.isEmpty();
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerProducts.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) layoutTrending.setVisibility(View.GONE);
    }

    private void showUpgradeDialog(String message) {
        new AlertDialog.Builder(this, R.style.PromoTrackerDialog)
                .setTitle(R.string.paywall_title)
                .setMessage(message)
                .setPositiveButton(R.string.paywall_upgrade, (d, w) -> startUpgrade())
                .setNegativeButton(R.string.paywall_cancel, null)
                .show();
    }

    private void startUpgrade() {
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String url = subscriptionManager.getCheckoutUrl(
                        currentUser.getAccessToken(), currentUser.getId());
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    toast("Erro: " + e.getMessage());
                });
            }
        });
    }

    private void goToAuth() {
        startActivity(new Intent(this, AuthActivity.class));
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            String watchId = data.getStringExtra("watch_id");
            double newTarget = data.getDoubleExtra("target_price", -1);
            for (Product p : products) {
                if (watchId != null && watchId.equals(p.getWatchId())) {
                    p.setTargetPrice(newTarget > 0 ? newTarget : null);
                    int i = products.indexOf(p);
                    if (i >= 0) adapter.notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
