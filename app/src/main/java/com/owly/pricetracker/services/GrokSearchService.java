package com.owly.pricetracker.services;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.owly.pricetracker.BuildConfig;
import com.owly.pricetracker.models.PriceSnapshot;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    private static final String GROK_URL = "https://api.x.ai/v1/responses";
    private static final String GROK_MODEL = "grok-4.20-reasoning";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String[] X_HANDLES = {"xetdaspromocoes", "urubupromo", "xetimporta"};

    private static final String SEARCH_PROMPT_TEMPLATE =
            "Search X for posts mentioning \"%s\" from @xetdaspromocoes, @urubupromo, " +
            "or @xetimporta since %s.\n\n" +
            "Keep ONLY genuine sales deals with clear price for the exact product.\n" +
            "Convert Brazilian prices (R$ xx,xx) to decimal number (e.g. 90.86).\n\n" +
            "Output **ONLY** the raw JSON. No explanations, no extra text.\n\n" +
            "Use this exact structure:\n" +
            "{\n" +
            "  \"deals\": [\n" +
            "    {\n" +
            "      \"url\": \"full post url\",\n" +
            "      \"price\": number or null,\n" +
            "      \"posted_at\": \"2026-04-17T13:33:30Z\" or null,\n" +
            "      \"author\": \"username without @\" or null,\n" +
            "      \"text\": \"full original post text\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private final String apiKey;
    private final OkHttpClient http;
    private static GrokSearchService instance;

    private GrokSearchService() {
        http = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        apiKey = BuildConfig.GROK_API_KEY;
    }

    public static synchronized GrokSearchService getInstance() {
        if (instance == null) instance = new GrokSearchService();
        return instance;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    public List<PriceSnapshot> searchTwitterPrices(String productName, String lastUpdated) throws IOException {
        if (!hasApiKey()) throw new IOException("Chave da API Grok não configurada");

        String fromDate = resolveFromDate(lastUpdated);
        String prompt = String.format(Locale.US, SEARCH_PROMPT_TEMPLATE,
                productName, fromDate);

        JsonObject payload = buildPayload(prompt, fromDate);
        Log.d(TAG, "Grok request for: " + productName + " since " + fromDate);

        JsonObject responseJson = callGrokApi(payload);
        validateResponse(responseJson);

        JsonArray deals = parseDeals(responseJson);
        Log.d(TAG, "Parsed " + deals.size() + " deals from Grok response");

        return buildSnapshots(deals);
    }

    private String resolveFromDate(String lastUpdated) {
        if (lastUpdated != null && !lastUpdated.isBlank()) {
            return lastUpdated.split("T")[0];
        }
        return LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private JsonObject buildPayload(String prompt, String fromDate) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", GROK_MODEL);

        // input
        JsonArray input = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        input.add(message);
        payload.add("input", input);

        // tools
        JsonArray tools = new JsonArray();
        JsonObject xSearch = new JsonObject();
        xSearch.addProperty("type", "x_search");
        JsonArray handles = new JsonArray();
        for (String handle : X_HANDLES) handles.add(handle);
        xSearch.add("allowed_x_handles", handles);
        if (fromDate != null) xSearch.addProperty("from_date", fromDate);
        tools.add(xSearch);
        payload.add("tools", tools);

        // response_format — json_schema
        payload.add("response_format", buildResponseFormat());

        payload.addProperty("temperature", 0.0);
        payload.addProperty("max_output_tokens", 8192);

        return payload;
    }

    private JsonObject buildResponseFormat() {
        JsonObject urlProp = new JsonObject();
        urlProp.addProperty("type", "string");

        JsonArray priceType = new JsonArray();
        priceType.add("number");
        priceType.add("null");
        JsonObject priceProp = new JsonObject();
        priceProp.add("type", priceType);

        JsonArray postedAtType = new JsonArray();
        postedAtType.add("string");
        postedAtType.add("null");
        JsonObject postedAtProp = new JsonObject();
        postedAtProp.add("type", postedAtType);

        JsonArray authorType = new JsonArray();
        authorType.add("string");
        authorType.add("null");
        JsonObject authorProp = new JsonObject();
        authorProp.add("type", authorType);

        JsonArray textType = new JsonArray();
        textType.add("string");
        textType.add("null");
        JsonObject textProp = new JsonObject();
        textProp.add("type", textType);

        JsonObject itemProperties = new JsonObject();
        itemProperties.add("url", urlProp);
        itemProperties.add("price", priceProp);
        itemProperties.add("posted_at", postedAtProp);
        itemProperties.add("author", authorProp);
        itemProperties.add("text", textProp);

        JsonArray itemRequired = new JsonArray();
        itemRequired.add("url");
        itemRequired.add("price");
        itemRequired.add("text");

        JsonObject itemSchema = new JsonObject();
        itemSchema.addProperty("type", "object");
        itemSchema.add("properties", itemProperties);
        itemSchema.add("required", itemRequired);
        itemSchema.addProperty("additionalProperties", false);

        // deals array
        JsonObject dealsProp = new JsonObject();
        dealsProp.addProperty("type", "array");
        dealsProp.add("items", itemSchema);

        JsonObject rootProperties = new JsonObject();
        rootProperties.add("deals", dealsProp);

        JsonArray rootRequired = new JsonArray();
        rootRequired.add("deals");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", rootProperties);
        schema.add("required", rootRequired);
        schema.addProperty("additionalProperties", false);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "deals_response");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);

        return responseFormat;
    }

    private JsonObject callGrokApi(JsonObject payload) throws IOException {
        Request req = new Request.Builder()
                .url(GROK_URL)
                .post(RequestBody.create(payload.toString(), JSON_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = http.newCall(req).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Grok API HTTP " + response.code() + ", body length: " + body.length());
            if (!response.isSuccessful()) {
                throw new IOException("Grok API error: " + response.code() + " " + body);
            }
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private void validateResponse(JsonObject responseJson) throws IOException {
        String status = responseJson.has("status")
                ? responseJson.get("status").getAsString() : null;

        if (responseJson.has("error") && !responseJson.get("error").isJsonNull()) {
            String error = responseJson.get("error").toString();
            throw new IOException("Grok API returned error: " + error);
        }

        if (!"completed".equals(status)) {
            String details = responseJson.has("incomplete_details")
                    && !responseJson.get("incomplete_details").isJsonNull()
                    ? responseJson.get("incomplete_details").toString() : "unknown reason";
            throw new IOException("Grok response not completed (status=" + status + "): " + details);
        }
    }

    private JsonArray parseDeals(JsonObject responseJson) {
        String text = extractTextFromOutput(responseJson);
        try {
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
            return parsed.has("deals") ? parsed.getAsJsonArray("deals") : new JsonArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse structured response: " + text, e);
            return new JsonArray();
        }
    }

    private String extractTextFromOutput(JsonObject responseJson) {
        JsonArray output = responseJson.has("output")
                ? responseJson.getAsJsonArray("output") : null;
        if (output == null) return "{\"deals\":[]}";

        StringBuilder sb = new StringBuilder();
        for (JsonElement item : output) {
            JsonObject obj = item.getAsJsonObject();
            String type = getStringOrNull(obj, "type");
            if (!"message".equals(type)) continue;

            JsonArray content = obj.has("content") ? obj.getAsJsonArray("content") : null;
            if (content == null) continue;

            for (JsonElement contentItem : content) {
                JsonObject co = contentItem.getAsJsonObject();
                if ("output_text".equals(getStringOrNull(co, "type")) && co.has("text")) {
                    sb.append(co.get("text").getAsString());
                }
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "{\"deals\":[]}" : result;
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
