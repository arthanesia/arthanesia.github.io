# Arthanesia Supabase production backend

Project ref: `ixihqkifpmxruvjyfnrk`  
Name: `arthanesia-production`  
Region: Singapore (`ap-southeast-1`).

## Deployed services

- Tables: profiles, transactions, financial goals, AI conversations/messages, exchange rates, gold prices, admin audit logs, and support-access requests.
- RLS is enabled. Supabase Security Advisor currently reports no security lints.
- Private Storage bucket `receipts` is configured with owner-path policies.
- Edge Functions deployed: `market-data`, `artha-assistant`, `admin-dashboard`, `admin-support-transactions`.
- Edge Function source is mirrored in `supabase/functions/` and configuration in `config.toml`.

## Data security model

- Profiles, transactions, goals, and AI message data are owner-scoped by `auth.uid()`.
- Receipt files are stored in a private bucket under a path beginning with the user's UUID.
- The admin web client uses a public publishable key only; database authorization remains in RLS and server Edge Functions.
- Admin role is read from server-managed `auth.users.raw_app_meta_data` / JWT `app_metadata`, not user-editable metadata.
- The dashboard function returns limited profiles and aggregate counts, not transaction details.
- Support transaction inspection requires a live, owner-approved request tied to the specific admin, expires after 30 minutes, can be revoked, and writes an audit row before reading transaction records.
- Secret/service-role keys are used only inside Edge Functions, never in the browser or APK.

## Owner setup still required

1. Register the intended admin account, verify its email, then follow [ADMIN-BOOTSTRAP.md](./ADMIN-BOOTSTRAP.md) to assign `app_metadata.role=admin` using trusted SQL Editor access.
2. In Supabase Auth URL Configuration, allow `https://arthanesia.github.io/app/` and confirm email delivery.
3. Add `GEMINI_API_KEY` to Edge Function secrets (optional `GEMINI_MODEL`); until configured, cloud AI returns a setup message and Android local analysis remains usable.
4. Test signup, email confirmation, password recovery, RLS isolation with two normal accounts, admin 403/200 role checks, approved support expiry/revocation, and receipt storage before announcing general availability.

## Edge Functions

- `market-data`: reads public FX reference data from open.er-api.com and XAU spot from gold-api.com. FX source data has a daily update schedule; the endpoint caches for 15 minutes and reports provider timestamps.
- `artha-assistant`: requires an authenticated user, explicit `consent: true`, and a server-side `GEMINI_API_KEY`. It aggregates the caller's own transactions through RLS and sends only totals/categories to Gemini.
- `admin-dashboard`: manually validates the bearer JWT (gateway JWT verification is disabled for the function because the code performs its own Supabase Auth validation), checks `app_metadata.role=admin`, writes an audit event, and returns limited profile/aggregate data.
- `admin-support-transactions`: similarly validates the admin identity, checks a live owner-approved request for the exact admin, audits first, then returns limited transaction fields.

## Client configuration

The public web and Android clients use:
- Project URL: `https://ixihqkifpmxruvjyfnrk.supabase.co`
- Publishable key: stored in `admin/config.js` and the Android API client. This key is intended for public clients; RLS and server-side authorization remain mandatory.

Do not commit any Supabase secret/service-role key or Gemini API key.
