package com.owly.pricetracker.services;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.owly.pricetracker.BuildConfig;
import com.owly.pricetracker.models.PriceSnapshot;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class SerperApiService {
    private static final String SERPER_URL = "https://google.serper.dev/search";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_MODEL = "gpt-5-nano";
    private static final String OPENAI_DEAL_VALIDATION_PROMPT_TEMPLATE =
            "You are an AI that evaluates whether a text describes a real deal for a specific product.\n" +
            "\n" +
            "Your task:\n" +
            "Return true if the text is a valid deal for the searched product.\n" +
            "Return false otherwise.\n" +
            "\n" +
            "Strict Rules:\n" +
            "- The text must clearly offer the product for sale (price, discount, payment method, or store).\n" +
            "- The product must match the searched product EXACTLY.\n" +
            "- Accessories, parts, or related items are NOT valid.\n" +
            "  Example: A controller is NOT a PlayStation 5.\n" +
            "- Mentions in news, opinions, speculation, or announcements are NOT valid.\n" +
            "- If the product is only mentioned but not being sold, return FALSE.\n" +
            "- Bundles are valid ONLY if the main product is the searched product.\n" +
            "- Be strict: if there is any doubt, return FALSE.\n" +
            "\n" +
            "Output Rules:\n" +
            "- Return ONLY: TRUE or FALSE\n" +
            "- No explanation, no extra text.\n" +
            "\n" +
            "Searched product:\n" +
            "\"%s\"\n" +
            "\n" +
            "Text:\n" +
            "\"%s\"";
    private static final String OPENAI_PROMPT_TEMPLATE = "You are an information extraction system. Your task is to identify and extract the price from the text of a product deal post from X (Twitter).\n" +
            "\n" +
            "Rules and constraints:\n" +
            "\n" +
            "The input text may contain prices in different formats, including:\n" +
            "\"99,00\" → represents 99.00\n" +
            "\"950\" → represents 950.00\n" +
            "\"1.240\" → represents 1240.00 (dot is a thousands separator, not decimal, when followed by 3 digits)\n" +
            "\"2.340,99\" → represents 2340.99 (dot = thousands separator, comma = decimal separator)\n" +
            "Interpretation rules:\n" +
            "A comma followed by exactly two digits represents decimal places.\n" +
            "A dot followed by exactly three digits represents a thousands separator (not decimal).\n" +
            "If there is no decimal part, assume \".00\".\n" +
            "The text may contain other numbers that are NOT prices — ignore them.\n" +
            "Output format:\n" +
            "Return ONLY the numeric price in standard format using a dot as decimal separator.\n" +
            "The result must be parseable as a Java double.\n" +
            "Do NOT include currency symbols, text, or explanations.\n" +
            "Do NOT include multiple values — return only the most relevant price.\n" +
            "Examples:\n" +
            "Input: \"Apenas R$ 99,00 hoje!\" → Output: 99.00\n" +
            "Input: \"Por só 950 reais\" → Output: 950.00\n" +
            "Input: \"De R$ 1.240 por R$ 999,90\" → Output: 999.90\n" +
            "Input: \"Mega oferta: R$ 2.340,99!!!\" → Output: 2340.99\n" +
            "\n" +
            "Now extract the price from the following text:\n%s";
    private static final String OPENAI_DATE_PARSER_PROMPT_TEMPLATE =
            "You are a date parser.\n" +
            "\n" +
            "Convert the given human-readable date/time expression into an ISO 8601 UTC timestamp in the format:\n" +
            "YYYY-MM-DDTHH:MM:SSZ\n" +
            "\n" +
            "Rules:\n" +
            "1. The input may be in English or Portuguese.\n" +
            "2. Handle both absolute and relative dates.\n" +
            "\n" +
            "RELATIVE DATES:\n" +
            "- Examples: \"7 hours ago\", \"há 4 dias\", \"2 days ago\"\n" +
            "- Use the provided reference datetime as \"now\".\n" +
            "- Subtract the specified amount of time.\n" +
            "- Preserve hours and minutes from the reference datetime unless explicitly overridden.\n" +
            "\n" +
            "ABSOLUTE DATES:\n" +
            "- Examples: \"Mar 25, 2026\", \"30 de mar. de 2026\"\n" +
            "- Convert to YYYY-MM-DD.\n" +
            "- If no time is provided:\n" +
            "  - Default to 00:00:00Z\n" +
            "- If the format implies \"today\" (like localized formats without time), you may keep the reference time.\n" +
            "\n" +
            "LANGUAGE HANDLING:\n" +
            "- English and Portuguese month names and abbreviations must be supported.\n" +
            "  Examples:\n" +
            "  - \"Mar\", \"March\", \"mar.\", \"março\"\n" +
            "- Portuguese relative terms:\n" +
            "  - \"há\" = \"ago\"\n" +
            "  - \"dias\" = \"days\"\n" +
            "  - \"horas\" = \"hours\"\n" +
            "\n" +
            "REFERENCE DATETIME:\n" +
            "%s\n" +
            "\n" +
            "INPUT:\n" +
            "%s\n" +
            "\n" +
            "OUTPUT:\n" +
            "Return ONLY the ISO 8601 UTC timestamp. No explanation.";
    private final String openAiApiKey;
    private String apiKey;
    private final OkHttpClient http;
    private static SerperApiService instance;
    private static final String TAG = "SerperApiService";

    private SerperApiService() {
        http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        openAiApiKey = BuildConfig.OPENAI_API_KEY;
    }

    public static synchronized SerperApiService getInstance() {
        if (instance == null) instance = new SerperApiService();
        return instance;
    }

    public void setApiKey(String key) { this.apiKey = key; }
    public boolean hasApiKey() { return apiKey != null && !apiKey.isEmpty(); }
    public boolean hasOpenAiKey() { return openAiApiKey != null && !openAiApiKey.isEmpty(); }

    /**
     * Twitter-only search via Serper, mirroring the web app's searchTwitterPrices().
     * Query: "from:@xetdaspromocoes <productName>"
     * Filters results to twitter.com / x.com links only.
     * Extracts R$ product prices from snippet text, ignoring coupon/discount values.
     */
    public List<PriceSnapshot> searchTwitterPrices(String productName, String lastUpdated) throws IOException {
        if (!hasApiKey()) throw new IOException("Chave da API Serper não configurada");
        if (!hasOpenAiKey()) throw new IOException("Chave da API OpenAI não configurada");

        JsonObject body = new JsonObject();
        body.addProperty("q",
                "site:x.com (x.com/xetdaspromocoes OR x.com/urubupromo OR x.com/xetimporta) \"" +
                        productName + "\"" +
                        (StringUtils.isBlank(lastUpdated) ? "" : ("since:" + lastUpdated.split("T")[0] + " ")));
        body.addProperty("gl", "br");
        body.addProperty("hl", "pt-br");
        body.addProperty("type", "search");
        body.addProperty("num", 20);
        body.addProperty("engine", "google");

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
            String combined = (title + " " + snippet).trim();
            boolean isValidDeal;
            try {
                isValidDeal = isValidProductDeal(productName, combined);
            } catch (IOException e) {
                Log.e(TAG, "OpenAI deal validation failed", e);
                continue;
            }
            if (!isValidDeal) continue;

            Double price;
            try {
                price = fetchPriceFromOpenAi(combined);
            } catch (IOException e) {
                Log.e(TAG, "OpenAI price extraction failed", e);
                continue;
            }
            if (price == null) continue;

            String sourceAccount = extractTwitterHandle(link);
            String rawDate = item.has("date") && !item.get("date").isJsonNull()
                    ? item.get("date").getAsString().trim()
                    : null;
            String normalizedTweetDate = null;
            if (!StringUtils.isBlank(rawDate)) {
                try {
                    normalizedTweetDate = fetchTweetDateFromOpenAi(rawDate);
                } catch (IOException e) {
                    Log.e(TAG, "OpenAI date parsing failed", e);
                }
            }

            PriceSnapshot s = new PriceSnapshot();
            s.setPrice(price);
            s.setSourceAccount(sourceAccount);
            s.setTweetExcerpt(snippet.length() > 280 ? snippet.substring(0, 280) + "…" : snippet);
            s.setTweetUrl(link);
            s.setTweetDate(normalizedTweetDate);
            results.add(s);
        }

        return results;
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

    private Double fetchPriceFromOpenAi(String text) throws IOException {
        if (text == null || text.isBlank()) return null;
        String prompt = String.format(Locale.US, OPENAI_PROMPT_TEMPLATE, text.trim());
        String content = fetchOpenAiContent(prompt);
        return content != null ? Double.parseDouble(content) : null;
    }

    private boolean isValidProductDeal(String productName, String postText) throws IOException {
        if (productName == null || productName.isBlank() || postText == null || postText.isBlank()) {
            return false;
        }
        String prompt = String.format(
                Locale.US,
                OPENAI_DEAL_VALIDATION_PROMPT_TEMPLATE,
                productName.trim(),
                postText.trim()
        );
        String content = fetchOpenAiContent(prompt);
        return content != null && "TRUE".equalsIgnoreCase(content.trim());
    }

    private String fetchTweetDateFromOpenAi(String dateText) throws IOException {
        if (dateText == null || dateText.isBlank()) return null;
        String prompt = String.format(
                Locale.US,
                OPENAI_DATE_PARSER_PROMPT_TEMPLATE,
                Instant.now().toString(),
                dateText.trim()
        );
        String content = fetchOpenAiContent(prompt);
        return content != null ? content.trim() : null;
    }

    private String fetchOpenAiContent(String prompt) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", OPENAI_MODEL);
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);
        payload.add("messages", messages);

        Request req = new Request.Builder()
                .url(OPENAI_URL)
                .post(RequestBody.create(payload.toString(), JSON))
                .addHeader("Authorization", "Bearer " + openAiApiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = http.newCall(req).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful())
                throw new IOException("OpenAI error: " + response.code() + " " + body);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = json.has("choices") ? json.getAsJsonArray("choices") : null;
            if (choices == null || choices.size() == 0) return null;
            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject messageObj = first.has("message") ? first.getAsJsonObject("message") : null;
            return messageObj != null && messageObj.has("content")
                    ? messageObj.get("content").getAsString() : null;
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
