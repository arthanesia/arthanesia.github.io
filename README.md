# Arthanesia

Arthanesia is the production home for the Indonesian small-business financial management product.

- Public website: https://arthanesia.github.io/
- Protected admin login: https://arthanesia.github.io/admin/
- Repository: https://github.com/arthanesia/arthanesia.github.io
- Prototype repository (kept separate): https://github.com/arthanesia/prototipe

## Repository layout

- `index.html` — public product website.
- `admin/` — admin sign-in and dashboard client. It does not contain demo users or hard-coded credentials.
- `mobile/android/` — native Android application (Kotlin + Jetpack Compose).
- `supabase/` — database schema and Edge Function source templates.
- `.github/workflows/` — Android build and GitHub Pages deployment.

## Android app

The Android application uses a local SQLite database for transaction, savings-goal, workspace-profile, currency-preference and theme persistence. The app starts with an empty ledger: no sample financial records are preloaded.

### Build

Requires JDK 17, Android SDK Platform 36, Android Build Tools 36.0.0, and Gradle 9.3.1.

```bash
gradle --no-daemon -p mobile/android :app:assembleDebug
```

Output:

`mobile/android/app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions builds the APK when `mobile/android/**` changes.

## Backend / production readiness

A Supabase organization named `arthanesia` is connected, but the organization currently has **no Supabase project**. The SQL and Edge Functions here are source templates; they are not yet deployed and no database has been created.

Before cloud accounts, multi-device sync, server-side AI, or the production admin dashboard can work:

1. Create the Supabase project in the Arthanesia organization and select the Singapore region.
2. Apply and review `supabase/schema-blueprint.sql` in the project.
3. Configure Supabase Auth email/password sign-in and invite/provision user accounts.
4. Configure the public admin client values in `admin/config.js`: project URL and publishable key only.
5. Deploy Edge Functions from `supabase/functions/` with JWT verification enabled.
6. Keep the Supabase secret/service-role key and Gemini API key exclusively in Edge Function secrets.
7. Assign `app_metadata.role = "admin"` only through trusted server-side administration. Never trust user-editable `user_metadata`.
8. Run RLS, role-boundary, and access-audit tests before enabling real users.

The admin web page is locked by default while the backend is unconfigured. It uses Supabase Auth and calls the protected `admin-dashboard` Edge Function after login. The function must verify the server-managed admin role before returning the limited profile directory and aggregate counts. It does not return transaction rows.

## Current limitations

- Local Android ledger data is persistent on that device, but cloud sync and real user accounts are not active yet.
- The website admin login cannot authenticate until a Supabase project is created, Auth is configured, the Edge Functions are deployed, and `admin/config.js` is filled with the public project URL and publishable key.
- The in-app Artha Assistant currently analyses local transaction aggregates. The Gemini Edge Function is a server template, not yet deployed.
- The native app still needs Supabase account/session integration and multi-user data sync before public production release.
- Currency conversion, live rates and tax/zakat calculations must be tested and connected to reviewed data providers before these are treated as official values.

Do not put password, service-role key, secret key, or Gemini API key in this repository or the Android app.
