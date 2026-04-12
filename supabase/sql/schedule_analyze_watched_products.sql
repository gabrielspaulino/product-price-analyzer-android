-- Required once before scheduling:
-- 1. Deploy the Edge Function:
--      supabase functions deploy analyze-watched-products
-- 2. Set function secrets:
--      supabase secrets set OPENAI_API_KEY=... CRON_SECRET=...
-- 3. Store secrets in Vault:
--      select vault.create_secret('https://zjkvahwiyzixunjqiyvk.supabase.co', 'project_url');
--      select vault.create_secret('<same CRON_SECRET value>', 'cron_secret');
-- 4. Ensure pg_cron and pg_net are enabled

-- 1. Create a wrapper function (safe for pg_cron)
create or replace function public.invoke_analyze_watched_products()
returns void
language plpgsql
as $$
begin
  perform net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name = 'project_url')
      || '/functions/v1/analyze-watched-products',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-cron-secret', (select decrypted_secret from vault.decrypted_secrets where name = 'cron_secret')
    ),
    body := jsonb_build_object(
      'trigger', 'supabase-cron',
      'scheduled_at', now()::text
    )
  );
end;
$$;

-- 2. Remove existing job if it exists
do $$
declare
    existing_job_id bigint;
begin
    select jobid
      into existing_job_id
      from cron.job
     where jobname = 'analyze-watched-products-hourly';

    if existing_job_id is not null then
        perform cron.unschedule(existing_job_id);
    end if;
end $$;

-- 3. Schedule the cron job (simple, safe command)
select cron.schedule(
  'analyze-watched-products-hourly',
  '0 * * * *',
  $$select public.invoke_analyze_watched_products();$$
);