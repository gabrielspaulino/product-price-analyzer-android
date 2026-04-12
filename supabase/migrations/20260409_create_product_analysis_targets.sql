create or replace function public.get_product_analysis_targets()
returns table (
    product_id uuid,
    product_name text,
    normalized_name text,
    product_status text,
    current_price numeric,
    last_updated timestamptz,
    representative_user_id uuid,
    representative_serper_key text
)
language sql
security definer
set search_path = public
as $$
    select distinct on (pw.product_id)
        p.id as product_id,
        p.name as product_name,
        p.normalized_name,
        p.status as product_status,
        p.current_price,
        p.last_updated,
        pw.user_id as representative_user_id,
        ac.serper_key as representative_serper_key
    from public.product_watches pw
    join public.products p
      on p.id = pw.product_id
    join public.api_credentials ac
      on ac.user_id = pw.user_id
    where pw.status = 'active'
      and ac.serper_key is not null
      and btrim(ac.serper_key) <> ''
    order by pw.product_id, pw.id;
$$;

revoke all on function public.get_product_analysis_targets() from public;
