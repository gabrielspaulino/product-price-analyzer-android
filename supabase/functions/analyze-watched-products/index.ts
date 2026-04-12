import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY") ?? "";
const CRON_SECRET = Deno.env.get("CRON_SECRET") ?? "";
const FIREBASE_SERVICE_ACCOUNT_JSON = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";

const SERPER_URL = "https://google.serper.dev/search";
const OPENAI_URL = "https://api.openai.com/v1/chat/completions";
const OPENAI_MODEL = "gpt-5-nano";
const JSON_HEADERS = { "Content-Type": "application/json" };
const MAX_CONCURRENCY = 3;

const OPENAI_DEAL_VALIDATION_PROMPT_TEMPLATE =
  `You are an AI that evaluates whether a text describes a real deal for a specific product.

Your task:
Return true if the text is a valid deal for the searched product.
Return false otherwise.

Strict Rules:
- The text must clearly offer the product for sale (price, discount, payment method, or store).
- The product must match the searched product EXACTLY.
- Accessories, parts, or related items are NOT valid.
  Example: A controller is NOT a PlayStation 5.
- Mentions in news, opinions, speculation, or announcements are NOT valid.
- If the product is only mentioned but not being sold, return FALSE.
- Bundles are valid ONLY if the main product is the searched product.
- Be strict: if there is any doubt, return FALSE.

Output Rules:
- Return ONLY: TRUE or FALSE
- No explanation, no extra text.

Searched product:
"%s"

Text:
"%s"`;

const OPENAI_PRICE_PROMPT_TEMPLATE =
  `You are an information extraction system. Your task is to identify and extract the price from the text of a product deal post from X (Twitter).

Rules and constraints:

The input text may contain prices in different formats, including:
"99,00" -> represents 99.00
"950" -> represents 950.00
"1.240" -> represents 1240.00 (dot is a thousands separator, not decimal, when followed by 3 digits)
"2.340,99" -> represents 2340.99 (dot = thousands separator, comma = decimal separator)

Interpretation rules:
A comma followed by exactly two digits represents decimal places.
A dot followed by exactly three digits represents a thousands separator (not decimal).
If there is no decimal part, assume ".00".
The text may contain other numbers that are NOT prices - ignore them.

Output format:
Return ONLY the numeric price in standard format using a dot as decimal separator.
The result must be parseable as a JavaScript number.
Do NOT include currency symbols, text, or explanations.
Do NOT include multiple values - return only the most relevant price.

Examples:
Input: "Apenas R$ 99,00 hoje!" -> Output: 99.00
Input: "Por só 950 reais" -> Output: 950.00
Input: "De R$ 1.240 por R$ 999,90" -> Output: 999.90
Input: "Mega oferta: R$ 2.340,99!!!" -> Output: 2340.99

Now extract the price from the following text:
%s`;

const OPENAI_DATE_PARSER_PROMPT_TEMPLATE =
  `You are a date parser.

Convert the given human-readable date/time expression into an ISO 8601 value.

Rules:
1. The input may be in English or Portuguese.
2. Handle both absolute and relative dates.

RELATIVE DATES:
- Examples: "7 hours ago", "há 4 dias", "2 days ago"
- Use the provided reference datetime as "now".
- Subtract the specified amount of time.
- Preserve hours and minutes from the reference datetime unless explicitly overridden.

ABSOLUTE DATES:
- Examples: "Mar 25, 2026", "30 de mar. de 2026"
- Convert to YYYY-MM-DD.
- If no time is provided, preserve the calendar date and return it as:
  YYYY-MM-DDT12:00:00Z
- This noon UTC default is required so timezone conversions do not move the value to the previous day.

LANGUAGE HANDLING:
- English and Portuguese month names and abbreviations must be supported.
  Examples:
  - "Mar", "March", "mar.", "março"
- Portuguese relative terms:
  - "há" = "ago"
  - "dias" = "days"
  - "horas" = "hours"

REFERENCE DATETIME:
%s

INPUT:
%s

OUTPUT:
Return ONLY the ISO 8601 value. No explanation.`;

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

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  if (!CRON_SECRET || req.headers.get("x-cron-secret") !== CRON_SECRET) {
    return json({ error: "Unauthorized" }, 401);
  }

  if (!OPENAI_API_KEY) {
    return json({ error: "Missing OPENAI_API_KEY secret" }, 500);
  }
  if (!FIREBASE_SERVICE_ACCOUNT_JSON) {
    return json({ error: "Missing FIREBASE_SERVICE_ACCOUNT_JSON secret" }, 500);
  }

  const startedAt = Date.now();
  const targets = await getTargets();
  const results = await processInBatches(targets, MAX_CONCURRENCY, analyzeTarget);

  const summary = {
    processed: results.length,
    succeeded: results.filter((item) => item.status === "success").length,
    skipped: results.filter((item) => item.status === "skipped").length,
    failed: results.filter((item) => item.status === "error").length,
    duration_ms: Date.now() - startedAt,
    results,
  };

  return json(summary);
});

async function getTargets(): Promise<AnalysisTarget[]> {
  const { data, error } = await supabase.rpc("get_product_analysis_targets");
  if (error) throw error;
  return (data ?? []) as AnalysisTarget[];
}

async function analyzeTarget(target: AnalysisTarget): Promise<ProductResult> {
  try {
    const snapshots = await searchTwitterPrices(
      target.product_name,
      target.last_updated,
      target.representative_serper_key,
    );

    if (snapshots.length === 0) {
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
      const { error } = await supabase.from("price_snapshots").insert(snapshot);
      if (error) throw error;
      notificationsSent += await sendNotificationsForSnapshot(target, snapshot);
      lowestPrice = Math.min(lowestPrice, snapshot.price);
    }

    if (Number.isFinite(lowestPrice)) {
      const { error } = await supabase
        .from("products")
        .update({
          current_price: lowestPrice,
          status: "success",
          last_updated: new Date().toISOString(),
        })
        .eq("id", target.product_id);
      if (error) throw error;
    }

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
): Promise<PriceSnapshot[]> {
  const payload = {
    q:
      `site:x.com (x.com/xetdaspromocoes OR x.com/urubupromo OR x.com/xetimporta) "${productName}" ` +
      (lastUpdated ? `since:${lastUpdated.split("T")[0]} ` : ""),
    gl: "br",
    hl: "pt-br",
    type: "search",
    num: 20,
    engine: "google",
  };

  const response = await fetch(SERPER_URL, {
    method: "POST",
    headers: {
      ...JSON_HEADERS,
      "X-API-KEY": serperKey,
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    throw new Error(`Serper error: ${response.status} ${await response.text()}`);
  }

  const json = await response.json();
  const organic = Array.isArray(json.organic) ? json.organic as SerperOrganicResult[] : [];
  const snapshots: PriceSnapshot[] = [];

  for (const item of organic) {
    const link = item.link ?? "";
    if (!link.includes("twitter.com") && !link.includes("x.com")) continue;

    const snippet = item.snippet?.trim() ?? "";
    const title = item.title?.trim() ?? "";
    const combined = `${title} ${snippet}`.trim();
    if (!combined) continue;

    const isValidDeal = await validateDeal(productName, combined);
    if (!isValidDeal) continue;

    const price = await extractPrice(combined);
    if (price == null) continue;

    snapshots.push({
      product_id: "",
      price,
      source_account: extractTwitterHandle(link),
      tweet_excerpt: snippet ? snippet.slice(0, 280) : null,
      tweet_url: link,
      tweet_date: item.date ? await parseTweetDate(item.date) : null,
    });
  }

  return snapshots;
}

async function validateDeal(productName: string, postText: string): Promise<boolean> {
  const prompt = formatPrompt(OPENAI_DEAL_VALIDATION_PROMPT_TEMPLATE, productName.trim(), postText.trim());
  const content = await fetchOpenAiContent(prompt);
  return content.trim().toUpperCase() === "TRUE";
}

async function extractPrice(postText: string): Promise<number | null> {
  const prompt = formatPrompt(OPENAI_PRICE_PROMPT_TEMPLATE, postText.trim());
  const content = await fetchOpenAiContent(prompt);
  const parsed = Number.parseFloat(content.trim());
  return Number.isFinite(parsed) ? parsed : null;
}

async function parseTweetDate(dateText: string): Promise<string | null> {
  const prompt = formatPrompt(OPENAI_DATE_PARSER_PROMPT_TEMPLATE, new Date().toISOString(), dateText.trim());
  const content = await fetchOpenAiContent(prompt);
  return content.trim() || null;
}

async function fetchOpenAiContent(prompt: string): Promise<string> {
  const payload = {
    model: OPENAI_MODEL,
    messages: [{ role: "user", content: prompt }],
  };

  const response = await fetch(OPENAI_URL, {
    method: "POST",
    headers: {
      ...JSON_HEADERS,
      Authorization: `Bearer ${OPENAI_API_KEY}`,
    },
    body: JSON.stringify(payload),
  });

  const body = await response.text();
  if (!response.ok) {
    throw new Error(`OpenAI error: ${response.status} ${body}`);
  }

  const json = JSON.parse(body);
  const content = json?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) {
    throw new Error("OpenAI returned an empty response");
  }
  return content;
}

async function sendNotificationsForSnapshot(target: AnalysisTarget, snapshot: PriceSnapshot): Promise<number> {
  const dedupeKey = snapshot.tweet_url?.trim() || `${target.product_id}:${snapshot.price}:${snapshot.tweet_date ?? "no-date"}`;
  const notificationTargets = await getNotificationTargets(target.product_id, snapshot.price);
  if (notificationTargets.length === 0) return 0;

  let sentCount = 0;
  for (const notificationTarget of notificationTargets) {
    const claimed = await claimNotificationDelivery(notificationTarget.device_token_id, target.product_id, dedupeKey);
    if (!claimed) continue;

    try {
      await sendFcmMessage(notificationTarget, target, snapshot);
      sentCount += 1;
    } catch (error) {
      console.error("Failed to send FCM message", notificationTarget.device_token_id, error);
    }
  }

  return sentCount;
}

async function getNotificationTargets(productId: string, price: number): Promise<NotificationTarget[]> {
  const { data, error } = await supabase.rpc("get_product_notification_targets", {
    p_product_id: productId,
    p_snapshot_price: price,
  });
  if (error) throw error;
  return (data ?? []) as NotificationTarget[];
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
  const accessToken = await getGoogleAccessToken(serviceAccount);
  const response = await fetch(
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
          android: {
            priority: "high",
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
  const response = await fetch("https://oauth2.googleapis.com/token", {
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

function extractTwitterHandle(url: string): string {
  try {
    const parts = new URL(url).pathname.split("/").filter(Boolean);
    return parts.length > 0 ? `@${parts[0]}` : "@xetdaspromocoes";
  } catch {
    return "@xetdaspromocoes";
  }
}

function formatPrompt(template: string, ...values: string[]): string {
  let result = template;
  for (const value of values) {
    result = result.replace("%s", value);
  }
  return result;
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
): Promise<R[]> {
  const results: R[] = [];
  for (let index = 0; index < items.length; index += concurrency) {
    const batch = items.slice(index, index + concurrency);
    const batchResults = await Promise.all(batch.map(worker));
    results.push(...batchResults);
  }
  return results;
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload, null, 2), {
    status,
    headers: JSON_HEADERS,
  });
}
