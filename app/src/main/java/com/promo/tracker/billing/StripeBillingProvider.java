package com.promo.tracker.billing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.promo.tracker.BuildConfig;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class StripeBillingProvider implements BillingProvider {
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient();

    @Override
    public String createCheckoutUrl(String userToken, String userId) throws IOException {
        return callEdgeFunction("create-checkout-session", userToken, userId,
                "Erro ao iniciar pagamento", "URL de pagamento indisponível");
    }

    @Override
    public String createPortalUrl(String userToken, String userId) throws IOException {
        return callEdgeFunction("create-portal-session", userToken, userId,
                "Erro ao abrir portal", "URL do portal indisponível");
    }

    private String callEdgeFunction(String function, String userToken, String userId,
                                    String errorMsg, String noUrlMsg) throws IOException {
        String url = BuildConfig.SUPABASE_URL + "/functions/v1/" + function;

        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer " + userToken)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response r = http.newCall(req).execute()) {
            String rb = r.body().string();
            if (!r.isSuccessful()) {
                throw new IOException(errorMsg);
            }
            JsonObject json = JsonParser.parseString(rb).getAsJsonObject();
            if (!json.has("url") || json.get("url").isJsonNull()) {
                throw new IOException(noUrlMsg);
            }
            return json.get("url").getAsString();
        }
    }
}
