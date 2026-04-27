import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const STRIPE_WEBHOOK_SECRET = Deno.env.get("STRIPE_WEBHOOK_SECRET") ?? "";

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  const body = await req.text();
  const signature = req.headers.get("stripe-signature");

  if (!signature || !STRIPE_WEBHOOK_SECRET) {
    return new Response("Missing signature or secret", { status: 400 });
  }

  const valid = await verifyStripeSignature(body, signature, STRIPE_WEBHOOK_SECRET);
  if (!valid) {
    console.error("[WEBHOOK] Invalid signature");
    return new Response("Invalid signature", { status: 400 });
  }

  const event = JSON.parse(body);
  console.log(`[WEBHOOK] Received event: ${event.type}`);

  try {
    switch (event.type) {
      case "checkout.session.completed":
        await handleCheckoutCompleted(event.data.object);
        break;
      case "customer.subscription.updated":
        await handleSubscriptionUpdated(event.data.object);
        break;
      case "customer.subscription.deleted":
        await handleSubscriptionDeleted(event.data.object);
        break;
      case "invoice.payment_failed":
        await handlePaymentFailed(event.data.object);
        break;
      default:
        console.log(`[WEBHOOK] Unhandled event type: ${event.type}`);
    }
  } catch (error) {
    console.error(`[WEBHOOK] Error handling ${event.type}:`, error);
    return new Response(JSON.stringify({ error: "Handler failed" }), { status: 500 });
  }

  return new Response(JSON.stringify({ received: true }), {
    headers: { "Content-Type": "application/json" },
  });
});

async function handleCheckoutCompleted(session: Record<string, unknown>): Promise<void> {
  const userId = (session.metadata as Record<string, string>)?.supabase_user_id;
  const stripeSubscriptionId = session.subscription as string;
  const stripeCustomerId = session.customer as string;

  if (!userId || !stripeSubscriptionId) {
    console.error("[WEBHOOK] checkout.session.completed missing userId or subscription");
    return;
  }

  const { data: premiumPlan } = await supabase
    .from("subscription_plans")
    .select("id")
    .eq("slug", "premium")
    .single();

  if (!premiumPlan) {
    console.error("[WEBHOOK] Premium plan not found");
    return;
  }

  const { error } = await supabase.from("user_subscriptions").upsert(
    {
      user_id: userId,
      plan_id: premiumPlan.id,
      status: "active",
      stripe_customer_id: stripeCustomerId,
      stripe_subscription_id: stripeSubscriptionId,
      current_period_start: new Date().toISOString(),
    },
    { onConflict: "user_id" },
  );

  if (error) {
    console.error("[WEBHOOK] Failed to upsert subscription:", error);
    throw error;
  }

  console.log(`[WEBHOOK] User ${userId} upgraded to premium`);
}

async function handleSubscriptionUpdated(subscription: Record<string, unknown>): Promise<void> {
  const stripeSubId = subscription.id as string;
  const status = mapStripeStatus(subscription.status as string);

  const periodStart = subscription.current_period_start
    ? new Date((subscription.current_period_start as number) * 1000).toISOString()
    : null;
  const periodEnd = subscription.current_period_end
    ? new Date((subscription.current_period_end as number) * 1000).toISOString()
    : null;

  const update: Record<string, unknown> = { status };
  if (periodStart) update.current_period_start = periodStart;
  if (periodEnd) update.current_period_end = periodEnd;

  const { error } = await supabase
    .from("user_subscriptions")
    .update(update)
    .eq("stripe_subscription_id", stripeSubId);

  if (error) {
    console.error("[WEBHOOK] Failed to update subscription:", error);
    throw error;
  }

  console.log(`[WEBHOOK] Subscription ${stripeSubId} updated to ${status}`);
}

async function handleSubscriptionDeleted(subscription: Record<string, unknown>): Promise<void> {
  const stripeSubId = subscription.id as string;

  // Downgrade to free plan
  const { data: freePlan } = await supabase
    .from("subscription_plans")
    .select("id")
    .eq("slug", "free")
    .single();

  if (!freePlan) {
    console.error("[WEBHOOK] Free plan not found");
    return;
  }

  const { error } = await supabase
    .from("user_subscriptions")
    .update({
      plan_id: freePlan.id,
      status: "canceled",
      stripe_subscription_id: null,
    })
    .eq("stripe_subscription_id", stripeSubId);

  if (error) {
    console.error("[WEBHOOK] Failed to cancel subscription:", error);
    throw error;
  }

  console.log(`[WEBHOOK] Subscription ${stripeSubId} canceled, downgraded to free`);
}

async function handlePaymentFailed(invoice: Record<string, unknown>): Promise<void> {
  const stripeSubId = invoice.subscription as string;
  if (!stripeSubId) return;

  const { error } = await supabase
    .from("user_subscriptions")
    .update({ status: "past_due" })
    .eq("stripe_subscription_id", stripeSubId);

  if (error) {
    console.error("[WEBHOOK] Failed to mark past_due:", error);
    throw error;
  }

  console.log(`[WEBHOOK] Subscription ${stripeSubId} marked past_due`);
}

function mapStripeStatus(stripeStatus: string): string {
  switch (stripeStatus) {
    case "active":
      return "active";
    case "past_due":
      return "past_due";
    case "canceled":
    case "unpaid":
    case "incomplete_expired":
      return "canceled";
    case "trialing":
      return "trialing";
    default:
      return "active";
  }
}

async function verifyStripeSignature(
  payload: string,
  header: string,
  secret: string,
): Promise<boolean> {
  const pairs = header.split(",");
  const timestamp = pairs.find((p) => p.startsWith("t="))?.slice(2);
  const sig = pairs.find((p) => p.startsWith("v1="))?.slice(3);

  if (!timestamp || !sig) return false;

  // Reject timestamps older than 5 minutes
  const age = Math.floor(Date.now() / 1000) - parseInt(timestamp, 10);
  if (age > 300) return false;

  const signedPayload = `${timestamp}.${payload}`;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const computed = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(signedPayload));
  const computedHex = Array.from(new Uint8Array(computed))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  return computedHex === sig;
}
