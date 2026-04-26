package com.promo.tracker.services;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.promo.tracker.BuildConfig;
import com.promo.tracker.models.PriceSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GrokSearchService {
    private static final String TAG = "GrokSearchService";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private static GrokSearchService instance;

    private GrokSearchService() {
        http = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized GrokSearchService getInstance() {
        if (instance == null) instance = new GrokSearchService();
        return instance;
    }

    public List<PriceSnapshot> searchTwitterPrices(String productName, String lastUpdated, String accessToken) throws IOException {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IOException("Sessão expirada. Faça login novamente.");
        }

        JsonObject body = new JsonObject();
        body.addProperty("product_name", productName);
        if (lastUpdated != null && !lastUpdated.isBlank()) {
            body.addProperty("last_updated", lastUpdated);
        }

        String url = BuildConfig.SUPABASE_URL + "/functions/v1/search-deals";
        Log.d(TAG, "Calling search-deals for: " + productName);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

        try (Response response = http.newCall(req).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "search-deals HTTP " + response.code() + ", body length: " + responseBody.length());

            if (!response.isSuccessful()) {
                throw new IOException("Search error: " + response.code() + " " + responseBody);
            }

            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray deals = json.has("deals") ? json.getAsJsonArray("deals") : new JsonArray();
            Log.d(TAG, "Parsed " + deals.size() + " deals");

            return buildSnapshots(deals);
        }
    }

    private List<PriceSnapshot> buildSnapshots(JsonArray deals) {
        List<PriceSnapshot> results = new ArrayList<>();

        for (JsonElement el : deals) {
            JsonObject deal = el.getAsJsonObject();

            String dealUrl = getStringOrNull(deal, "url");
            if (dealUrl == null) continue;

            if (!deal.has("price") || deal.get("price").isJsonNull()) continue;
            double price;
            try {
                price = deal.get("price").getAsDouble();
            } catch (Exception e) {
                Log.w(TAG, "Skipping deal with invalid price", e);
                continue;
            }

            PriceSnapshot s = new PriceSnapshot();
            s.setPrice(price);
            s.setTweetUrl(dealUrl);

            String author = getStringOrNull(deal, "author");
            s.setSourceAccount(author != null ? "@" + author : null);
            s.setTweetDate(getStringOrNull(deal, "posted_at"));

            String text = getStringOrNull(deal, "text");
            if (text != null && text.length() > 280) {
                text = text.substring(0, 280) + "…";
            }
            s.setTweetExcerpt(text);

            results.add(s);
        }

        return results;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    public static String formatPrice(double price) {
        String usFormatted = String.format(Locale.US, "%,.2f", price);
        return "R$ " + usFormatted.replace(",", "X").replace(".", ",").replace("X", ".");
    }
}
