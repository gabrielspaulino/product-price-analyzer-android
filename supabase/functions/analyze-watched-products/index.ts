import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const CRON_SECRET = Deno.env.get("CRON_SECRET") ?? "";
const FIREBASE_SERVICE_ACCOUNT_JSON = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";

const JSON_HEADERS = { "Content-Type": "application/json" };
const MAX_CONCURRENCY = 2;
const WALL_CLOCK_LIMIT_MS = 120_000;
const FETCH_TIMEOUT_MS = 30_000;

type AnalysisTarget = {
  product_id: string;
  product_name: string;
  last_updated: string | null;
};

type PriceSnapshot = {
  product_id: string;
  price: number;
  source_account: string | null;
  tweet_excerpt: string | null;
  tweet_url: string | null;
  tweet_date: string | null;
};

type ProductResult = {
  productId: string;
  snapshotsSaved: number;
  notificationsSent: number;
  lowestPrice: number | null;
  status: "success" | "skipped" | "error";
  error?: string;
};

type NotificationTarget = {
  device_token_id: string;
  device_token: string;
  user_id: string;
  watch_id: string;
  target_price: number | null;
};

if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
  throw new Error("Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY");
}

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);
let cachedFcmAccessToken: string | null = null;

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  if (!CRON_SECRET || req.headers.get("x-cron-secret") !== CRON_SECRET) {
    return json({ error: "Unauthorized" }, 401);
  }

  if (!FIREBASE_SERVICE_ACCOUNT_JSON) {
    return json({ error: "Missing FIREBASE_SERVICE_ACCOUNT_JSON secret" }, 500);
  }

  const startedAt = Date.now();
  console.log("[CRON] Starting analysis...");
  const targets = await getTargets();
  console.log(`[CRON] Found ${targets.length} targets:`, targets.map(t => t.product_name));
  const results = await processInBatches(
    targets,
    MAX_CONCURRENCY,
    (t) => analyzeTarget(t),
    startedAt,
  );

  const summary = {
    processed: results.length,
    total_targets: targets.length,
    succeeded: results.filter((item) => item.status === "success").length,
    skipped: results.filter((item) => item.status === "skipped").length,
    failed: results.filter((item) => item.status === "error").length,
    timed_out: results.length < targets.length,
    duration_ms: Date.now() - startedAt,
    results,
  };

  return json(summary);
});

async function getTargets(): Promise<AnalysisTarget[]> {
  const { data: watches, error: watchError } = await supabase
    .from("product_watches")
    .select("product_id")
    .eq("status", "active");
  if (watchError) throw watchError;
  console.log(`[TARGETS] Found ${watches?.length ?? 0} active watches`);
  if (!watches || watches.length === 0) return [];

  const productIds = [...new Set(watches.map((w: { product_id: string }) => w.product_id))];

  const { data: products, error: productError } = await supabase
    .from("products")
    .select("id, name, last_updated")
    .in("id", productIds);
  if (productError) throw productError;

  console.log(`[TARGETS] Found ${products?.length ?? 0} products`);
  return (products ?? []).map((p) => ({
    product_id: p.id,
    product_name: p.name,
    last_updated: p.last_updated,
  }));
}

async function analyzeTarget(target: AnalysisTarget): Promise<ProductResult> {
  console.log(`[ANALYZE] Starting: "${target.product_name}" (last_updated: ${target.last_updated})`);
  try {
    const snapshots = await searchDeals(target.product_name, target.last_updated);

    if (snapshots.length === 0) {
      console.log(`[ANALYZE] No snapshots found for "${target.product_name}"`);
      const { error } = await supabase
        .from("products")
        .update({ last_updated: new Date().toISOString() })
        .eq("id", target.product_id);
      if (error) throw error;
      return {
        productId: target.product_id,
        snapshotsSaved: 0,
        notificationsSent: 0,
        lowestPrice: null,
        status: "skipped",
      };
    }

    let lowestPrice = Number.POSITIVE_INFINITY;
    let notificationsSent = 0;
    for (const snapshot of snapshots) {
      snapshot.product_id = target.product_id;
      const { error } = await supabase.from("price_snapshots")
        .upsert(snapshot, { onConflict: "product_id,tweet_url", ignoreDuplicates: true });
      if (error) throw error;
      try {
        notificationsSent += await sendNotificationsForSnapshot(target, snapshot);
      } catch (notifyError) {
        console.error(`[NOTIFY] Failed to send notification for "${target.product_name}":`, notifyError);
      }
      lowestPrice = Math.min(lowestPrice, snapshot.price);
    }

    const updatePayload: Record<string, unknown> = {
      last_updated: new Date().toISOString(),
    };
    if (Number.isFinite(lowestPrice)) {
      updatePayload.current_price = lowestPrice;
      updatePayload.status = "success";
    }
    const { error } = await supabase
      .from("products")
      .update(updatePayload)
      .eq("id", target.product_id);
    if (error) throw error;

    return {
      productId: target.product_id,
      snapshotsSaved: snapshots.length,
      notificationsSent,
      lowestPrice: Number.isFinite(lowestPrice) ? lowestPrice : null,
      status: "success",
    };
  } catch (error) {
    console.error("Failed to analyze product", target.product_id, error);
    return {
      productId: target.product_id,
      snapshotsSaved: 0,
      notificationsSent: 0,
      lowestPrice: null,
      status: "error",
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

async function searchDeals(
  productName: string,
  lastUpdated: string | null,
): Promise<PriceSnapshot[]> {
  const body: Record<string, string> = { product_name: productName };
  if (lastUpdated) body.last_updated = lastUpdated;

  const response = await fetchWithTimeout(`${SUPABASE_URL}/functions/v1/search-deals`, {
    method: "POST",
    headers: {
      ...JSON_HEADERS,
      Authorization: `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`search-deals error: ${response.status} ${errText}`);
  }

  const result = await response.json();
  const deals = Array.isArray(result.deals) ? result.deals : [];
  console.log(`[SEARCH] "${productName}" returned ${deals.length} deals`);

  const snapshots: PriceSnapshot[] = [];
  for (const deal of deals) {
    if (!deal.url || deal.price == null) continue;
    snapshots.push({
      product_id: "",
      price: deal.price,
      source_account: deal.author ? `@${deal.author}` : null,
      tweet_excerpt: deal.text ? String(deal.text).slice(0, 280) : null,
      tweet_url: deal.url,
      tweet_date: deal.posted_at ?? null,
    });
  }
  return snapshots;
}

async function sendNotificationsForSnapshot(target: AnalysisTarget, snapshot: PriceSnapshot): Promise<number> {
  const dedupeKey = snapshot.tweet_url?.trim() || `${target.product_id}:${snapshot.price}:${snapshot.tweet_date ?? "no-date"}`;
  const notificationTargets = await getNotificationTargets(target.product_id, snapshot.price);
  console.log(`[NOTIFY] "${target.product_name}" price=${snapshot.price}: ${notificationTargets.length} notification target(s)`);
  if (notificationTargets.length === 0) return 0;

  let sentCount = 0;
  for (const notificationTarget of notificationTargets) {
    const claimed = await claimNotificationDelivery(notificationTarget.device_token_id, target.product_id, dedupeKey);
    if (!claimed) {
      console.log(`[NOTIFY] Skipped (already delivered) for device ${notificationTarget.device_token_id}`);
      continue;
    }

    try {
      await sendFcmMessage(notificationTarget, target, snapshot);
      console.log(`[NOTIFY] FCM sent successfully to device ${notificationTarget.device_token_id}`);
      sentCount += 1;
    } catch (error) {
      console.error(`[NOTIFY] FCM FAILED for device ${notificationTarget.device_token_id}:`, error);
    }
  }

  return sentCount;
}

async function getNotificationTargets(productId: string, price: number): Promise<NotificationTarget[]> {
  const { data: watches, error: watchError } = await supabase
    .from("product_watches")
    .select("id, user_id, target_price")
    .eq("product_id", productId)
    .eq("status", "active");
  if (watchError) throw watchError;
  console.log(`[NOTIFY] Watches for product ${productId}: ${watches?.length ?? 0}, target_prices: ${watches?.map((w: { target_price: number | null }) => w.target_price)}`);
  if (!watches || watches.length === 0) return [];

  const eligibleWatches = watches.filter(
    (w: { target_price: number | null }) => w.target_price == null || price <= w.target_price,
  );
  console.log(`[NOTIFY] Eligible watches (price=${price}): ${eligibleWatches.length}`);
  if (eligibleWatches.length === 0) return [];

  const userIds = eligibleWatches.map((w: { user_id: string }) => w.user_id);
  const { data: tokens, error: tokenError } = await supabase
    .from("device_tokens")
    .select("id, user_id, token")
    .in("user_id", userIds);
  if (tokenError) throw tokenError;
  console.log(`[NOTIFY] Device tokens found: ${tokens?.length ?? 0}`);
  if (!tokens || tokens.length === 0) return [];

  const watchByUser = new Map<string, { watch_id: string; target_price: number | null }>();
  for (const w of eligibleWatches) {
    watchByUser.set(w.user_id, { watch_id: w.id, target_price: w.target_price });
  }

  const results: NotificationTarget[] = [];
  for (const t of tokens) {
    const watch = watchByUser.get(t.user_id);
    if (!watch) continue;
    results.push({
      device_token_id: t.id,
      device_token: t.token,
      user_id: t.user_id,
      watch_id: watch.watch_id,
      target_price: watch.target_price,
    });
  }
  return results;
}

async function claimNotificationDelivery(
  deviceTokenId: string,
  productId: string,
  dedupeKey: string,
): Promise<boolean> {
  const { error } = await supabase
    .from("notification_deliveries")
    .insert({
      device_token_id: deviceTokenId,
      product_id: productId,
      dedupe_key: dedupeKey,
    });

  if (!error) return true;
  if (String(error.code) === "23505") return false;
  throw error;
}

async function sendFcmMessage(
  notificationTarget: NotificationTarget,
  target: AnalysisTarget,
  snapshot: PriceSnapshot,
): Promise<void> {
  const serviceAccount = JSON.parse(FIREBASE_SERVICE_ACCOUNT_JSON);
  if (!cachedFcmAccessToken) {
    cachedFcmAccessToken = await getGoogleAccessToken(serviceAccount);
  }
  const accessToken = cachedFcmAccessToken;
  const response = await fetchWithTimeout(
    `https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        ...JSON_HEADERS,
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({
        message: {
          token: notificationTarget.device_token,
          notification: {
            title: `${target.product_name} em promoção!`,
            body: `${formatPriceBrl(snapshot.price)} encontrado por ${snapshot.source_account ?? "promoção"}`,
          },
          android: {
            priority: "high",
            notification: {
              channel_id: "promo_sales",
            },
          },
          data: {
            product_id: target.product_id,
            product_name: target.product_name,
            watch_id: notificationTarget.watch_id,
            price: String(snapshot.price),
            target_price: notificationTarget.target_price != null ? String(notificationTarget.target_price) : "",
            last_updated: new Date().toISOString(),
            source_account: snapshot.source_account ?? "",
          },
        },
      }),
    },
  );

  if (!response.ok) {
    const errorBody = await response.text();
    if (isUnregisteredFcmToken(errorBody)) {
      await deleteDeviceTokenById(notificationTarget.device_token_id);
    }
    throw new Error(`FCM error: ${response.status} ${errorBody}`);
  }
}

async function deleteDeviceTokenById(deviceTokenId: string): Promise<void> {
  const { error } = await supabase
    .from("device_tokens")
    .delete()
    .eq("id", deviceTokenId);
  if (error) {
    throw error;
  }
}

async function getGoogleAccessToken(serviceAccount: Record<string, string>): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const assertion = await createJwtAssertion(serviceAccount, now);
  const response = await fetchWithTimeout("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });

  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Google OAuth error: ${response.status} ${body}`);
  }

  const json = JSON.parse(body);
  if (!json.access_token) {
    throw new Error("Google OAuth response missing access_token");
  }
  return json.access_token as string;
}

async function createJwtAssertion(serviceAccount: Record<string, string>, now: number): Promise<string> {
  const header = base64UrlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const payload = base64UrlEncode(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now,
  }));
  const unsigned = `${header}.${payload}`;
  const signature = await signWithServiceAccountKey(unsigned, serviceAccount.private_key);
  return `${unsigned}.${signature}`;
}

async function signWithServiceAccountKey(unsigned: string, privateKeyPem: string): Promise<string> {
  const pem = privateKeyPem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  const keyData = Uint8Array.from(atob(pem), (char) => char.charCodeAt(0));
  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    keyData,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(unsigned),
  );
  return base64UrlFromBuffer(signature);
}

function formatPriceBrl(price: number): string {
  return `R$ ${price.toFixed(2).replace(".", ",")}`;
}

function isUnregisteredFcmToken(errorBody: string): boolean {
  try {
    const parsed = JSON.parse(errorBody);
    const details = parsed?.error?.details;
    if (!Array.isArray(details)) return false;
    return details.some((detail) =>
      detail?.errorCode === "UNREGISTERED" || detail?.errorCode === "INVALID_ARGUMENT"
    );
  } catch {
    return false;
  }
}

function base64UrlEncode(value: string): string {
  return base64UrlFromBuffer(new TextEncoder().encode(value));
}

function base64UrlFromBuffer(value: ArrayBuffer | Uint8Array): string {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function processInBatches<T, R>(
  items: T[],
  concurrency: number,
  worker: (item: T) => Promise<R>,
  startedAt: number,
): Promise<R[]> {
  const results: R[] = [];
  for (let index = 0; index < items.length; index += concurrency) {
    if (Date.now() - startedAt > WALL_CLOCK_LIMIT_MS) break;
    const batch = items.slice(index, index + concurrency);
    const batchResults = await Promise.all(batch.map(worker));
    results.push(...batchResults);
  }
  return results;
}

function fetchWithTimeout(url: string, init: RequestInit, timeoutMs = FETCH_TIMEOUT_MS): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return fetch(url, { ...init, signal: controller.signal }).finally(() => clearTimeout(timer));
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload, null, 2), {
    status,
    headers: JSON_HEADERS,
  });
}
