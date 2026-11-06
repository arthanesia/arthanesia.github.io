-- Arthanesia production schema blueprint.
-- This file documents the production schema; production changes are applied via versioned Supabase migrations.
create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  full_name text,
  avatar_url text,
  base_currency text not null default 'IDR',
  locale text not null default 'id-ID',
  timezone text not null default 'Asia/Jakarta',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  business_name text not null default '',
  default_currency text not null default 'IDR'
);

create table if not exists public.transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  type text not null check (type in ('income','expense','transfer')),
  amount numeric(20,2) not null check (amount >= 0),
  currency text not null default 'IDR',
  amount_base numeric(20,2),
  base_currency text not null default 'IDR',
  exchange_rate numeric(20,10) not null default 1,
  category text,
  description text,
  merchant text,
  occurred_at timestamptz not null default now(),
  source text not null default 'manual' check (source in ('manual','receipt','voice','ai')),
  receipt_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  client_ref text
);

create table if not exists public.financial_goals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  target_amount numeric(20,2) not null check (target_amount >= 0),
  currency text not null default 'IDR',
  current_amount numeric(20,2) not null default 0,
  deadline date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.ai_conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.ai_messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.ai_conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('user','assistant','system')),
  content text not null,
  created_at timestamptz not null default now()
);

create table if not exists public.exchange_rates (
  id uuid primary key default gen_random_uuid(),
  base_currency text not null,
  quote_currency text not null,
  rate numeric(24,12) not null check (rate > 0),
  source text,
  observed_at timestamptz not null default now(),
  unique(base_currency, quote_currency)
);

create table if not exists public.gold_prices (
  id uuid primary key default gen_random_uuid(),
  purity text not null default '24K',
  price_per_gram numeric(20,2) not null check (price_per_gram > 0),
  currency text not null default 'IDR',
  source text,
  observed_at timestamptz not null default now()
);

create table if not exists public.admin_audit_logs (
  id uuid primary key default gen_random_uuid(),
  admin_user_id uuid references auth.users(id) on delete set null,
  action text not null,
  target_user_id uuid references auth.users(id) on delete set null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists public.support_access_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  admin_id uuid not null references auth.users(id) on delete cascade,
  reason text not null,
  status text not null default 'pending'
    check (status in ('pending','approved','declined','revoked','expired')),
  created_at timestamptz not null default now(),
  approved_at timestamptz,
  expires_at timestamptz not null default (now() + interval '30 minutes'),
  customer_note text
);

create index if not exists transactions_user_date_idx
  on public.transactions(user_id, occurred_at desc);
create unique index if not exists transactions_user_client_ref_unique
  on public.transactions(user_id, client_ref);
create index if not exists goals_user_idx on public.financial_goals(user_id);
create index if not exists ai_conversations_user_created_idx on public.ai_conversations(user_id, created_at desc);
create index if not exists ai_messages_conversation_idx on public.ai_messages(conversation_id, created_at);
create index if not exists ai_messages_user_created_idx on public.ai_messages(user_id, created_at desc);
create index if not exists exchange_rates_pair_idx on public.exchange_rates(base_currency, quote_currency);
create index if not exists gold_prices_observed_idx on public.gold_prices(observed_at desc);
create index if not exists admin_audit_logs_admin_created_idx on public.admin_audit_logs(admin_user_id, created_at desc);
create index if not exists admin_audit_logs_target_created_idx on public.admin_audit_logs(target_user_id, created_at desc);
create index if not exists support_requests_admin_idx on public.support_access_requests(admin_id, created_at desc);
create index if not exists support_requests_user_idx on public.support_access_requests(user_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.transactions enable row level security;
alter table public.financial_goals enable row level security;
alter table public.ai_conversations enable row level security;
alter table public.ai_messages enable row level security;
alter table public.exchange_rates enable row level security;
alter table public.gold_prices enable row level security;
alter table public.admin_audit_logs enable row level security;
alter table public.support_access_requests enable row level security;

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles(id, full_name, business_name, base_currency)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'full_name',''),
    coalesce(new.raw_user_meta_data->>'business_name',''),
    'IDR'
  )
  on conflict (id) do update
  set full_name = excluded.full_name,
      business_name = excluded.business_name;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
for each row execute procedure public.handle_new_user();

drop policy if exists "profiles own" on public.profiles;
create policy "profiles own" on public.profiles
  for all to authenticated
  using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);

drop policy if exists "transactions own" on public.transactions;
create policy "transactions own" on public.transactions
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "goals own" on public.financial_goals;
create policy "goals own" on public.financial_goals
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "conversations own" on public.ai_conversations;
create policy "conversations own" on public.ai_conversations
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "messages own" on public.ai_messages;
create policy "messages own" on public.ai_messages
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "rates readable" on public.exchange_rates;
create policy "rates readable" on public.exchange_rates
  for select to authenticated using (true);
drop policy if exists "gold readable" on public.gold_prices;
create policy "gold readable" on public.gold_prices
  for select to authenticated using (true);

drop policy if exists "support request parties read" on public.support_access_requests;
create policy "support request parties read" on public.support_access_requests
  for select to authenticated
  using ((select auth.uid()) = user_id or (select auth.uid()) = admin_id);

drop policy if exists "admin creates own support request" on public.support_access_requests;
create policy "admin creates own support request" on public.support_access_requests
  for insert to authenticated
  with check (
    (select auth.uid()) = admin_id
    and ((select auth.jwt())->'app_metadata'->>'role') = 'admin'
    and status = 'pending'
  );

drop policy if exists "owner manages support access" on public.support_access_requests;
create policy "owner manages support access" on public.support_access_requests
  for update to authenticated
  using ((select auth.uid()) = user_id and status in ('pending','approved'))
  with check ((select auth.uid()) = user_id and status in ('approved','declined','revoked'));
revoke update on public.support_access_requests from authenticated;
grant update(status, customer_note) on public.support_access_requests to authenticated;

create or replace function public.stamp_support_access_window()
returns trigger language plpgsql set search_path = '' as $$
begin
  if old.status = 'pending' and new.status = 'approved' then
    new.approved_at := now();
    new.expires_at := now() + interval '30 minutes';
  elsif old.status = 'approved' and new.status = 'revoked' then
    new.expires_at := now();
  end if;
  return new;
end;
$$;
drop trigger if exists support_access_window on public.support_access_requests;
create trigger support_access_window before update of status on public.support_access_requests
for each row execute function public.stamp_support_access_window();

drop policy if exists "admin audit deny clients" on public.admin_audit_logs;
create policy "admin audit deny clients" on public.admin_audit_logs
  for all to anon, authenticated using (false) with check (false);
revoke all on public.admin_audit_logs from anon, authenticated;

grant select, insert, update, delete on public.profiles to authenticated;
grant select, insert, update, delete on public.transactions to authenticated;
grant select, insert, update, delete on public.financial_goals to authenticated;
grant select, insert, update, delete on public.ai_conversations to authenticated;
grant select, insert, update, delete on public.ai_messages to authenticated;
grant select on public.exchange_rates to authenticated;
grant select on public.gold_prices to authenticated;
grant select, insert, update on public.support_access_requests to authenticated;

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values ('receipts','receipts',false,10485760,array['image/jpeg','image/png','image/webp','application/pdf'])
on conflict (id) do update set public=false, file_size_limit=10485760,
  allowed_mime_types=array['image/jpeg','image/png','image/webp','application/pdf'];

drop policy if exists "receipt owner select" on storage.objects;
create policy "receipt owner select" on storage.objects for select to authenticated
  using (bucket_id='receipts' and (storage.foldername(name))[1]=(select auth.uid())::text);
drop policy if exists "receipt owner insert" on storage.objects;
create policy "receipt owner insert" on storage.objects for insert to authenticated
  with check (bucket_id='receipts' and (storage.foldername(name))[1]=(select auth.uid())::text);
drop policy if exists "receipt owner update" on storage.objects;
create policy "receipt owner update" on storage.objects for update to authenticated
  using (bucket_id='receipts' and (storage.foldername(name))[1]=(select auth.uid())::text)
  with check (bucket_id='receipts' and (storage.foldername(name))[1]=(select auth.uid())::text);
drop policy if exists "receipt owner delete" on storage.objects;
create policy "receipt owner delete" on storage.objects for delete to authenticated
  using (bucket_id='receipts' and (storage.foldername(name))[1]=(select auth.uid())::text);
