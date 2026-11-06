# Arthanesia

Arthanesia is a financial-management product for Indonesian small businesses. The production website and Android app are published from this repository.

## Production links

- Website: https://arthanesia.github.io/
- Web app: https://arthanesia.github.io/app/
- Android download: https://arthanesia.github.io/download/
- Admin sign-in: https://arthanesia.github.io/admin/
- Privacy policy: https://arthanesia.github.io/privacy/
- Terms: https://arthanesia.github.io/terms/
- Source repository: https://github.com/arthanesia/arthanesia.github.io
- Separate prototype repository: https://github.com/arthanesia/prototipe

## Android app

The native Android application uses Kotlin + Jetpack Compose, SQLite for offline persistence, and Supabase Auth/REST for account sessions and transaction sync.

Version: **0.6.1** (debug / early-access build).

Included:
- Email/password signup and login.
- Session encryption using Android Keystore (AES-GCM).
- Local transaction persistence with per-user partitioning on shared devices.
- Offline-first transaction sync to Supabase.
- Transaction currency selection: IDR, USD, EUR, GBP, JPY, SGD, AUD, MYR.
- Original amount, FX rate snapshot, and IDR base value stored separately.
- Voice input using Android speech recognition.
- Receipt image OCR using on-device ML Kit.
- Professional A4 bookkeeping PDF, Android print dialog, and CSV export.
- Cashflow summary and transaction-derived seven-day candlestick chart.
- Annual tax estimate and configured 250g gold nisab zakat indicator.
- Artha Assistant local analysis plus consent-gated cloud endpoint.

Build requirements: JDK 17, Android SDK Platform 36, Android Build Tools 36.0.0, and Gradle 9.3.1.

Build:
```bash
gradle --no-daemon -p mobile/android :app:assembleDebug
```

APK output:
`mobile/android/app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions also publishes `Arthanesia.apk` to the GitHub Release tagged `v0.6.1`.

## Web app

The installable PWA lives at `app/`. It uses Supabase email/password authentication and RLS-protected user-owned transactions. It supports:
- User sign-up / sign-in.
- Profile and business settings.
- Transaction entry and history.
- Currency conversion using the market-data function; historical rate and IDR amount are stored on the transaction.
- Private receipt upload to the `receipts` storage bucket.
- CSV export and browser print-to-PDF.
- Consent-gated Artha AI invocation.

The web app is a production MVP, not yet feature parity with every native screen. Browser receipt upload is available; on-device OCR and VoiceCash are currently in the Android app.

## Supabase production backend

Project: `arthanesia-production`  
Project ref: `ixihqkifpmxruvjyfnrk`  
Region: Singapore (`ap-southeast-1`)

Database tables include:
- `profiles`
- `transactions`
- `financial_goals`
- `ai_conversations` / `ai_messages`
- `exchange_rates` / `gold_prices`
- `admin_audit_logs`
- `support_access_requests`

RLS is enabled on all customer tables. The Security Advisor currently reports no security lints. Some performance advisories may remain as the project has little/no production traffic yet.

Deployed Edge Functions:
- `market-data`: gold spot + currency reference rates.
- `admin-dashboard`: checks server-managed `app_metadata.role=admin`, writes audit events, and returns limited profile records + aggregate counts (not transaction rows).
- `admin-support-transactions`: requires an owner-approved, live, time-limited support request bound to the requesting admin; writes audit before returning limited transaction details.
- `artha-assistant`: requires an authenticated user and explicit `consent: true`; queries the caller's RLS-protected rows and sends aggregates to Gemini only.

## Remaining owner setup for full production operation

1. **First admin account:** create/register the intended admin user through the web app or Android app, then provision `app_metadata.role = 'admin'` for that exact user from the Supabase SQL editor or trusted server-side administration. Never set roles in user-editable metadata. See `supabase/ADMIN-BOOTSTRAP.md`.
2. **Gemini:** add `GEMINI_API_KEY` (and optionally `GEMINI_MODEL`) to Supabase Edge Function secrets. The cloud AI function is deployed but intentionally returns a configuration message until the key exists; local analysis continues to work.
3. **Auth URLs/email:** in Supabase Auth settings, allow `https://arthanesia.github.io/app/` as a redirect URL and verify that email confirmation mail delivery is configured.
4. **Device QA:** test APK installation, account verification, network/offline sync, transaction deletion, print-to-PDF, OCR, and VoiceCash on actual Android devices before a public stable release.

## Important data/provider limitations

- Currency reference feed is sourced from open.er-api.com and updates on the provider's daily schedule; it is **not tick-by-tick live FX**. Transaction values store their original currency and conversion snapshot, so later rate updates do not rewrite history.
- Gold uses an XAU spot feed, converted to IDR per gram, and refreshed by the app every 15 minutes. It is not an Antam retail quote.
- Zakat and tax screens are estimates. Zakat uses the Arthanesia 250g nisab configuration and net recorded annual cashflow; the correct basis should be reviewed with the relevant zakat institution. The PPh estimate uses net income after entered qualifying deductions and default PTKP TK/0; household status and allowable deductions must be verified.
- The release is a debug-signed APK for early access, not a Play Store production-signed binary. The current build has passed Gradle compilation but has not been tested across a device matrix.

Never put Supabase secret/service-role keys or the Gemini API key in the website, Android source, or repository.
