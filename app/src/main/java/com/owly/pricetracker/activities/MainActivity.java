package com.owly.pricetracker.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.owly.pricetracker.R;
import com.owly.pricetracker.adapters.ProductAdapter;
import com.owly.pricetracker.models.PriceSnapshot;
import com.owly.pricetracker.models.Product;
import com.owly.pricetracker.models.User;
import com.owly.pricetracker.services.SerperApiService;
import com.owly.pricetracker.services.SupabaseService;
import com.owly.pricetracker.utils.LogoLoader;
import com.owly.pricetracker.utils.SessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements ProductAdapter.Listener {

    // Header
    private android.widget.ImageView ivLogo;
    private TextView tvAvatar, tvUserEmail;
    private LinearLayout layoutUser;

    // Settings panel
    private LinearLayout layoutSettingsContent;
    private TextView btnToggleSettings;
    private TextInputEditText etSerperKey;
    private Button btnSaveKeys;
    private boolean settingsExpanded = true;

    // Add product
    private TextInputEditText etProductName;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        session = SessionManager.getInstance(this);
        currentUser = session.getUser();
        if (currentUser == null) { goToAuth(); return; }

        bindViews();
        setupHeader();
        setupSettingsPanel();
        setupAddProduct();
        setupRecycler();
        initSerperKey();
        loadProducts();
    }

    private void bindViews() {
        ivLogo           = findViewById(R.id.iv_logo);
        tvAvatar         = findViewById(R.id.tv_avatar);
        tvUserEmail      = findViewById(R.id.tv_user_email);
        layoutUser       = findViewById(R.id.layout_user);
        layoutSettingsContent = findViewById(R.id.layout_settings_content);
        btnToggleSettings = findViewById(R.id.btn_toggle_settings);
        etSerperKey      = findViewById(R.id.et_serper_key);
        btnSaveKeys      = findViewById(R.id.btn_save_keys);
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

    private void setupSettingsPanel() {
        btnToggleSettings.setOnClickListener(v -> {
            settingsExpanded = !settingsExpanded;
            layoutSettingsContent.setVisibility(settingsExpanded ? View.VISIBLE : View.GONE);
            btnToggleSettings.setText(settingsExpanded ? "▲" : "▼");
        });
        btnSaveKeys.setOnClickListener(v -> saveSerperKey());
    }

    private void setupAddProduct() {
        btnAddProduct.setOnClickListener(v -> {
            String name = etProductName.getText() != null
                    ? etProductName.getText().toString().trim() : "";
            if (name.isEmpty()) {
                Toast.makeText(this, "Digite o nome do produto", Toast.LENGTH_SHORT).show();
                return;
            }
            etProductName.setText("");
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
        recyclerProducts.setLayoutManager(new LinearLayoutManager(this));
        recyclerProducts.setAdapter(adapter);
        recyclerProducts.setNestedScrollingEnabled(false);

        swipeRefresh.setColorSchemeResources(R.color.accent_primary_fallback);
        swipeRefresh.setOnRefreshListener(this::loadProducts);
    }

    private void initSerperKey() {
        String key = session.getSerperKey();
        if (key != null && !key.isEmpty()) {
            SerperApiService.getInstance().setApiKey(key);
        }
    }

    // ── Data loading ─────────────────────────────────────────────────────────

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
                    if (products.isEmpty()) loadTrending();
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
                    if (SerperApiService.getInstance().hasApiKey()) analyze(product);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    toast("Erro: " + e.getMessage());
                });
            }
        });
    }

    // ── Analysis ─────────────────────────────────────────────────────────────

    private void analyze(Product product) {
        if (!SerperApiService.getInstance().hasApiKey()) {
            toast("Configure a chave Serper nas configurações");
            return;
        }
        int idx = products.indexOf(product);
        product.setAnalyzing(true);
        product.setStatus("loading");
        if (idx >= 0) adapter.notifyItemChanged(idx);

        executor.execute(() -> {
            try {
                // Twitter-only search, matching the web app behaviour
                List<PriceSnapshot> all = new ArrayList<>(
                        SerperApiService.getInstance().searchTwitterPrices(product.getName()));

                double lowest = Double.MAX_VALUE;
                for (PriceSnapshot s : all) {
                    s.setProductId(product.getId());
                    SupabaseService.getInstance().saveSnapshot(currentUser.getAccessToken(), s);
                    if (s.getPrice() < lowest) lowest = s.getPrice();
                }

                double finalLowest = lowest == Double.MAX_VALUE ? 0 : lowest;
                if (finalLowest > 0)
                    SupabaseService.getInstance().updateProductPrice(
                            currentUser.getAccessToken(), product.getId(), finalLowest);

                runOnUiThread(() -> {
                    product.setAnalyzing(false);
                    product.setStatus("success");
                    if (finalLowest > 0) product.setCurrentPrice(finalLowest);
                    product.setLastUpdated(java.time.Instant.now().toString());
                    int i = products.indexOf(product);
                    if (i >= 0) adapter.notifyItemChanged(i);
                    if (product.isTargetReached())
                        toast("🎯 " + product.getName() + " atingiu o preço alvo!");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    product.setAnalyzing(false);
                    product.setStatus("error");
                    int i = products.indexOf(product);
                    if (i >= 0) adapter.notifyItemChanged(i);
                    toast("Erro: " + e.getMessage());
                });
            }
        });
    }

    private void analyzeAll() {
        if (!SerperApiService.getInstance().hasApiKey()) {
            toast("Configure a chave Serper nas configurações");
            return;
        }
        if (products.isEmpty()) { toast("Nenhum produto para analisar"); return; }
        toast("Analisando " + products.size() + " produto(s)…");
        for (Product p : new ArrayList<>(products)) analyze(p);
    }

    // ── Adapter callbacks ─────────────────────────────────────────────────────

    @Override public void onAnalyze(Product p) { analyze(p); }

    @Override
    public void onRemove(Product p) {
        new AlertDialog.Builder(this, R.style.OwlyDialog)
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

    // ── Settings panel key save ───────────────────────────────────────────────

    private void saveSerperKey() {
        String key = etSerperKey.getText() != null
                ? etSerperKey.getText().toString().trim() : "";
        if (key.isEmpty()) { toast("Digite a chave"); return; }
        session.saveSerperKey(key);
        SerperApiService.getInstance().setApiKey(key);
        executor.execute(() -> {
            try {
                SupabaseService.getInstance().saveApiCredentials(
                        currentUser.getAccessToken(), currentUser.getId(), key);
            } catch (Exception ignored) {}
        });
        etSerperKey.setText("");
        toast("✓ Chave salva com sucesso!");
    }

    // ── User menu ─────────────────────────────────────────────────────────────

    private void showUserMenu() {
        new AlertDialog.Builder(this, R.style.OwlyDialog)
                .setTitle(currentUser.getDisplayEmail())
                .setItems(new String[]{"Configurações", "Sair"}, (d, which) -> {
                    if (which == 0) startActivity(new Intent(this, SettingsActivity.class));
                    else logout();
                }).show();
    }

    private void logout() {
        session.clearSession();
        goToAuth();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateEmptyState() {
        boolean empty = products.isEmpty();
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerProducts.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) layoutTrending.setVisibility(View.GONE);
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

    @Override protected void onResume() { super.onResume(); initSerperKey(); }
    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
