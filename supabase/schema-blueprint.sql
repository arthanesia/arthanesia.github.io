-- Arthanesia backend blueprint (NOT applied).
-- Apply only after a Supabase project is connected and reviewed in staging.
-- Roles are read from server-controlled auth.app_metadata, never user-editable user_metadata.

create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '',
  business_name text not null default '',
  base_currency char(3) not null default 'IDR',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint profiles_currency_iso check (base_currency ~ '^[A-Z]{3}$')
);

create table if not exists public.transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  transaction_date date not null default current_date,
  transaction_type text not null check (transaction_type in ('income','expense')),
  description text not null,
  category text not null,
  amount numeric(20,2) not null check (amount > 0),
  currency char(3) not null check (currency ~ '^[A-Z]{3}$'),
  base_currency char(3) not null check (base_currency ~ '^[A-Z]{3}$'),
  base_amount numeric(20,2) not null check (base_amount > 0),
  fx_rate_to_base numeric(24,10) not null default 1 check (fx_rate_to_base > 0),
  fx_provider text,
  fx_observed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists transactions_owner_date_idx
  on public.transactions(user_id, transaction_date desc, created_at desc);
create index if not exists transactions_owner_category_idx
  on public.transactions(user_id, category, transaction_type);

-- Admins initiate a support request; the owner must approve it in-app.
-- The request is tied to one admin and expires automatically.
create table if not exists public.support_access_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  admin_id uuid not null references auth.users(id) on delete cascade,
  reason text not null,
  status text not null default 'pending'
    check (status in ('pending','approved','declined','revoked','expired')),
  created_at timestamptz not null default now(),
  approved_at timestamptz,
  expires_at timestamptz not null default (now() + interval '15 minutes'),
  customer_note text
);
create index if not exists support_access_active_idx
  on public.support_access_requests(user_id, admin_id, expires_at)
  where status = 'approved';

create table if not exists public.admin_audit_logs (
  id bigint generated always as identity primary key,
  actor_admin_id uuid not null references auth.users(id),
  target_user_id uuid references auth.users(id),
  action text not null,
  purpose text not null,
  support_request_id uuid references public.support_access_requests(id),
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists admin_audit_actor_created_idx
  on public.admin_audit_logs(actor_admin_id, created_at desc);
create index if not exists admin_audit_target_created_idx
  on public.admin_audit_logs(target_user_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.transactions enable row level security;
alter table public.support_access_requests enable row level security;
alter table public.admin_audit_logs enable row level security;

-- Profiles: users own and update their profile; admins can inspect only profile fields.
drop policy if exists "profile owner read" on public.profiles;
create policy "profile owner read" on public.profiles
  for select to authenticated using ((select auth.uid()) = id);
drop policy if exists "profile owner insert" on public.profiles;
create policy "profile owner insert" on public.profiles
  for insert to authenticated with check ((select auth.uid()) = id);
drop policy if exists "profile owner update" on public.profiles;
create policy "profile owner update" on public.profiles
  for update to authenticated using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);
drop policy if exists "admin profile read" on public.profiles;
create policy "admin profile read" on public.profiles
  for select to authenticated
  using ((auth.jwt()->'app_metadata'->>'role') = 'admin');

-- Transactions: private to owner by default. Admin access is allowed only
-- through a live, owner-approved support request for that specific admin.
drop policy if exists "transaction owner read" on public.transactions;
create policy "transaction owner read" on public.transactions
  for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists "transaction owner insert" on public.transactions;
create policy "transaction owner insert" on public.transactions
  for insert to authenticated with check ((select auth.uid()) = user_id);
drop policy if exists "transaction owner update" on public.transactions;
create policy "transaction owner update" on public.transactions
  for update to authenticated using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
drop policy if exists "transaction owner delete" on public.transactions;
create policy "transaction owner delete" on public.transactions
  for delete to authenticated using ((select auth.uid()) = user_id);
-- Direct admin reads on transactions are intentionally not granted.
-- Approved support inspection must go through the audited Edge Function.
-- Requests are initiated by an admin and can be read by the target user
-- and the requesting admin. Owners can only change approval state and note.
drop policy if exists "support request parties read" on public.support_access_requests;
create policy "support request parties read" on public.support_access_requests
  for select to authenticated
  using ((select auth.uid()) = user_id or (select auth.uid()) = admin_id);
drop policy if exists "admin creates own support request" on public.support_access_requests;
create policy "admin creates own support request" on public.support_access_requests
  for insert to authenticated
  with check (
    (select auth.uid()) = admin_id
    and (auth.jwt()->'app_metadata'->>'role') = 'admin'
    and status = 'pending'
  );
revoke update on public.support_access_requests from authenticated;
grant update(status, customer_note) on public.support_access_requests to authenticated;
drop policy if exists "owner approves or rejects support request" on public.support_access_requests;
create policy "owner approves or rejects support request" on public.support_access_requests
  for update to authenticated
  using ((select auth.uid()) = user_id and status = 'pending')
  with check (
    (select auth.uid()) = user_id
    and status in ('approved','declined')
  );


create or replace function public.stamp_support_approval()
returns trigger
language plpgsql
set search_path = ''
as $
begin
  if old.status = 'pending' and new.status = 'approved' then
    new.approved_at := now();
    new.expires_at := now() + interval '30 minutes';
  elsif old.status = 'approved' and new.status = 'revoked' then
    new.expires_at := now();
  end if;
  return new;
end;
$;

drop trigger if exists support_access_approval_stamp on public.support_access_requests;
create trigger support_access_approval_stamp
  before update of status on public.support_access_requests
  for each row execute function public.stamp_support_approval();

drop policy if exists "owner revokes approved support request" on public.support_access_requests;
create policy "owner revokes approved support request" on public.support_access_requests
  for update to authenticated
  using ((select auth.uid()) = user_id and status = 'approved')
  with check ((select auth.uid()) = user_id and status = 'revoked');

-- Audit rows are written by a trusted backend function, never by mobile clients.
revoke all on public.admin_audit_logs from anon, authenticated;
grant select on public.admin_audit_logs to authenticated;
drop policy if exists "user reads audit for own data" on public.admin_audit_logs;
create policy "user reads audit for own data" on public.admin_audit_logs
  for select to authenticated using ((select auth.uid()) = target_user_id);
drop policy if exists "admin reads own actions" on public.admin_audit_logs;
create policy "admin reads own actions" on public.admin_audit_logs
  for select to authenticated
  using (
    (select auth.uid()) = actor_admin_id
    and (auth.jwt()->'app_metadata'->>'role') = 'admin'
  );

-- Important operational controls:
-- 1) Provision role='admin' only from trusted server/operations, in app_metadata.
-- 2) Before returning transactions to an admin, an Edge Function must verify
--    the approved support request, create an audit row, then query with a scoped policy.
-- 3) Never expose service_role / sb_secret keys in the app or website.
-- 4) Review RLS and run Supabase security advisors before production rollout.
