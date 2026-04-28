import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const CRON_SECRET = Deno.env.get("CRON_SECRET") ?? "";
const FIREBASE_SERVICE_ACCOUNT_JSON = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";

const SERPER_URL = "https://google.serper.dev/search";
const JSON_HEADERS = { "Content-Type": "application/json" };
const MAX_CONCURRENCY = 2;
const WALL_CLOCK_LIMIT_MS = 120_000;
const FETCH_TIMEOUT_MS = 30_000;

type AnalysisTarget = {
  product_id: string;
  product_name: string;
  normalized_name: string | null;
  product_status: string | null;
  current_price: number | null;
  last_updated: string | null;
  representative_user_id: string;
  representative_serper_key: string;
};

type SerperOrganicResult = {
  title?: string;
  link?: string;
  snippet?: string;
  date?: string;
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
  console.log(`[CRON] Found ${targets.length} targets:`, targets.map(t => `${t.product_name} (key: ${t.representative_serper_key ? "YES" : "NO"})`));
  const results = await processInBatches(
    targets,
    MAX_CONCURRENCY,
    (t) => analyzeTarget(t, startedAt),
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
    .select("product_id, user_id")
    .eq("status", "active");
  if (watchError) throw watchError;
  console.log(`[TARGETS] Found ${watches?.length ?? 0} active watches`);
  if (!watches || watches.length === 0) return [];

  const productIds = [...new Set(watches.map((w: { product_id: string }) => w.product_id))];
  const userIds = [...new Set(watches.map((w: { user_id: string }) => w.user_id))];

  const { data: products, error: productError } = await supabase
    .from("products")
    .select("id, name, normalized_name, status, current_price, last_updated")
    .in("id", productIds);
  if (productError) throw productError;

  const { data: credentials, error: credError } = await supabase
    .from("api_credentials")
    .select("user_id, serper_key")
    .in("user_id", userIds)
    .not("serper_key", "is", null);
  if (credError) throw credError;

  console.log(`[TARGETS] Found ${products?.length ?? 0} products, ${credentials?.length ?? 0} credentials`);

  const serperKeyByUser = new Map<string, string>();
  for (const cred of credentials ?? []) {
    if (cred.serper_key) serperKeyByUser.set(cred.user_id, cred.serper_key);
  }

  const productMap = new Map<string, typeof products extends (infer T)[] ? T : never>();
  for (const p of products ?? []) {
    productMap.set(p.id, p);
  }

  const targetMap = new Map<string, AnalysisTarget>();
  for (const watch of watches) {
    const product = productMap.get(watch.product_id);
    if (!product) continue;
    if (targetMap.has(watch.product_id)) continue;

    const serperKey = serperKeyByUser.get(watch.user_id);
    if (!serperKey) continue;

    targetMap.set(watch.product_id, {
      product_id: product.id,
      product_name: product.name,
      normalized_name: product.normalized_name,
      product_status: product.status,
      current_price: product.current_price,
      last_updated: product.last_updated,
      representative_user_id: watch.user_id,
      representative_serper_key: serperKey,
    });
  }

  return [...targetMap.values()];
}

async function analyzeTarget(target: AnalysisTarget, startedAt: number): Promise<ProductResult> {
  console.log(`[ANALYZE] Starting: "${target.product_name}" (last_updated: ${target.last_updated})`);
  try {
    const snapshots = await searchTwitterPrices(
      target.product_name,
      target.last_updated,
      target.representative_serper_key,
      startedAt,
    );

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

async function searchTwitterPrices(
  productName: string,
  lastUpdated: string | null,
  serperKey: string,
  startedAt: number,
): Promise<PriceSnapshot[]> {
  const payload = {
    q:
      `site:x.com (x.com/xetdaspromocoes OR x.com/urubupromo OR x.com/xetimporta) "${productName}" ` +
      (lastUpdated ? `since:${lastUpdated.split("T")[0]} ` : ""),
    gl: "br",
    hl: "pt-br",
    type: "search",
    num: 10,
    engine: "google",
  };

  const response = await fetchWithTimeout(SERPER_URL, {
    method: "POST",
    headers: {
      ...JSON_HEADERS,
      "X-API-KEY": serperKey,
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const errText = await response.text();
    console.error(`[SERPER] Error ${response.status}: ${errText}`);
    throw new Error(`Serper error: ${response.status} ${errText}`);
  }

  const json = await response.json();
  const organic = Array.isArray(json.organic) ? json.organic as SerperOrganicResult[] : [];
  console.log(`[SERPER] "${productName}" returned ${organic.length} organic results`);
  const snapshots: PriceSnapshot[] = [];
  const fromDate = lastUpdated?.split("T")[0];
  const cutoff = fromDate ? new Date(fromDate + "T00:00:00Z").getTime() : 0;

  for (const item of organic) {
    if (Date.now() - startedAt > WALL_CLOCK_LIMIT_MS) break;

    const link = item.link ?? "";
    if (!link.includes("twitter.com") && !link.includes("x.com")) continue;

    const snippet = item.snippet?.trim() ?? "";
    const title = item.title?.trim() ?? "";
    const combined = `${title} ${snippet}`.trim();
    if (!combined) continue;

    try {
      const price = extractPrice(combined);
      console.log(`[EXTRACT] "${productName}" result for "${link}": price=${price}`);
      if (price == null) continue;

      const tweetDate = item.date ? parseTweetDate(item.date) : null;
      if (cutoff && tweetDate) {
        try {
          if (new Date(tweetDate).getTime() < cutoff) {
            console.log(`[FILTER] Skipping old deal (${tweetDate} < ${fromDate}): "${link}"`);
            continue;
          }
        } catch { /* keep if date parsing fails */ }
      }

      snapshots.push({
        product_id: "",
        price,
        source_account: extractTwitterHandle(link),
        tweet_excerpt: snippet ? snippet.slice(0, 280) : null,
        tweet_url: link,
        tweet_date: tweetDate,
      });
    } catch (error) {
      console.error(`[EXTRACT] "${productName}" failed for "${link}":`, error);
    }
  }

  return snapshots;
}

function extractPrice(text: string): number | null {
  const matches = [...text.matchAll(/(\d{1,3}(?:\.\d{3})*,\d{2})/g)];
  if (matches.length === 0) return null;

  let lowest = Number.POSITIVE_INFINITY;
  for (const match of matches) {
    const raw = match[1].replace(/\./g, "").replace(",", ".");
    const value = Number.parseFloat(raw);
    if (Number.isFinite(value) && value > 0) lowest = Math.min(lowest, value);
  }
  return Number.isFinite(lowest) ? lowest : null;
}

const MONTH_MAP: Record<string, number> = {
  jan: 0, january: 0, janeiro: 0,
  feb: 1, fev: 1, february: 1, fevereiro: 1,
  mar: 2, march: 2, "março": 2,
  apr: 3, abr: 3, april: 3, abril: 3,
  may: 4, mai: 4, maio: 4,
  jun: 5, june: 5, junho: 5,
  jul: 6, july: 6, julho: 6,
  aug: 7, ago: 7, august: 7, agosto: 7,
  sep: 8, set: 8, september: 8, setembro: 8,
  oct: 9, out: 9, october: 9, outubro: 9,
  nov: 10, november: 10, novembro: 10,
  dec: 11, dez: 11, december: 11, dezembro: 11,
};

function parseTweetDate(dateText: string): string | null {
  const text = dateText.trim().toLowerCase().replace(/\./g, "");
  const now = new Date();

  const relMatch = text.match(/(?:há\s+)?(\d+)\s*(hour|hora|day|dia|minute|minuto|min|h|d|m)s?\s*(?:ago|atrás)?/i);
  if (relMatch) {
    const amount = parseInt(relMatch[1], 10);
    const unit = relMatch[2].toLowerCase();
    const ms = unit.startsWith("h") || unit.startsWith("hora")
      ? amount * 3600_000
      : unit.startsWith("d") || unit.startsWith("dia")
        ? amount * 86400_000
        : amount * 60_000;
    return new Date(now.getTime() - ms).toISOString();
  }

  for (const [name, idx] of Object.entries(MONTH_MAP)) {
    const patterns = [
      new RegExp(`${name}\\s+(\\d{1,2}),?\\s+(\\d{4})`),
      new RegExp(`(\\d{1,2})\\s+(?:de\\s+)?${name}(?:\\s+(?:de\\s+)?(\\d{4}))?`),
    ];
    for (const pattern of patterns) {
      const m = text.match(pattern);
      if (m) {
        const day = parseInt(m[1].length <= 2 ? m[1] : m[2], 10);
        const year = parseInt(m[1].length === 4 ? m[1] : (m[2] ?? String(now.getFullYear())), 10);
        const dayVal = patterns.indexOf(pattern) === 0 ? parseInt(m[1], 10) : day;
        const yearVal = patterns.indexOf(pattern) === 0 ? parseInt(m[2], 10) : year;
        return `${yearVal}-${String(idx + 1).padStart(2, "0")}-${String(dayVal).padStart(2, "0")}T12:00:00Z`;
      }
    }
  }

  return null;
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

function extractTwitterHandle(url: string): string {
  try {
    const parts = new URL(url).pathname.split("/").filter(Boolean);
    return parts.length > 0 ? `@${parts[0]}` : "@xetdaspromocoes";
  } catch {
    return "@xetdaspromocoes";
  }
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
