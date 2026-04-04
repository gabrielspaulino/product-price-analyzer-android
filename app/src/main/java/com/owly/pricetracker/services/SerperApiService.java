package com.owly.pricetracker.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.owly.pricetracker.models.PriceSnapshot;

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

    // Matches "R$ 2.499,00" or "R$2499,00" or "R$ 2.499" — captures only the numeric part
    // Uses a lookahead so we don't consume what's after the number
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "R\\$\\s*([\\d]+(?:[.,][\\d]+)*)",
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
     * Twitter-only search via Serper, exactly mirroring the web app's searchTwitterPrices().
     * Query: "from:@xetdaspromocoes <productName>"
     * Filters results to twitter.com / x.com links only.
     * Extracts R$ prices from snippet text.
     */
    public List<PriceSnapshot> searchTwitterPrices(String productName) throws IOException {
        if (!hasApiKey()) throw new IOException("Chave da API Serper não configurada");

        JsonObject body = new JsonObject();
        body.addProperty("q", "from:@xetdaspromocoes " + productName);
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

            Double price = extractPriceFromText(combined);
            if (price == null) continue;

            // Extract the source account from the URL (e.g. @xetdaspromocoes)
            String sourceAccount = extractTwitterHandle(link);

            PriceSnapshot s = new PriceSnapshot();
            s.setPrice(price);
            s.setSourceAccount(sourceAccount);
            // Use snippet as excerpt (up to 280 chars like a tweet)
            s.setTweetExcerpt(snippet.length() > 280 ? snippet.substring(0, 280) + "…" : snippet);
            s.setTweetUrl(link);
            results.add(s);
        }

        return results;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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

    /**
     * Extracts the lowest R$ price from text.
     * Handles Brazilian number format:
     *   "R$ 2.499,00" → 2499.00   (dot = thousands separator, comma = decimal)
     *   "R$ 2499,00"  → 2499.00
     *   "R$ 249,90"   → 249.90
     *   "R$ 2.06"     → ignored (dot can't be thousands when only 2 digits follow — this was a bug)
     *
     * Rule (matching web app api.js logic):
     * - If the string has both dot AND comma → dot is thousands, comma is decimal
     *   e.g. "2.499,00" → remove dots → "2499,00" → replace comma → "2499.00"
     * - If only comma → comma is decimal  e.g. "2499,00" → "2499.00"
     * - If only dot  → dot is thousands separator IFF there are exactly 3 digits after it
     *   (e.g. "2.499" → 2499),  otherwise dot is decimal (e.g. "2.5" → 2.5)
     */
    private Double parseBrazilianPrice(String numStr) {
        if (numStr == null || numStr.isEmpty()) return null;
        numStr = numStr.trim();

        double value;
        if (numStr.contains(",") && numStr.contains(".")) {
            // Both separators: dot=thousands, comma=decimal  e.g. "2.499,00"
            numStr = numStr.replace(".", "").replace(",", ".");
        } else if (numStr.contains(",")) {
            // Only comma: comma=decimal  e.g. "2499,90"
            numStr = numStr.replace(",", ".");
        } else if (numStr.contains(".")) {
            // Only dot: determine if thousands or decimal
            int dotIdx = numStr.lastIndexOf('.');
            String afterDot = numStr.substring(dotIdx + 1);
            if (afterDot.length() == 3) {
                // Thousands separator: "2.499" → 2499
                numStr = numStr.replace(".", "");
            }
            // else decimal: leave as-is e.g. "2.50"
        }

        try {
            value = Double.parseDouble(numStr);
        } catch (NumberFormatException e) {
            return null;
        }

        // Sanity check: prices should be between R$1 and R$999.999
        return (value >= 1.0 && value < 1_000_000.0) ? value : null;
    }

    private Double extractPriceFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = PRICE_PATTERN.matcher(text);
        Double lowestPrice = null;
        while (m.find()) {
            String numStr = m.group(1); // e.g. "2.499,00" or "2499,00"
            Double price = parseBrazilianPrice(numStr);
            if (price != null && (lowestPrice == null || price < lowestPrice)) {
                lowestPrice = price;
            }
        }
        return lowestPrice;
    }

    private String extractTwitterHandle(String url) {
        // e.g. https://twitter.com/xetdaspromocoes/status/123 → @xetdaspromocoes
        try {
            String[] parts = url.split("/");
            for (int i = 0; i < parts.length; i++) {
                if ((parts[i].equals("twitter.com") || parts[i].equals("x.com"))
                        && i + 1 < parts.length && !parts[i+1].isEmpty()) {
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
        // Use US locale to get consistent number formatting then convert
        String usFormatted = String.format(Locale.US, "%,.2f", price);
        // US format: 2,499.00 → convert to BR: 2.499,00
        return "R$ " + usFormatted.replace(",", "X").replace(".", ",").replace("X", ".");
    }
}
