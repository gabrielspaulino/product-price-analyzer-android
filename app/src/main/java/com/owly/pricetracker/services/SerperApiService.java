package com.owly.pricetracker.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.owly.pricetracker.models.PriceSnapshot;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SerperApiService {
    private static final String SERPER_URL = "https://google.serper.dev/search";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Matches "R$" followed by a number in Brazilian format.
     * Captures the full numeric string including dots/commas.
     * Examples matched: "R$ 5.899", "R$ 5.899,00", "R$ 249,90", "R$1299"
     */
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "R\\$\\s*([\\d]+(?:[.,][\\d]+)*)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Context words that indicate the number after R$ is NOT the product price
     * but rather a discount, coupon value, cashback, etc.
     * Based on the web app's api.js filtering logic.
     */
    private static final Pattern COUPON_CONTEXT_PATTERN = Pattern.compile(
            "(?:cupom|cupon|desconto|cashback|volta|off|de desconto|frete|taxa)\\s+(?:de\\s+)?R\\$",
            Pattern.CASE_INSENSITIVE
    );

    private String apiKey;
    private final OkHttpClient http;
    private static SerperApiService instance;

    private SerperApiService() {
        http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized SerperApiService getInstance() {
        if (instance == null) instance = new SerperApiService();
        return instance;
    }

    public void setApiKey(String key) { this.apiKey = key; }
    public boolean hasApiKey() { return apiKey != null && !apiKey.isEmpty(); }

    /**
     * Twitter-only search via Serper, mirroring the web app's searchTwitterPrices().
     * Query: "from:@xetdaspromocoes <productName>"
     * Filters results to twitter.com / x.com links only.
     * Extracts R$ product prices from snippet text, ignoring coupon/discount values.
     */
    public List<PriceSnapshot> searchTwitterPrices(String productName, String lastUpdated) throws IOException {
        if (!hasApiKey()) throw new IOException("Chave da API Serper não configurada");

        JsonObject body = new JsonObject();
        body.addProperty("q", "from:@xetdaspromocoes " + productName +
                (StringUtils.isBlank(lastUpdated) ? "" : (" since:" + lastUpdated.split(" ")[0])));
        body.addProperty("gl", "br");
        body.addProperty("hl", "pt-br");
        body.addProperty("num", 10);

        List<PriceSnapshot> results = new ArrayList<>();
        JsonObject json = post(body);

        if (!json.has("organic")) return results;

        for (JsonElement el : json.getAsJsonArray("organic")) {
            JsonObject item = el.getAsJsonObject();
            String link = item.has("link") ? item.get("link").getAsString() : "";

            // Only process Twitter / X links
            if (!link.contains("twitter.com") && !link.contains("x.com")) continue;

            String snippet = item.has("snippet") ? item.get("snippet").getAsString() : "";
            String title   = item.has("title")   ? item.get("title").getAsString()   : "";
            String combined = title + " " + snippet;

            Double price = extractProductPrice(combined);
            if (price == null) continue;

            String sourceAccount = extractTwitterHandle(link);

            PriceSnapshot s = new PriceSnapshot();
            s.setPrice(price);
            s.setSourceAccount(sourceAccount);
            s.setTweetExcerpt(snippet.length() > 280 ? snippet.substring(0, 280) + "…" : snippet);
            s.setTweetUrl(link);
            results.add(s);
        }

        return results;
    }

    // ── Price extraction ──────────────────────────────────────────────────────

    /**
     * Extracts the product price from tweet text.
     *
     * Key rules (matching web app api.js logic):
     *
     * 1. DOT is ALWAYS a thousands separator in Brazilian prices, NEVER decimal.
     *    "R$ 5.348"  → 5348   (NOT 5.35)
     *    "R$ 5.899"  → 5899
     *    "R$ 1.299,90" → 1299.90
     *
     * 2. COMMA is always the decimal separator.
     *    "R$ 249,90" → 249.90
     *    "R$ 5.899,00" → 5899.00
     *
     * 3. Skip prices that are preceded by coupon/discount context words
     *    ("cupom de R$ 90", "R$ 90 OFF", "desconto de R$ 50", etc.)
     *    because those are discount amounts, not product prices.
     *
     * 4. Among all valid prices found, pick the LARGEST one (most likely the
     *    actual product price, not a partial/coupon value). The web app uses
     *    the first price found, but filtering coupons first and then taking
     *    the highest remaining value is more robust.
     */
    private Double extractProductPrice(String text) {
        if (text == null || text.isEmpty()) return null;

        // First, blank out coupon/discount contexts to avoid matching them
        // e.g. "cupom de R$ 90" → "cupom de XXXXX" so the R$90 won't be matched
        String cleaned = COUPON_CONTEXT_PATTERN.matcher(text).replaceAll(Matcher.quoteReplacement("DESCONTO_REMOVIDO"));

        // Also remove "R$ XX OFF" patterns (e.g. "R$ 90 OFF")
        cleaned = cleaned.replaceAll("R\\$\\s*[\\d.,]+\\s+(?:off|OFF|Off)", "DESCONTO_OFF");

        Matcher m = PRICE_PATTERN.matcher(cleaned);
        List<Double> candidates = new ArrayList<>();

        while (m.find()) {
            String numStr = m.group(1);
            Double price = parseBrazilianPrice(numStr);
            if (price != null) candidates.add(price);
        }

        if (candidates.isEmpty()) return null;

        // Return the highest price found — in a promo tweet the product price
        // is typically the largest number (coupon values are smaller)
        Double highest = null;
        for (Double p : candidates) {
            if (highest == null || p > highest) highest = p;
        }
        return highest;
    }

    /**
     * Converts a Brazilian-format number string to a Java double.
     *
     * Rules:
     * - Dots are ALWAYS thousands separators → strip them
     * - Commas are ALWAYS decimal separators → replace with dot
     *
     * Examples:
     *   "5.348"    → 5348.0
     *   "5.899,00" → 5899.0
     *   "249,90"   → 249.9
     *   "1299"     → 1299.0
     */
    private Double parseBrazilianPrice(String numStr) {
        if (numStr == null || numStr.isEmpty()) return null;
        numStr = numStr.trim();

        // Remove all dots (thousands separators)
        numStr = numStr.replace(".", "");

        // Replace comma with dot (decimal separator)
        numStr = numStr.replace(",", ".");

        double value;
        try {
            value = Double.parseDouble(numStr);
        } catch (NumberFormatException e) {
            return null;
        }

        // Sanity check: valid product prices are between R$ 1 and R$ 999.999
        return (value >= 1.0 && value < 1_000_000.0) ? value : null;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private JsonObject post(JsonObject body) throws IOException {
        Request req = new Request.Builder()
                .url(SERPER_URL)
                .post(RequestBody.create(body.toString(), JSON))
                .addHeader("X-API-KEY", apiKey)
                .addHeader("Content-Type", "application/json")
                .build();
        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) throw new IOException("Serper error: " + r.code());
            return JsonParser.parseString(rb).getAsJsonObject();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractTwitterHandle(String url) {
        try {
            String[] parts = url.split("/");
            for (int i = 0; i < parts.length; i++) {
                if ((parts[i].equals("twitter.com") || parts[i].equals("x.com"))
                        && i + 1 < parts.length && !parts[i + 1].isEmpty()
                        && !parts[i + 1].equals("status")) {
                    return "@" + parts[i + 1];
                }
            }
        } catch (Exception ignored) {}
        return "@xetdaspromocoes";
    }

    /**
     * Formats a price in Brazilian Real format: R$ 2.499,00
     */
    public static String formatPrice(double price) {
        String usFormatted = String.format(Locale.US, "%,.2f", price);
        // "2,499.00" → "2.499,00"
        return "R$ " + usFormatted.replace(",", "X").replace(".", ",").replace("X", ".");
    }
}