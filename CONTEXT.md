# Owly - Rastreador Inteligente de Precos (Price Tracker)

Android app that monitors e-commerce product prices by scraping Twitter/X deal accounts and using AI to validate and extract pricing data. Users add products to a watchlist and receive notifications when prices drop below their target.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Build | Gradle 8.5, AGP 8.3.2 |
| Min / Target SDK | 26 (Android 8.0) / 34 (Android 14) |
| HTTP | OkHttp3 4.12.0 |
| JSON | Gson 2.10.1 |
| UI | AndroidX + Material Design 3 |
| Images | Glide 4.16.0 |
| Background | WorkManager 2.9.1 |
| Push | Firebase Cloud Messaging |

## Architecture

Lightweight MVC with a Service Layer. No local database -- all data lives in Supabase (PostgreSQL). Local state is limited to SharedPreferences for session tokens and notification dedup.

```
Activities (UI) --> Services (API) --> Remote APIs
                                       |
                         Supabase / Serper / Grok / FCM
```

Key patterns: Singleton services, Worker for background jobs, Adapter pattern for RecyclerViews, Manager classes for orchestrating workflows.

## External Services

| Service | Purpose |
|---------|---------|
| **Supabase** | Auth, product DB, watchlist, price snapshots, device tokens |
| **Serper API** | Google search targeting Twitter/X deal accounts |
| **Grok** (`grok-4.20-reasoning`) | Deal search, validation, price extraction via X search |
| **Firebase (FCM)** | Push notifications for price drops |
| **Google Cloud Storage** | Logo images (light/dark variants) |

## Project Structure

```
app/src/main/java/com/owly/pricetracker/
  activities/        5 Activity classes (Splash, Auth, Main, ProductDetail, Settings)
  adapters/          ProductAdapter, SnapshotAdapter
  models/            User, Product, PriceSnapshot
  services/          SupabaseService, GrokSearchService, OwlyFirebaseMessagingService
  utils/             SessionManager, ProductAnalysisManager, NotificationHelper,
                     PushTokenManager, NotificationPrefs, LogoLoader,
                     NonScrollableLinearLayoutManager
  work/              ProductAnalysisWorker, ProductAnalysisScheduler
  OwlyApplication.java
```

## Key Classes

### Activities
- **SplashActivity** -- Entry point. Restores session, refreshes token, routes to Auth or Main.
- **AuthActivity** -- Login/signup with email+password.
- **MainActivity** -- Product watchlist, add products (with autocomplete), trending chips, settings panel, trigger analysis.
- **ProductDetailActivity** -- Price history, manual analysis, target price management, sort by date/price.
- **SettingsActivity** -- Serper API key config, logout.

### Services
- **SupabaseService** -- Singleton. All Supabase REST calls (auth, CRUD, RPC). Uses OkHttp.
- **GrokSearchService** -- Singleton. Grok x_search for deal discovery, validation, and price extraction.

### Background Work
- **ProductAnalysisScheduler** -- Schedules hourly analysis via WorkManager with network constraint.
- **ProductAnalysisWorker** -- Fetches watchlist, runs Grok pipeline per product, saves snapshots, sends notifications.

### Utilities
- **ProductAnalysisManager** -- Orchestrates the search-validate-extract-save-notify pipeline. Used by both the worker and manual triggers.
- **SessionManager** -- SharedPreferences wrapper for user session and Serper key.
- **NotificationHelper** -- Builds and posts local notifications. Creates notification channel.
- **PushTokenManager** -- Registers/unregisters FCM tokens with Supabase.
- **NotificationPrefs** -- Tracks last-shown snapshot per product to prevent duplicate notifications.

## Database Schema (Supabase)

```
products         (id, name, normalized_name, language, status, current_price, last_updated)
product_watches  (id, user_id, product_id, status, target_price)
price_snapshots  (id, product_id, price, source_account, tweet_excerpt, tweet_url, tweet_date, captured_at)
api_credentials  (user_id, serper_key)
device_tokens    (id, user_id, token, platform)
```

## Build Configuration

API keys are injected via `local.properties` into `BuildConfig`:
- `SUPABASE_URL`, `SUPABASE_ANON_KEY` -- Supabase connection
- `GROK_API_KEY` -- Grok API access
- `FIREBASE_ENABLED` -- Boolean, true if `google-services.json` exists

View binding is enabled. ProGuard is configured for release builds.

## UI / Theming

- **Light mode**: White surfaces, black accent buttons, `#EEF2F7` background
- **Dark mode**: Navy surfaces (`#0D1117`), purple accent (`#7B6CF6`)
- Localization: Portuguese (BR)
- 6 layout XMLs, Material Design 3 components

## Analysis Pipeline Flow

1. User adds product or hourly worker triggers
2. Serper searches Twitter/X for deals (targeting known promo accounts)
3. Price extracted from search results (R$ format)
4. Tweet date parsed from search metadata
5. Snapshot saved to Supabase
6. Product's current_price updated if lower
7. Local + push notification sent if price <= target_price

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` -- API calls
- `POST_NOTIFICATIONS` -- Android 13+ runtime permission
- `RECEIVE_BOOT_COMPLETED` -- WorkManager background scheduling
