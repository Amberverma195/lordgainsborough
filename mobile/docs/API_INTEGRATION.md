# Mobile API integration

The customer app calls the Spring Boot API directly. Native requests are not subject to browser CORS. The Next.js app keeps JWTs in HttpOnly cookies; this app sends `Authorization: Bearer <jwt>` and stores that JWT in Expo SecureStore.

Default local origin: `http://localhost:8082` (`server.port`). Android emulators rewrite `localhost` and `127.0.0.1` to `10.0.2.2`. Physical devices need the computer's LAN address. Do not place test orders against production.

## Public catalog

`GET /api/menu`, `GET /api/menu/most-loved`, `GET /api/menu/{id}`, and `GET /api/categories` are anonymous. Other menu and category methods require an `ADMIN` token. A failed admin check is HTTP 404, not 403.

Menu item JSON:

- `id`, `name`, `description`, `price`, `category`, `image`
- `available` (boolean)
- `mostLoved` (Jackson name for `isMostLoved`)

`category` on a dish is the category name string, not necessarily the category id. Categories are `{ id, name, description, displayOrder }`.

Menu images may be absolute URLs or paths. Paths beginning with `/uploads` are loaded from the API origin. Other relative paths use `EXPO_PUBLIC_ASSET_ORIGIN`. The website's `NavLOGO.png` and `restaurant-interior.png` files are not in this checkout.

An empty most-loved list falls through to available dishes from `GET /api/menu`. A failed menu request is shown as an error. The app does not substitute sample dishes.

## Store settings

`GET /api/store-settings` is public and returns:

`id`, `taxRate`, `defaultReadyMinutes`, `storeName`, `contactPhone`, `contactEmail`, `pickupOrdersEnabled`.

`kdsAllowedIp` is omitted unless the caller is an admin. The app must not call `PUT /api/store-settings`. There is no client-side fallback tax rate when this request fails; checkout stays blocked until the real rate loads.

The model default tax rate is `0.13` and the default ready time is 25 minutes. Those defaults are server data, not values the app invents after an error.

## Authentication

`POST /api/auth/login`

```json
{ "emailOrPhone": "guest@example.com", "password": "secret" }
```

The legacy `email` field is still accepted by the server. The identifier may be an email or a 10-digit phone number.

Success is HTTP 200 with `code: "LOGIN_SUCCESS"`, `token`, `name`, `email`, `phone`, `memberSince`, `role`, `canUpdateProfile`, `nextProfileUpdateAt`, `noShowCount`, `pickupDisabled`, and `serverTime`.

Roles are `MEMBER`, `STAFF`, and `ADMIN`. Staff and admin accounts can sign in, but this app does not call administrator or kitchen routes.

Failures:

- `LOGIN_INVALID_REQUEST` — 400
- `LOGIN_INVALID_CREDENTIALS` — 401, same message whether the account exists
- `LOGIN_RATE_LIMITED` — 429, with `retryAfterSeconds` and a `Retry-After` header

`GET /api/auth/me` with `Authorization: Bearer` returns the same account fields. A missing, invalid, or expired token is HTTP 401 with a plain string body such as `Expired or invalid token.`

JWT lifetime defaults to 86400000 ms (24 hours). There is no refresh-token endpoint. Password reset increments the account token version and invalidates older JWTs. Mobile sign-out calls `POST /api/auth/logout` with the current Bearer JWT. HTTP 204 confirms durable current-session revocation; HTTP 401 means it is already invalid. Local cleanup follows that acknowledgment. Network/server errors retain the local session for retry. Other device JWTs remain valid; new tokens carry unique IDs. The website cookie logout route is separate.

`pickupDisabled` is true when `noShowCount` is at least 3.

Signup and password reset are not wired to screens yet. The existing endpoints, which the next milestone must keep intact, are:

- `POST /api/auth/signup/send-otp` with `Idempotency-Key`, optional `X-Captcha-Token`, and `X-Signup-Attempt-Token`
- `POST /api/auth/signup`
- `GET /api/auth/signup/otp-status`
- `POST /api/auth/forgot-password/send-otp`
- `POST /api/auth/forgot-password/resend-otp`
- `POST /api/auth/forgot-password/reset`

Those flows return stable `code` values (`OTP_SENT`, `OTP_CAPTCHA_REQUIRED`, `OTP_RATE_LIMITED`, `RESET_CODE_INVALID`, and the other codes in `SignupOtpCode`). Captcha and attempt-token checks must not be bypassed.

`PUT /api/auth/profile` currently changes the name only. `GET /api/auth/orders` returns the signed-in customer's orders.

## Cart

`GET` and `PUT /api/cart` require a bearer token. `PUT` replaces the saved snapshot with an array of `{ menuItemId, name, description, price, category, image, quantity }`. Unauthorized responses are the plain string `Missing or invalid token.` Invalid rows return `{ code: "INVALID_CART_ITEM", message }`.

The device cart is the working copy and is stored in AsyncStorage. Prices are replaced from the menu response when it loads. The order API prices the cart again; client totals are estimates.

## Pickup orders

`POST /api/orders` requires a bearer token. Send `Idempotency-Key` (maximum 128 characters) and the same key in the body, matching the website. The server ignores client prices and recomputes each line from the menu. It also replaces the customer name, email, and phone from the authenticated account and returns 403 `CUSTOMER_IDENTITY_MISMATCH` if a supplied email or phone belongs to someone else.

Scheduling uses `America/Toronto`, 11:00 AM until dining closing time, 30-minute slots, or `asap`. Dates are `YYYY-MM-DD`, cannot be in the past, and cannot be more than 90 days ahead. `asap` is rejected after the daily closing time local time:

- Monday–Thursday: 11:00 AM–8:00 PM (Dining: 9:00 AM–8:00 PM)
- Friday–Saturday: 11:00 AM–8:30 PM (Dining: 9:00 AM–8:30 PM)
- Sunday: 11:00 AM–6:00 PM (Dining: 10:00 AM–6:00 PM)

Verified restaurant details used by the app: Lord Gainsborough, established 1985, 1800 Hyde Park Rd, London, ON N6H 0K1, 519-472-4168.

Important order responses:

| Status | Code | Meaning |
| --- | --- | --- |
| 401 | `AUTHENTICATION_REQUIRED` / `INVALID_TOKEN` | Sign in again |
| 400 | `EMPTY_CART`, `CART_ITEM_CHANGED`, `CART_ITEM_UNAVAILABLE`, `INVALID_PICKUP_DATE`, `INVALID_PICKUP_TIME`, `PAST_PICKUP_TIME`, `ORDER_TOTAL_EXCEEDED` | Fix the cart or pickup |
| 400 | `PICKUP_TIME_CLOSED` | Too late for an ASAP order today |
| 403 | `PICKUP_DISABLED_NO_SHOW` | Message: `Pickup disabled due to recent no-show history` |
| 403 | `CUSTOMER_IDENTITY_MISMATCH` | Contact details do not match the account |
| 409 | `IDEMPOTENT_PAYLOAD_MISMATCH` | Same key, different order. The app clears the key |
| 429 | `ACTIVE_ORDERS_LIMIT_EXCEEDED` / `RATE_LIMIT_EXCEEDED` | Respect `Retry-After` |
| 503 | `PICKUP_ORDERS_PAUSED` | Pickup is switched off |

Default limits from `OrderRateLimitProperties`: 30 lines, 20 of one item, 50 items total, 3 active orders, and a $1000 total. The server remains authoritative.

A successful body is `{ order, trackingToken }`. `order` is an `OrderStatusResponse`: `id`, pickup schedule, `status` (`NEW`, `ACCEPTED`, `READY`, `PICKED_UP`, `NO_SHOW`), items, `subtotal`, `tax`, `total`, and timestamps. The app shows a confirmation only when `order.id` is present. The same idempotency key returns that original order when the payload matches, so a lost response can be retried without a second order.

`X-Order-Tracking-Token` can be sent with `GET /api/orders/{id}`. The app stores it in SecureStore. Account ownership also grants access.

Payment is collected at the restaurant. There is no Stripe, Apple Pay, or Google Pay integration.

## Left for later

`GET /api/orders/{id}/socket-ticket` returns a one-minute Socket.IO ticket. It is not an account JWT. Live status and customer messages (`POST /api/orders/{id}/messages`) are the next order milestone.

`POST /api/reservations` is public, but the app does not submit reservation requests yet. The home screen links to a contact screen instead of a fake booking form.

Operational KDS routes are IP-restricted and return 404 when the caller is outside the kitchen network. Admin routes require role `ADMIN`. This app does not call either.
