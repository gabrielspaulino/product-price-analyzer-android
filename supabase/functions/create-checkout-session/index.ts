import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const STRIPE_SECRET_KEY = Deno.env.get("STRIPE_SECRET_KEY") ?? "";

const STRIPE_API = "https://api.stripe.com/v1";
const JSON_HEADERS = { "Content-Type": "application/json" };

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  if (!STRIPE_SECRET_KEY) {
    return json({ error: "Stripe not configured" }, 500);
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) {
    return json({ error: "Unauthorized" }, 401);
  }

  const token = authHeader.replace("Bearer ", "");
  const {
    data: { user },
    error: authError,
  } = await supabase.auth.getUser(token);
  if (authError || !user) {
    return json({ error: "Unauthorized" }, 401);
  }

  try {
    const customerId = await getOrCreateStripeCustomer(user.id, user.email ?? "");

    const { data: plan, error: planError } = await supabase
      .from("subscription_plans")
      .select("stripe_price_id")
      .eq("slug", "premium")
      .single();

    if (planError || !plan?.stripe_price_id) {
      return json(
        { error: "Premium plan not configured. Set stripe_price_id in subscription_plans." },
        500,
      );
    }

    const session = await stripePost("checkout/sessions", {
      customer: customerId,
      mode: "subscription",
      "line_items[0][price]": plan.stripe_price_id,
      "line_items[0][quantity]": "1",
      "success_url": `${SUPABASE_URL}/functions/v1/checkout-result?status=success`,
      "cancel_url": `${SUPABASE_URL}/functions/v1/checkout-result?status=canceled`,
      "metadata[supabase_user_id]": user.id,
    });

    if (!session.url) {
      console.error("[CHECKOUT] Session created without URL:", JSON.stringify(session));
      return json({ error: "Failed to create checkout session" }, 500);
    }

    return json({ url: session.url });
  } catch (error) {
    console.error("[CHECKOUT] Error:", error);
    return json({ error: error instanceof Error ? error.message : "Internal error" }, 500);
  }
});

async function getOrCreateStripeCustomer(userId: string, email: string): Promise<string> {
  const { data: sub } = await supabase
    .from("user_subscriptions")
    .select("stripe_customer_id")
    .eq("user_id", userId)
    .maybeSingle();

  if (sub?.stripe_customer_id) {
    return sub.stripe_customer_id;
  }

  const customer = await stripePost("customers", {
    email,
    "metadata[supabase_user_id]": userId,
  });

  // Upsert a free-tier subscription row so stripe_customer_id is persisted
  const { data: freePlan } = await supabase
    .from("subscription_plans")
    .select("id")
    .eq("slug", "free")
    .single();

  if (freePlan) {
    await supabase.from("user_subscriptions").upsert(
      {
        user_id: userId,
        plan_id: freePlan.id,
        status: "active",
        stripe_customer_id: customer.id,
      },
      { onConflict: "user_id" },
    );
  }

  return customer.id;
}

async function stripePost(endpoint: string, params: Record<string, string>): Promise<Record<string, unknown>> {
  const res = await fetch(`${STRIPE_API}/${endpoint}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${STRIPE_SECRET_KEY}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams(params),
  });

  const body = await res.json();
  if (!res.ok) {
    throw new Error(`Stripe ${endpoint} error: ${body?.error?.message ?? res.status}`);
  }
  return body;
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: JSON_HEADERS,
  });
}
