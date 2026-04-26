package com.promo.tracker.services;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.promo.tracker.BuildConfig;
import com.promo.tracker.models.PriceSnapshot;
import com.promo.tracker.models.Product;
import com.promo.tracker.models.User;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SupabaseService {
    private static final String TAG = "SupabaseService";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String anonKey;
    private final OkHttpClient http;

    private static SupabaseService instance;

    private SupabaseService() {
        this.baseUrl = BuildConfig.SUPABASE_URL;
        this.anonKey = BuildConfig.SUPABASE_ANON_KEY;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized SupabaseService getInstance() {
        if (instance == null) instance = new SupabaseService();
        return instance;
    }

    // ─── Auth ───────────────────────────────────────────────────────────────

    public User signUp(String email, String password) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        Request req = new Request.Builder()
                .url(baseUrl + "/auth/v1/signup")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) throw new IOException(extractError(rb, "Erro ao criar conta"));
            return parseSession(rb);
        }
    }

    public User signIn(String email, String password) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        Request req = new Request.Builder()
                .url(baseUrl + "/auth/v1/token?grant_type=password")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) throw new IOException(extractError(rb, "Email ou senha inválidos"));
            return parseSession(rb);
        }
    }

    public User refreshToken(String refreshToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("refresh_token", refreshToken);
        Request req = new Request.Builder()
                .url(baseUrl + "/auth/v1/token?grant_type=refresh_token")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) throw new IOException("Sessão expirada. Faça login novamente.");
            return parseSession(rb);
        }
    }

    private User parseSession(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        User user = new User();
        if (obj.has("access_token"))  user.setAccessToken(obj.get("access_token").getAsString());
        if (obj.has("refresh_token")) user.setRefreshToken(obj.get("refresh_token").getAsString());
        if (obj.has("user")) {
            JsonObject u = obj.getAsJsonObject("user");
            user.setId(u.get("id").getAsString());
            user.setEmail(u.get("email").getAsString());
        }
        return user;
    }

    // ─── Products / Watchlist ────────────────────────────────────────────────

    public List<Product> getUserWatchlist(String token, String userId) throws IOException {
        String url = baseUrl + "/rest/v1/product_watches"
                + "?select=id,target_price,status,product_id,"
                + "products(id,name,normalized_name,status,current_price,last_updated,created_at)"
                + "&user_id=eq." + userId + "&status=eq.active";

        Request req = authGet(token, url);
        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) throw new IOException("Erro ao carregar lista");

            List<Product> list = new ArrayList<>();
            for (JsonElement el : JsonParser.parseString(rb).getAsJsonArray()) {
                JsonObject watch = el.getAsJsonObject();
                if (!watch.has("products") || watch.get("products").isJsonNull()) continue;
                JsonObject p = watch.getAsJsonObject("products");

                Product product = new Product();
                product.setId(str(p, "id"));
                product.setName(str(p, "name"));
                product.setNormalizedName(str(p, "normalized_name"));
                product.setStatus(str(p, "status"));
                if (p.has("current_price") && !p.get("current_price").isJsonNull())
                    product.setCurrentPrice(p.get("current_price").getAsDouble());
                if (p.has("last_updated") && !p.get("last_updated").isJsonNull())
                    product.setLastUpdated(p.get("last_updated").getAsString());

                product.setWatchId(str(watch, "id"));
                if (watch.has("target_price") && !watch.get("target_price").isJsonNull())
                    product.setTargetPrice(watch.get("target_price").getAsDouble());
                product.setWatchStatus(str(watch, "status"));
                list.add(product);
            }
            return list;
        }
    }

    public Product findOrCreateProduct(String token, String name) throws IOException {
        String normalized = name.toLowerCase().trim().replaceAll("\\s+", " ");
        String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString());

        // Try find existing
        String url = baseUrl + "/rest/v1/products?normalized_name=eq." + encoded
                + "&language=eq.pt&select=*";
        try (Response r = http.newCall(authGet(token, url)).execute()) {
            String rb = r.body().string();
            if (r.isSuccessful()) {
                JsonArray arr = JsonParser.parseString(rb).getAsJsonArray();
                if (arr.size() > 0) return parseProduct(arr.get(0).getAsJsonObject());
            }
        }

        // Create new
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("normalized_name", normalized);
        body.addProperty("language", "pt");
        body.addProperty("status", "idle");

        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/products")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Prefer", "return=representation")
                .build();
        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) throw new IOException("Erro ao criar produto");
            JsonArray arr = JsonParser.parseString(rb).getAsJsonArray();
            return parseProduct(arr.get(0).getAsJsonObject());
        }
    }

    private Product parseProduct(JsonObject p) {
        Product prod = new Product();
        prod.setId(str(p, "id"));
        prod.setName(str(p, "name"));
        prod.setNormalizedName(str(p, "normalized_name"));
        prod.setStatus(str(p, "status"));
        if (p.has("current_price") && !p.get("current_price").isJsonNull())
            prod.setCurrentPrice(p.get("current_price").getAsDouble());
        if (p.has("last_updated") && !p.get("last_updated").isJsonNull())
            prod.setLastUpdated(p.get("last_updated").getAsString());
        return prod;
    }

    public String addWatch(String token, String userId, String productId,
                           Double targetPrice) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId);
        body.addProperty("product_id", productId);
        body.addProperty("status", "active");
        if (targetPrice != null) body.addProperty("target_price", targetPrice);

        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/product_watches")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Prefer", "return=representation")
                .build();
        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (r.code() == 409) return null; // already watching
            if (!r.isSuccessful()) throw new IOException("Erro ao adicionar produto");
            JsonArray arr = JsonParser.parseString(rb).getAsJsonArray();
            return arr.get(0).getAsJsonObject().get("id").getAsString();
        }
    }

    public void removeWatch(String token, String watchId) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/product_watches?id=eq." + watchId)
                .delete()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful() && r.code() != 404)
                throw new IOException("Erro ao remover produto");
        }
    }

    public void updateTargetPrice(String token, String watchId, Double target) throws IOException {
        JsonObject body = new JsonObject();
        if (target != null) body.addProperty("target_price", target);
        else body.add("target_price", com.google.gson.JsonNull.INSTANCE);

        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/product_watches?id=eq." + watchId)
                .patch(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("Erro ao atualizar preço alvo");
        }
    }

    public void updateProductPrice(String token, String productId, double price) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("current_price", price);
        body.addProperty("status", "success");
        body.addProperty("last_updated", Instant.now().toString());

        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/products?id=eq." + productId)
                .patch(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) Log.e(TAG, "updateProductPrice failed: " + r.code());
        }
    }

    // ─── Snapshots ───────────────────────────────────────────────────────────

    public void saveSnapshot(String token, PriceSnapshot snap) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("product_id", snap.getProductId());
        body.addProperty("price", snap.getPrice());
        if (snap.getSourceAccount() != null) body.addProperty("source_account", snap.getSourceAccount());
        if (snap.getTweetExcerpt() != null)  body.addProperty("tweet_excerpt", snap.getTweetExcerpt());
        if (snap.getTweetUrl() != null)      body.addProperty("tweet_url", snap.getTweetUrl());
        if (snap.getTweetDate() != null)     body.addProperty("tweet_date", snap.getTweetDate());

        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/price_snapshots")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) Log.e(TAG, "saveSnapshot failed: " + r.code());
        }
    }

    public List<PriceSnapshot> getSnapshots(String token, String productId) throws IOException {
        return querySnapshots(token, productId, 20);
    }

    public PriceSnapshot getLatestSnapshot(String token, String productId) throws IOException {
        List<PriceSnapshot> list = querySnapshots(token, productId, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<PriceSnapshot> querySnapshots(String token, String productId, int limit) throws IOException {
        String url = baseUrl + "/rest/v1/price_snapshots?product_id=eq." + productId
                + "&order=captured_at.desc&limit=" + limit;
        try (Response r = http.newCall(authGet(token, url)).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) throw new IOException("Erro ao buscar histórico");
            List<PriceSnapshot> list = new ArrayList<>();
            for (JsonElement el : JsonParser.parseString(rb).getAsJsonArray()) {
                list.add(parseSnapshot(el.getAsJsonObject()));
            }
            return list;
        }
    }

    private PriceSnapshot parseSnapshot(JsonObject o) {
        PriceSnapshot s = new PriceSnapshot();
        s.setId(str(o, "id"));
        s.setProductId(str(o, "product_id"));
        if (o.has("price") && !o.get("price").isJsonNull())
            s.setPrice(o.get("price").getAsDouble());
        if (o.has("source_account") && !o.get("source_account").isJsonNull())
            s.setSourceAccount(o.get("source_account").getAsString());
        if (o.has("tweet_excerpt") && !o.get("tweet_excerpt").isJsonNull())
            s.setTweetExcerpt(o.get("tweet_excerpt").getAsString());
        if (o.has("tweet_url") && !o.get("tweet_url").isJsonNull())
            s.setTweetUrl(o.get("tweet_url").getAsString());
        if (o.has("tweet_date") && !o.get("tweet_date").isJsonNull())
            s.setTweetDate(o.get("tweet_date").getAsString());
        if (o.has("captured_at") && !o.get("captured_at").isJsonNull())
            s.setCapturedAt(o.get("captured_at").getAsString());
        return s;
    }

    // ─── Trending ────────────────────────────────────────────────────────────

    public List<Product> getTrending(String token) throws IOException {
        String url = baseUrl + "/rest/v1/products"
                + "?select=id,name,current_price&status=eq.success"
                + "&order=last_updated.desc&limit=10";
        try (Response r = http.newCall(authGet(token, url)).execute()) {
            String rb = r.body().string();
            List<Product> list = new ArrayList<>();
            if (!r.isSuccessful()) return list;
            for (JsonElement el : JsonParser.parseString(rb).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                Product p = new Product();
                p.setId(str(o, "id"));
                p.setName(str(o, "name"));
                if (o.has("current_price") && !o.get("current_price").isJsonNull())
                    p.setCurrentPrice(o.get("current_price").getAsDouble());
                list.add(p);
            }
            return list;
        }
    }

    public void registerDeviceToken(String token, String deviceToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("p_token", deviceToken);
        body.addProperty("p_platform", "android");

        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/rpc/register_device_token")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("Erro ao registrar push token");
        }
    }

    public void deleteDeviceToken(String token, String deviceToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("p_token", deviceToken);

        Request req = new Request.Builder()
                .url(baseUrl + "/rest/v1/rpc/delete_device_token")
                .post(body(body))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("Erro ao remover push token");
        }
    }


    // ─── Product Search (for autocomplete) ───────────────────────────────────

    /**
     * Searches products by name prefix for autocomplete suggestions.
     * Queries the public.products table with ilike filter.
     * Returns up to 8 matches ordered by name.
     */
    public List<String> searchProductNames(String token, String query) throws IOException {
        if (query == null || query.trim().isEmpty()) return new ArrayList<>();
        String encoded = URLEncoder.encode(query.trim() + "%", StandardCharsets.UTF_8.toString());
        String url = baseUrl + "/rest/v1/products"
                + "?select=name"
                + "&normalized_name=ilike." + encoded.toLowerCase()
                + "&order=name.asc"
                + "&limit=8";

        try (Response r = http.newCall(authGet(token, url)).execute()) {
            String rb = r.body().string();
            List<String> names = new ArrayList<>();
            if (!r.isSuccessful()) return names;
            for (JsonElement el : JsonParser.parseString(rb).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("name") && !o.get("name").isJsonNull()) {
                    names.add(o.get("name").getAsString());
                }
            }
            return names;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Request authGet(String token, String url) {
        return new Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + token)
                .build();
    }

    private RequestBody body(JsonObject obj) {
        return RequestBody.create(obj.toString(), JSON_TYPE);
    }

    private String str(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsString();
        return null;
    }

    private String extractError(String json, String fallback) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("error_description")) return o.get("error_description").getAsString();
            if (o.has("msg")) return o.get("msg").getAsString();
            if (o.has("message")) return o.get("message").getAsString();
        } catch (Exception ignored) {}
        return fallback;
    }
}
