-- Supabase schema for Owly domain model (see class-diagram.mmd)
-- Run with: supabase db push -f supabase-schema.sql

-- Ensure UUID generation helpers are available
create extension if not exists "pgcrypto";

-- Shared enums ---------------------------------------------------------------
do $$
begin
  if not exists (select 1 from pg_type where typname = 'product_status') then
    create type product_status as enum ('idle', 'loading', 'success', 'error');
  end if;

  if not exists (select 1 from pg_type where typname = 'product_watch_status') then
    create type product_watch_status as enum ('idle', 'active', 'paused');
  end if;
end $$;

-- Generic updated_at trigger -------------------------------------------------
create or replace function set_updated_at() returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;

-- Core tables ----------------------------------------------------------------
create table if not exists public.products (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  normalized_name text not null,
  language text not null default 'pt',
  status product_status not null default 'idle',
  current_price numeric(12,2),
  last_updated timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists products_normalized_language_idx
  on public.products (normalized_name, language);

create table if not exists public.product_watches (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  product_id uuid not null references public.products(id) on delete cascade,
  target_price numeric(12,2),
  status product_watch_status not null default 'active',
  last_notified_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint product_watches_unique_user_product unique (user_id, product_id)
);

create table if not exists public.price_snapshots (
  id uuid primary key default gen_random_uuid(),
  product_id uuid not null references public.products(id) on delete cascade,
  price numeric(12,2) not null,
  source_account text,
  tweet_excerpt text,
  tweet_url text,
  tweet_date timestamptz,
  captured_at timestamptz not null default now()
);

create table if not exists public.api_credentials (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references auth.users(id) on delete cascade,
  serper_key text not null,
  grok_key text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.user_preferences (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references auth.users(id) on delete cascade,
  theme text not null default 'light',
  language text not null default 'pt',
  updated_at timestamptz not null default now()
);

-- updated_at triggers
create trigger set_products_updated_at
  before update on public.products
  for each row execute procedure set_updated_at();

create trigger set_product_watches_updated_at
  before update on public.product_watches
  for each row execute procedure set_updated_at();

create trigger set_api_credentials_updated_at
  before update on public.api_credentials
  for each row execute procedure set_updated_at();

create trigger set_user_preferences_updated_at
  before update on public.user_preferences
  for each row execute procedure set_updated_at();
