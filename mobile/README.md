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

`EXPO_PUBLIC_API_ORIGIN` is the Spring Boot origin. `EXPO_PUBLIC_ASSET_ORIGIN` is used for menu images that are not stored under `/uploads`.

| Where the app runs | Origin to use |
| --- | --- |
| iOS Simulator | `http://localhost:8082` |
| Android emulator | `http://localhost:8082` is rewritten to `http://10.0.2.2:8082` |
| Physical phone | Your computer's LAN address, for example `http://192.168.1.20:8082` |

The Android rewrite applies only while `Constants.isDevice` is false. A physical Android device must use the LAN address.

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

Sign-up, password reset, profile editing, live kitchen status, and table requests are not implemented in the screens. The request builders for the existing email verification APIs live in `src/api/recovery.ts`.

The logo and food photographs referenced by the website are not present in `frontend/public` in this repository. Dish photos are loaded from each menu item's `image` field. When an item has no image, the app shows a typographic monogram.

## Authentication

The mobile app stores the account JWT in Expo SecureStore. It does not use AsyncStorage for that token, and it does not call the Next.js cookie session. There is no refresh-token endpoint. Signing out deletes the token on the device; the server still accepts that JWT until it expires or the account token version changes.
