# Arthanesia backend

This directory contains a **deployment blueprint**, not a connected production backend yet.

## Current state

- No Supabase project is connected to the linked account, so SQL has not been applied.
- `schema-blueprint.sql` describes user-owned profiles and transactions, time-limited support access, and audit logs.
- `functions/artha-assistant/index.ts` is an authenticated Gemini Edge Function template; explicit `consent: true` is required and only financial aggregates are sent to Gemini.
- `functions/admin-dashboard/index.ts` is an admin-only metrics/profile directory template. It checks server-managed `app_metadata.role=admin`, writes an audit event, and does not return transaction rows.
- `functions/admin-support-transactions/index.ts` is an audited support endpoint: it checks a live, owner-approved request for that exact admin, writes the audit event first, then returns limited transaction rows.
- `config.toml` keeps JWT verification enabled for the assistant function.

## Production setup checklist

1. Connect or create the Supabase project that will own Arthanesia data.
2. Review `schema-blueprint.sql` in a staging project first. Confirm every table has RLS enabled and review all policies with the Supabase security advisors.
3. Configure Auth (email verification, password reset, session policies) and provision trusted admin roles only through server-side `app_metadata`. Never trust `user_metadata` for authorization.
4. Configure Edge Function secrets: `GEMINI_API_KEY` and, when needed, `GEMINI_MODEL`. Supabase supplies the project URL and anon key to Edge Functions.
5. Deploy `artha-assistant` with JWT verification enabled. The request must include explicit `consent: true`. The function queries only the caller's RLS-protected rows, sends aggregated category/amount data to Gemini, and never forwards transaction descriptions or receipt images.
6. Deploy `admin-dashboard` with JWT verification enabled and `SUPABASE_SERVICE_ROLE_KEY` stored as an Edge Function secret only. It checks `app_metadata.role=admin`, writes an audit event, and returns limited profile fields plus aggregate counts only.
7. Deploy `admin-support-transactions` with JWT verification enabled. It requires a live, owner-approved support request created for the current admin. It logs the access before reading limited transaction details; direct admin SELECT on transactions is intentionally not granted in the SQL blueprint.
8. For support access, the admin starts a request, the owner approves or declines in-app, the approval lasts 30 minutes, and the owner can revoke it.
9. Run RLS tests with at least two ordinary users and one admin. Verify user A cannot select, update, delete, export, or infer user B's transaction rows.

## Important key handling

- Publishable/anon keys may be used by the app with RLS in place.
- Secret/service-role keys and `GEMINI_API_KEY` must stay in the server's secret store.
- Never store production credentials in this repository or inside the APK.

## Assistant API contract

POST to the deployed `/functions/v1/artha-assistant` endpoint with an authenticated user JWT and JSON:

```json
{
  "question": "Apa kategori pengeluaran terbesar saya?",
  "from": "2026-11-01",
  "to": "2026-11-30",
  "consent": true
}
```

The response includes `answer`, `period`, and `transactionCount`. Without explicit consent or a valid session, the request is rejected.
