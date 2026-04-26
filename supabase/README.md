# Supabase backend scheduler

This repository now includes a Supabase Edge Function that replaces the Android `WorkManager` hourly analysis job.

## Files

- `supabase/functions/analyze-watched-products/index.ts`
  Runs the product analysis for all watched products that have at least one watcher with a Serper API key.
- `supabase/migrations/20260408_create_product_analysis_targets.sql`
  Creates the SQL helper function used by the Edge Function.
- `supabase/sql/schedule_analyze_watched_products.sql`
  Schedules the Edge Function to run every hour with `pg_cron`.

## Required secrets

Set these as Supabase Edge Function secrets:

- `CRON_SECRET`
- `FIREBASE_SERVICE_ACCOUNT_JSON`

The function already receives these default Supabase secrets automatically:

- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`

## Deployment

1. Deploy the SQL migration to create `public.get_product_analysis_targets()`.
2. Deploy the function:

   `supabase functions deploy analyze-watched-products`

3. Set function secrets:

   `supabase secrets set CRON_SECRET=... FIREBASE_SERVICE_ACCOUNT_JSON='{"type":"service_account",...}'`

4. Save the project URL and the same cron secret in Vault:

   `select vault.create_secret('https://<project-ref>.supabase.co', 'project_url');`

   `select vault.create_secret('<same CRON_SECRET value>', 'cron_secret');`

5. Run `supabase/sql/schedule_analyze_watched_products.sql`.

## Android setup for push

1. Add your Firebase Android app in Firebase Console.
2. Download `google-services.json` and place it at `app/google-services.json`.
3. Build the Android app so it can register FCM device tokens.

The app now registers device tokens after login/session restore and receives push messages through Firebase Cloud Messaging.

## Note

This backend scheduler replaces the hourly analysis job. It does not automatically replace Android local notifications. If you want server-driven notifications, you still need a push notification pipeline.
