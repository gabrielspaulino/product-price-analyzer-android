import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const GROK_API_KEY = Deno.env.get("GROK_API_KEY") ?? "";

const GROK_URL = "https://api.x.ai/v1/responses";
const GROK_MODEL = "grok-4.20-reasoning";
const JSON_HEADERS = { "Content-Type": "application/json" };
const FETCH_TIMEOUT_MS = 120_000;
const X_HANDLES = ["xetdaspromocoes", "urubupromo", "xetimporta"];

const SEARCH_PROMPT_TEMPLATE =
  `Search X for posts mentioning "%s" from @xetdaspromocoes, @urubupromo, or @xetimporta since %s.

Keep ONLY genuine sales deals with clear price for the exact product.
Convert Brazilian prices (R$ xx,xx) to decimal number (e.g. 90.86).

Output **ONLY** the raw JSON. No explanations, no extra text.

Use this exact structure:
{
  "deals": [
    {
      "url": "full post url",
      "price": number or null,
      "posted_at": "2026-04-17T13:33:30Z" or null,
      "author": "username without @" or null,
      "text": "full original post text"
    }
  ]
}`;

const RESPONSE_SCHEMA = {
  type: "json_schema",
  json_schema: {
    name: "deals_response",
    strict: true,
    schema: {
      type: "object",
      properties: {
        deals: {
          type: "array",
          items: {
            type: "object",
            properties: {
              url: { type: "string" },
              price: { type: ["number", "null"] },
              posted_at: { type: ["string", "null"] },
              author: { type: ["string", "null"] },
              text: { type: ["string", "null"] },
            },
            required: ["url", "price", "text"],
            additionalProperties: false,
          },
        },
      },
      required: ["deals"],
      additionalProperties: false,
    },
  },
};

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) {
    return json({ error: "Missing Authorization header" }, 401);
  }

  const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    global: { headers: { Authorization: authHeader } },
  });
  const { data: { user }, error: authError } = await supabase.auth.getUser();
  if (authError || !user) {
    return json({ error: "Unauthorized" }, 401);
  }

  if (!GROK_API_KEY) {
    return json({ error: "Missing GROK_API_KEY secret" }, 500);
  }

  let body: { product_name?: string; last_updated?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }

  const productName = body.product_name?.trim();
  if (!productName) {
    return json({ error: "Missing product_name" }, 400);
  }

  const fromDate = body.last_updated?.split("T")[0] ??
    new Date(Date.now() - 30 * 86400_000).toISOString().split("T")[0];

  console.log(`[SEARCH] user=${user.id} product="${productName}" since=${fromDate}`);

  try {
    const prompt = SEARCH_PROMPT_TEMPLATE
      .replace("%s", productName)
      .replace("%s", fromDate);

    const payload = {
      model: GROK_MODEL,
      input: [{ role: "user", content: prompt }],
      tools: [{
        type: "x_search",
        allowed_x_handles: X_HANDLES,
        from_date: fromDate,
      }],
      response_format: RESPONSE_SCHEMA,
      temperature: 0.0,
      max_output_tokens: 8192,
    };

    const grokResponse = await fetchWithTimeout(GROK_URL, {
      method: "POST",
      headers: {
        ...JSON_HEADERS,
        Authorization: `Bearer ${GROK_API_KEY}`,
      },
      body: JSON.stringify(payload),
    });

    const grokBody = await grokResponse.text();
    if (!grokResponse.ok) {
      console.error(`[GROK] Error ${grokResponse.status}: ${grokBody}`);
      return json({ error: "Grok API error", status: grokResponse.status }, 502);
    }

    const grokJson = JSON.parse(grokBody);

    if (grokJson.error) {
      console.error(`[GROK] API error:`, grokJson.error);
      return json({ error: "Grok API returned error" }, 502);
    }

    if (grokJson.status !== "completed") {
      console.error(`[GROK] Incomplete:`, grokJson.incomplete_details);
      return json({ error: "Grok response incomplete", status: grokJson.status }, 502);
    }

    const text = extractTextFromOutput(grokJson);
    let deals;
    try {
      deals = JSON.parse(text);
    } catch {
      console.error(`[GROK] Failed to parse response: ${text}`);
      deals = { deals: [] };
    }

    console.log(`[SEARCH] Found ${deals.deals?.length ?? 0} deals for "${productName}"`);
    return json(deals);
  } catch (error) {
    console.error(`[SEARCH] Failed:`, error);
    return json({ error: error instanceof Error ? error.message : String(error) }, 500);
  }
});

function extractTextFromOutput(responseJson: Record<string, unknown>): string {
  const output = responseJson.output as Array<Record<string, unknown>> | undefined;
  if (!output) return '{"deals":[]}';

  let result = "";
  for (const item of output) {
    if (item.type !== "message") continue;
    const content = item.content as Array<Record<string, unknown>> | undefined;
    if (!content) continue;
    for (const co of content) {
      if (co.type === "output_text" && typeof co.text === "string") {
        result += co.text;
      }
    }
  }
  return result.trim() || '{"deals":[]}';
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
