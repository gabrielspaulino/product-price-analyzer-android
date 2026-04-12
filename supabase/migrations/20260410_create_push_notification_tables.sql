create table if not exists public.device_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    token text not null unique,
    platform text not null default 'android',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.notification_deliveries (
    id uuid primary key default gen_random_uuid(),
    device_token_id uuid not null references public.device_tokens(id) on delete cascade,
    product_id uuid not null references public.products(id) on delete cascade,
    dedupe_key text not null,
    created_at timestamptz not null default now(),
    unique (device_token_id, product_id, dedupe_key)
);

create or replace function public.register_device_token(p_token text, p_platform text default 'android')
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    insert into public.device_tokens (user_id, token, platform, updated_at)
    values (auth.uid(), p_token, coalesce(nullif(trim(p_platform), ''), 'android'), now())
    on conflict (token) do update
    set user_id = excluded.user_id,
        platform = excluded.platform,
        updated_at = now();
end;
$$;

create or replace function public.delete_device_token(p_token text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    delete from public.device_tokens
     where token = p_token
       and user_id = auth.uid();
end;
$$;

create or replace function public.get_product_notification_targets(
    p_product_id uuid,
    p_snapshot_price numeric
)
returns table (
    device_token_id uuid,
    device_token text,
    user_id uuid,
    watch_id uuid,
    target_price numeric
)
language sql
security definer
set search_path = public
as $$
    select
        dt.id as device_token_id,
        dt.token as device_token,
        pw.user_id,
        pw.id as watch_id,
        pw.target_price
    from public.product_watches pw
    join public.device_tokens dt
      on dt.user_id = pw.user_id
    where pw.product_id = p_product_id
      and pw.status = 'active'
      and (pw.target_price is null or p_snapshot_price <= pw.target_price);
$$;

revoke all on table public.device_tokens from public;
revoke all on table public.notification_deliveries from public;
revoke all on function public.register_device_token(text, text) from public;
revoke all on function public.delete_device_token(text) from public;
revoke all on function public.get_product_notification_targets(uuid, numeric) from public;

grant execute on function public.register_device_token(text, text) to authenticated;
grant execute on function public.delete_device_token(text) to authenticated;
