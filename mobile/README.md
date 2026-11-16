# Lord Gainsborough customer app

Expo customer app for pickup ordering. It uses the existing Spring Boot API in `backend/` and does not replace the Next.js website, administrator dashboard, or kitchen display.

## Requirements

- Node.js 22.13 or newer. Expo SDK 58 and React Native 0.88 refuse older Node versions.
- The restaurant API running locally, normally on port 8082. See the repository README and `backend/TESTING.md`.
- Expo Go for SDK 58 on a phone, or an Android emulator. iOS Simulator requires macOS.

Do not point this app at production when placing test orders.

## Setup

```bash
cd mobile
npm install
copy .env.example .env
```

`EXPO_PUBLIC_API_ORIGIN` is the Spring Boot origin. `EXPO_PUBLIC_ASSET_ORIGIN` is used for menu images that are not stored under `/uploads`. `EXPO_PUBLIC_SOCKET_URL` is the Socket.IO server origin for live order tracking.

| Where the app runs | API origin | Socket.IO origin |
| --- | --- | --- |
| iOS Simulator | `http://localhost:8082` | `http://localhost:9092` |
| Android emulator | `http://localhost:8082` (rewritten to `http://10.0.2.2:8082`) | `http://localhost:9092` (rewritten to `http://10.0.2.2:9092`) |
| Physical phone (dev) | Your computer's LAN address, e.g. `http://192.168.1.20:8082` | Your computer's LAN address, e.g. `http://192.168.1.20:9092` |
| Production build | `https://api.lordgainsboroughrestaurant.ca` | `https://socket.lordgainsboroughrestaurant.ca` (or your production Socket.IO origin) |

The Android rewrite applies only while `Constants.isDevice` is false. A physical Android device must use the LAN address.

### Required Environment Variables

All variables exposed to the mobile app must begin with the `EXPO_PUBLIC_` prefix:

- `EXPO_PUBLIC_API_ORIGIN`: Base URL for the Spring Boot REST API. Defaults to `http://localhost:8082`.
- `EXPO_PUBLIC_ASSET_ORIGIN`: Base URL for dish images and static assets not stored under `/uploads`. Defaults to `EXPO_PUBLIC_API_ORIGIN`.
- `EXPO_PUBLIC_SOCKET_URL` (or `EXPO_PUBLIC_SOCKET_ORIGIN`): Base URL for the Socket.IO server that powers real-time order status updates (`order.updated`). Defaults to `http://localhost:9092`.
  > **Production requirement:** Socket.IO runs on a separate port or host from standard HTTP API traffic. In production builds (e.g. EAS Build, APK, AAB, IPA, TestFlight), omitting `EXPO_PUBLIC_SOCKET_URL` causes real-time socket connections to fall back to `http://localhost:9092` and fail on devices. Always define `EXPO_PUBLIC_SOCKET_URL` in production build profiles.

## Run

```bash
npm start
```

Then open the project in Expo Go, an Android emulator, or press `a` from the Expo CLI. `npm run ios` only works on macOS.

## Checks

```bash
npm test
npm run typecheck
npm run lint
```

Tests use fixtures and do not call a live API.

## What this version includes

Home, menu, cart, sign-in, pickup checkout, order confirmation, and account order history. Menu rows come from `GET /api/menu`, `GET /api/categories`, and `GET /api/menu/most-loved`. Checkout submits `POST /api/orders` and shows the order only after the API confirms it. Payment is at pickup.

Sign-up, password reset, profile editing, live order status polling, and real-time Socket.IO status updates are implemented. Table requests are not implemented in the mobile screens.

The logo and food photographs referenced by the website are not present in `frontend/public` in this repository. Dish photos are loaded from each menu item's `image` field. When an item has no image, the app shows a typographic monogram.

## Authentication & Credential Isolation

- **Account Session**: The mobile app stores the account JWT in Expo `SecureStore` (`lgb.session.token`). It does not use `AsyncStorage` for that token, and it does not use cookies. Signing out deletes the session token on the device; the server still accepts that JWT until it expires or the account token version changes.
- **Order-Tracking Token Isolation**: Order-tracking credentials returned during checkout are stored in `SecureStore` scoped strictly to the signed-in account (`lgb.track.u.<email>.<orderId>.v1`) or guest namespace (`lgb.track.guest.<orderId>.v1`). Un-scoped legacy keys are purged automatically.
- **Account-Switch & Logout Protection**: When users switch accounts or sign out, in-memory caches, idempotency keys, and stored tracking credentials belonging to the previous account are cleared. A newly signed-in account cannot access another account's tracking credentials.
- **Authorized Order Recovery & App Restarts**: On app restarts, active account sessions are restored and authorized tracking credentials remain accessible to the owning account for order recovery.
- **Stale-Request Cancellation**: Order detail screens cancel in-flight tracking credential lookups when switching accounts or signing out, preventing stale tokens from leaking into active queries or socket connections.
