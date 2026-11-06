# Admin bootstrap and production secrets

The admin dashboard is deployed at https://arthanesia.github.io/admin/. The public page has no shared password and does not decide authorization by itself. The `admin-dashboard` Edge Function validates the Supabase user and checks server-managed `app_metadata.role = 'admin'`.

## Assign the first admin

1. Register the intended administrator using the Arthanesia web app or Android app.
2. Confirm the account's email address.
3. Open the Supabase Dashboard for project `arthanesia-production` and open SQL Editor.
4. Replace the placeholder below with the exact email of the account you own, then run the statement.

```sql
update auth.users
set raw_app_meta_data =
  coalesce(raw_app_meta_data, '{}'::jsonb)
  || jsonb_build_object('role', 'admin')
where lower(email) = lower('YOUR_ADMIN_EMAIL@example.com');

select id, email, raw_app_meta_data->>'role' as role
from auth.users
where lower(email) = lower('YOUR_ADMIN_EMAIL@example.com');
```

The query must return exactly the intended account with role `admin`. Do not promote an account you do not control. Do not set this role in `raw_user_meta_data` / user-editable metadata.

After the role is assigned, sign out from the admin page and sign in again so the new JWT includes the updated app metadata.

To remove admin access:
```sql
update auth.users
set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb) - 'role'
where lower(email) = lower('YOUR_ADMIN_EMAIL@example.com');
```

## Gemini Cloud AI secret

The Edge Function `artha-assistant` is deployed and requires explicit per-request data-sharing consent. It intentionally returns a configuration message until `GEMINI_API_KEY` exists.

In Supabase Dashboard → Project Settings → Edge Functions → Secrets, add:
- `GEMINI_API_KEY`: your Gemini API key
- `GEMINI_MODEL`: optional model name; defaults to `gemini-2.5-flash`

Never paste the Gemini key into this repository, the static website, the Android app, or a chat message.

## Auth redirect / email setup

In Supabase Dashboard → Authentication → URL Configuration, add:
- Site URL: `https://arthanesia.github.io`
- Redirect URL: `https://arthanesia.github.io/app/`

Ensure email/password signup, confirmation delivery, and password recovery are configured for the production domain.

## Admin access and privacy

- Admin dashboard by default returns a limited profile directory and aggregate counts only.
- Direct client-side SELECT of user transaction rows is not granted to administrators.
- The support transaction Edge Function requires a still-active owner approval for the exact admin, verifies the 30-minute expiry, and writes an audit row before reading any personal transaction details.
- The `service_role` / secret key remains in Edge Function server environment only.
