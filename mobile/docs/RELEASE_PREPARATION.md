# Customer release preparation

## Current audit - 22 November 2026

Inspected fetched main at `0524187`, including the recent cart-limit, tax-fallback, reorder identity, order-workflow and KDS fixes. This mobile task leaves website and backend source unchanged. No commit, push, deployment, cloud build, store submission or production test order was performed. The historical record below is retained for context; this section supersedes its readiness verdict and command counts.

### Confirmed fixes

- Cart loading now displays both `Calculating tax...` and `Calculating total...`; unknown zero-rate totals are hidden. Backend estimates, legitimate zero tax, valid cached configuration, and the 13% fallback notice remain. Checkout still requires backend settings and retains its existing idempotency and uncertainty protections.
- Cart saves have generation ownership. Account transitions and unmount invalidate old callbacks before they can release a new save lock, clear errors, remove pending markers or schedule retries. Account hydration guards local persistence. Pending local carts preserve decreases and deletions rather than merging server items back in. Clearing an account cart retains its pending marker until the empty server save succeeds.
- Late API 401 responses from a previous account generation cannot trigger global sign-out of the new account. Cart save errors remain owned by the cart operation and surface the existing session-expiry notice.
- Notification registration checks account lifecycle across permission, token and storage awaits. If registration completes after account change, cleanup is attempted using the original account. Registration errors cannot trigger unrelated account sign-out. Notification taps authorize the order before navigation, stop immediately on account change, deduplicate event IDs and clear handled launch responses. Existing opt-ins renew on foreground without requesting permission; revoked permission removes registration. The profile ignores stale opt-in callbacks.
- Node engine metadata now matches installed React Native's supported major-version ranges. No dependencies were added or upgraded.

### Current executed verification

Used Node **22.23.5**, because host-default Node 20.19.5 is unsupported. Invoke npm's CLI using the supported Node binary or make that Node installation first in PATH; invoking a system `npm.cmd` can otherwise select its adjacent Node 20 binary.

| Check | Actual result |
| --- | --- |
| `npm ci` under Node 22.23.5 | PASS; 25 moderate dependency advisories; no forced audit upgrade |
| `npm run typecheck` | PASS |
| `npm run lint` | PASS; 0 errors, 52 warnings |
| `npm test -- --runInBand --forceExit` | PASS; 52 suites, 282 tests; force-exit handle warning remains |
| `npx expo-doctor` under Node 22.23.5 | PASS; 20/20 checks |
| `npx expo export --platform android --output-dir dist` | PASS; fixture origins, JavaScript/assets/Hermes packaging only |
| `npx expo export --platform ios --output-dir dist-ios` | PASS; fixture origins, JavaScript/assets/Hermes packaging only; not a simulator or signing test |
| Local PostgreSQL/API/Socket.IO regression selection | PASS; 74 tests, no failures/errors/skips |
| `adb devices -l` | No attached devices |
| Signed Android preview / TestFlight / iOS simulator | BLOCKED; no verified project association or signed device artifact |

New regression coverage includes pending saves resolving/rejecting/expiring after an account switch, serialization of the new account's save, failed clear/restart without item resurrection, tax loading, late unauthorized replies, permission rejection, registration identity reuse, account change during permission, foreground renewal without permission prompts, unauthorized taps, duplicate taps and account changes during tap authorization. Existing tests cover cart limits/guest merging, order polling/reconnection/ticket renewal, background refresh, checkout idempotency/uncertainty and current-menu reorder behavior.

The backend selection is `PostgresOnlyApplicationIntegrationTest#checkoutPickupIsPersistedListedAndDeliveredToKitchen+authenticatedKitchenSocketAdmitsWithValidTicketAndRejectsAnonymousOrInvalidTicket+kitchenEventsObserveCommittedWritesAndRollbackEmitsNothing,KdsOrderTransitionsTest,OrderSchedulingValidatorTest`, using mandatory isolated PostgreSQL on port 54353. It verifies POST persistence, matching KDS IDs/dates, protected events and status transitions. It does **not** verify a phone placing an order or native notification delivery.

### Notifications gate and outstanding verification

Native notification permission is requested only by the explicit Profile enable action. Keep `EXPO_PUBLIC_ORDER_NOTIFICATIONS_ENABLED=false` for unverified releases. Backend `ORDER_NOTIFICATIONS_ENABLED` and `ORDER_NOTIFICATIONS_DELIVERY_VERIFIED` default to false. Existing encrypted device storage, unique outbox events, minimal notification text, Expo push-ticket/receipt handling, invalid-token removal, session-revocation checks and account-deletion cascades are preserved. Do not mark delivery verified based on mocks, bundling or a successful push ticket alone.

Before any production enablement, authorized staging tests must demonstrate signed-device permission accept/reject, foreground/background/terminated delivery, notification tap ownership, account switches, logout, deletion, permission revocation, expired sessions, token replacement and provider receipts. Network failures during cleanup remain retryable; a failed opt-out must not be represented as success. Backend revocation/expiry prevents delivery from invalidated sessions. Actual provider delivery remains unverified.

### Build setup required

The checkout has no configured `extra.eas.projectId` and no supplied `EXPO_PROJECT_ID`, `EXPO_OWNER` or approved staging origins. The operator explicitly confirmed these are unverified/unavailable on 22 November 2026 and instructed that they remain unset. Repository production endpoints are not approved staging targets. A cached Expo login alone does not establish project ownership or signing authorization. Do not create a replacement project. The existing app identifiers remain `com.lordgainsborough.customer` on Android and iOS; display name remains Lord Gainsborough. Existing preview APK, iOS simulator, TestFlight and production AAB profiles are retained. Icon, adaptive icon foreground/background/monochrome and splash PNGs are present at 1024×1024; actual appearance still needs signed-device testing.

From `mobile/`, with supported Node active and operator-approved values configured in the matching EAS environments:

```sh
npx eas-cli@latest login
# Only after obtaining the existing project UUID:
npx eas-cli@latest init --id <existing-project-uuid>
npx eas-cli@latest project:info
npx eas-cli@latest credentials --platform android
npx eas-cli@latest build --platform android --profile preview
# macOS simulator artifact or Apple-signed TestFlight build, respectively:
npx eas-cli@latest build --platform ios --profile preview-ios-simulator
npx eas-cli@latest credentials --platform ios
npx eas-cli@latest build --platform ios --profile testflight
```

No `submit` command is authorized. Android needs the existing keystore or authorized EAS-managed signing. iOS needs Apple Developer enrollment, the matching App Store Connect app, distribution certificate and provisioning; physical internal distribution also needs registered UDIDs. Windows cannot run Xcode/iOS Simulator. Retain existing remote version counters and confirm they match store records before building.

App/EAS configuration required: `EXPO_PROJECT_ID`, optional authorized `EXPO_OWNER`, `EXPO_PUBLIC_API_ORIGIN`, `EXPO_PUBLIC_ASSET_ORIGIN`, `EXPO_PUBLIC_SOCKET_ORIGIN`, and `EXPO_PUBLIC_ORDER_NOTIFICATIONS_ENABLED=false`. Public variables are embedded in the app. The API, asset and socket origins must be independently verified public HTTPS endpoints; preview/TestFlight must use approved staging resources.

Notification-related **backend-only** configuration: `ORDER_NOTIFICATIONS_ENABLED`, `ORDER_NOTIFICATIONS_DELIVERY_VERIFIED`, `EXPO_PUSH_ACCESS_TOKEN`, and the existing persistent `PASSWORD_RESET_QUEUE_ENCRYPTION_KEY` used by encrypted payload storage. Configure FCM/APNs credentials for the existing Expo project with the authorized team. Never put these backend/provider secrets into `EXPO_PUBLIC_*` values or Git.

### Customer-release acceptance checklist - pending operator/device work

- [ ] Install a signed staging APK and signed iOS build; inspect icon, adaptive/monochrome icon, splash, dark theme, safe areas and keyboards. Approve draft assets.
- [ ] Complete the full requested phone journey: menu, dish details, several cart items/quantities, login and sync, Toronto pickup, disposable order, persisted/KDS ticket, Accept/Ready/Picked Up reflected on phone, restart/history and current-price reorder.
- [ ] Repeat with socket loss/reconnect/ticket renewal, background/foreground, offline saves, A/B account transitions, and interrupted checkout; verify one persisted order per idempotency key.
- [ ] Complete signed Android/iOS notification tests and provider receipt verification before changing the production gates.
- [ ] Confirm public HTTPS privacy, Terms, account-deletion and support URLs from the signed app. Do not assume historical publication status is current.
- [ ] Operator approves `store/listing.json`, business/support contact, age/content rating, app identifiers/version counters and reviewer credentials/instructions.
- [ ] Capture real Android/iPhone/iPad screenshots and required Play feature graphics. Do not use fabricated screens.
- [ ] Complete Google Play Data Safety and account-deletion declarations; Apple App Privacy, permission disclosures, privacy-manifest/required-reason API review and encryption/export declarations against the final signed binary and actual SDK/network inventory.
- [ ] Confirm Play Console/App Store Connect enrollment, signing ownership, production environment, approved FCM/APNs/Expo credentials, and staged release authorization.

The complete native ordering workflow, signed-device delivery and customer release are **not verified** by this audit. Missing external prerequisites remain release blockers; automated checks and local API tests are evidence for code behavior only. Build-profile guidance follows [Expo build configuration](https://docs.expo.dev/build/eas-json/); native notification integration was checked against [Expo SDK 58 Notifications](https://docs.expo.dev/versions/v58.0.0/sdk/notifications/).

### Files changed in this mobile audit

All paths are relative to `mobile/`:

- Configuration/documentation: `.env.example`, `README.md`, `docs/RELEASE_PREPARATION.md`, `package.json`, `package-lock.json`.
- API/account handling: `src/api/client.ts`, `src/api/orders.ts`.
- Cart/profile: `src/app/(tabs)/cart.tsx`, `src/app/(tabs)/profile.tsx`, `src/cart/CartContext.tsx`, `src/cart/tax.ts`.
- Native notifications: `src/notifications/orderAlerts.ts`, `src/notifications/OrderAlertBridge.tsx`, `src/notifications/nativeModule.ts`.
- Regression tests: `src/cart/__tests__/cartTaxFallback.test.tsx`, `src/cart/__tests__/syncRace.test.tsx`, `src/api/__tests__/staleUnauthorized.test.ts`, `src/notifications/__tests__/enabled.test.ts`, `src/notifications/__tests__/bridge.test.tsx`.

## Historical audit - 21 November 2026

Audited main at eea2dd1 (equal to origin/main when fetched). Changes remain local. No deployments, store submissions, pushes or production writes were made.

## Implemented

- Profile links to permanent account deletion, Terms, Privacy, support and public web deletion information. Current-password confirmation uses DELETE /api/auth/me with a Bearer JWT and {password}. Errors cover incorrect passwords (403), expired sessions (401), throttling (429) and ACCOUNT_HAS_ACTIVE_ORDERS (409).
- Successful deletion cancels React Query requests, clears its cache and confirmed-order cache, deletes the secure JWT and indexed account/guest tracking credentials, and removes local cart, pickup and checkout keys. Cleanup failures are surfaced; device storage failures cannot be treated as successful erasure. Account lifecycle notifications stop queued cart saves and clear in-memory cart fields.
- Native reservation form captures name, email, Canadian/US phone, date, time, party size and requests. The public POST /api/reservations returns a PENDING request. No online payments were added. Loading, validation, throttling, uncertain/offline submission and telephone fallback are presented. A persistent submission marker blocks accidental repeats after timeouts/restarts. Customers must check with the restaurant before explicitly starting another request.
- Password recovery accepts email or Canadian/US telephone (the backend intentionally carries either identifier in its email JSON field). Codes go to the account email, not SMS. It consumes serverTime, nextResendAt, codeExpiresAt, challengeExpiresAt and resendsRemaining. Absolute deadlines correct for server/device clock offset. Resend quota and cooldown are separate; expired challenges offer Start New Reset. Failed verification remains actionable. Focus/account lifecycle guards discard stale replies and clear memory-only password/OTP values.
- Checkout uncertainty survives edits and application restarts using an account-scoped marker; submitting a new fingerprint requires explicit acknowledgement after checking existing orders. Existing idempotency keys remain stable for a matching request. An interrupted request is never automatically resubmitted. Account changes preserve uncertainty for the original account.
- Release origins use literal Expo public-variable references for bundling, public HTTPS validation and fail-closed release configuration. Localhost defaults remain development-only.
- Expo SDK patch updates, development client, splash plugin, EAS build profiles, draft LG icon/adaptive/splash assets and store listing copy are prepared. Existing identifiers remain com.lordgainsborough.customer on both platforms.

## API audit

Reservation POST is public and rate-limited; reservation GET, PATCH and DELETE require administrator authorization. The mapper enforces valid email, phone length, party size 1-10, special requests <=1000 characters, a real future date within one year, half-hour slots within dining hours, and America/Toronto same-day cutoffs. Client status/id fields cannot confirm a reservation. There is no table-inventory availability or idempotency-key API; requests require human confirmation. We did not invent either capability.

Account deletion already uses current password, JWT authentication, rate limiting, active-order checks and transactional cleanup/revocation. Backend tests cover failed credentials, blocked orders, dependent records, stale JWT/cart writes, rollback and concurrent writes. The website already had authenticated deletion in Profile; only a public /delete-account information route and footer link were added.

Pickup schedules already use Intl with America/Toronto, date validation and server scheduling checks. Existing tests cover account cart isolation, synchronization, ticket authentication, order refresh, polling fallback and checkout idempotency. Socket.IO uses short-lived tickets with websocket/polling transports; order detail separately polls while active. These automated checks do not establish that staging networking works on actual phones.

## Build profiles and signing

Use Node >=22.13.0. Run commands from mobile/. Dependencies were checked against Expo SDK 58 documentation.

1. Log in with the authorized Expo team account using npx eas-cli@latest login. Use npx eas-cli@latest project:info to identify the EXISTING project, or npx eas-cli@latest init --id <existing-project-uuid> to link it. Do not create a replacement project unintentionally.
2. Set EXPO_PROJECT_ID (actual project UUID) and EXPO_OWNER as needed in the EAS environment. These are association metadata, not backend secrets. app.config.js places the UUID in extra.eas.projectId. This checkout has no verified Expo project association; no UUID was invented.
3. Set EXPO_PUBLIC_API_ORIGIN, EXPO_PUBLIC_SOCKET_ORIGIN and EXPO_PUBLIC_ASSET_ORIGIN in the EAS development, preview and production environments. Use verified staging HTTPS origins for preview/TestFlight. Values are public in the binary. EXPO_PUBLIC_SOCKET_URL is a supported runtime legacy alias; release configuration deliberately requires the canonical SOCKET_ORIGIN setting. Never set database, JWT, Resend or queue-encryption secrets in the app.
4. Run npx eas-cli@latest build --platform android --profile preview for an installable APK. preview on iOS is internal/ad hoc and requires registered device UDIDs and Apple signing credentials. preview-ios-simulator is unsigned simulator distribution and cannot go to TestFlight.
5. testflight uses store distribution with preview environment and is compatible with TestFlight after signing/upload. production uses store distribution, production environment and Android app-bundle. Store builds automatically increment remote build numbers; user-facing version starts at 1.0.0. Configure/match remote version counters to any existing store records before the first build.
6. Let the authorized team manage Android keystore and iOS distribution certificates/provisioning via npx eas-cli@latest credentials. Keep signing files and service account/App Store Connect private keys outside Git. Review Apple encryption declarations before submission.
7. No EAS builds were requested from the cloud in this task. Project association, environment values and team signing credentials are still required. No submission profiles/credentials are fabricated.

Profiles follow https://docs.expo.dev/build/eas-json/ and versioning follows https://docs.expo.dev/build-reference/app-versions/. Expo public-variable bundling requires literal access: https://docs.expo.dev/guides/environment-variables/.

## Store work remaining

Publish the website /delete-account route and footer with the normal approved website release, then enter its real public URL in Play Console. Verify this route, /privacy, /terms and support links from a signed build before submitting. The local web entry is not yet live.

Approve the draft LG monogram assets and listing in store/listing.json. Replace drafts if the operator requires their official crest. Prepare real Android phone and iPhone/iPad screenshots, store feature graphics, promotional text, keywords and age/content ratings. Complete Google Data Safety and Apple App Privacy disclosures based on the actual SDK/network audit; check Apple privacy manifests/required-reason APIs and permission prompts in the signed binaries. Supply reviewer access/test instructions, restaurant contact details, developer enrollment, signing, store records and export compliance declarations. Do not add online payments without approval.

## Device acceptance gate - not performed

adb devices -l reported no connected devices. This Windows workspace has no iOS simulator/Xcode. No APK/IPA or TestFlight build was produced, so Android/iOS device-level checks are SKIPPED, not passed.

Use a disposable/staging backend and synthetic accounts only. Confirm the backend deployment includes its persistent queue encryption key and working email provider before recovery tests. On both platforms:

- Install preview; inspect adaptive icon, splash, safe areas, font loading and keyboard behavior.
- Confirm every menu image: relative static assets use ASSET_ORIGIN, uploads use API_ORIGIN, HTTPS remote images work, missing/broken images have a fallback.
- Sign in as A, save a cart, force-stop/restart, verify server sync; switch to B and verify isolation. Delete A and inspect local secure/session/cart/checkout data.
- Test reset by email and phone, expired code/challenge, exhausted quota, rate limiting, backgrounding, back navigation and account switching. Verify no plaintext password/OTP device keys.
- Test Toronto pickup times from another device timezone and across DST boundaries. Submit staging orders with dropped responses and force-stop/restart; check the backend has one order for the same idempotency key. Changing fields must not dismiss uncertainty silently.
- Disable sockets and confirm polling still refreshes active orders; reconnect and verify ticket renewal, account isolation and terminal order states.
- Submit one staging reservation; verify PENDING, reject invalid/past/closed slots and preserve uncertainty after dropped responses. Contact confirmation remains a manual restaurant action.
- Resolve active orders, delete the staging account with wrong and correct passwords, test expired sessions and rate limits, then verify revocation and cleanup.

## Verification record

See the final report below for executed command results. Full mobile Jest currently requires --forceExit because pre-existing suites retain asynchronous handles; this is a test-runner limitation, not a device test. ESLint warnings in test mocks are reported separately from errors. npm install reports 25 moderate dependency advisories; no forced major-version audit fix was applied.


## Final executed results

| Check | Actual result |
| --- | --- |
| Mobile TypeScript, tsc --noEmit | PASS |
| Mobile ESLint, expo lint | PASS: 0 errors, 40 warnings (primarily test mock import conventions) |
| Mobile Jest, --runInBand --forceExit | PASS: 35 suites, 178 tests, no skipped tests |
| Expo Doctor | PASS: 20/20 checks after SDK patch alignment |
| Native Android Metro/Hermes export | PASS, local dist/ only |
| Native iOS Metro/Hermes export | PASS, local dist-ios/ only |
| Website TypeScript, tsc --noEmit | PASS |
| PostgresOnlyApplicationIntegrationTest | PASS: 40 tests |
| PostgresPasswordResetOtpPersistenceIntegrationTest | PASS: 54 tests |
| PostgresReservationControllerIntegrationTest | PASS: 40 tests after fixture correction |
| Android connected device check | No devices attached: device-level testing SKIPPED |
| iOS device/simulator check | Unavailable on Windows: device-level testing SKIPPED |
| Signed Android/iOS preview builds | NOT CREATED; existing Expo project association, environment and signing setup remain unverified |

Backend tests used the exclusively owned loopback PostgreSQL 17 cluster on port 54353 and synthetic disposable databases, with mocked outbound email. The initial reservation suite stopped on an outdated migration-count assertion (expected five migrations after V1; current schema has nine). The fixture now derives the migration count and all 40 reservation tests pass on rerun. The backend suites passed 134 tests across the runs; this was not one clean combined initial run.

Native exports used explicit fixture HTTPS origins under test.invalid, so no production requests or customer orders were submitted. Exports validate JavaScript/assets/Hermes packaging, not signing, installation, network reachability or native device behavior. Their output directories are ignored by Git. A Node 22.23.5 runner was used for final checks because the default host Node 20 is unsupported by Expo 58.

Tests formerly under src/app/auth/__tests__ and src/app/profile/__tests__ were relocated to src/screens/... so Expo Router cannot treat test files as screens. New screen tests also live outside the route directory. DishImage now provides a fallback on load failures and rejects cleartext image URLs in release mode. A persistent confirmed-order receipt marker also guards the crash window between successful order creation and asynchronous cart clearing; checking orders is required before the next submission.

## Preview readiness verdict

Code/configuration and native bundles are prepared for the signing/setup step. A distributable preview is NOT yet verified. Missing external prerequisites: the real existing Expo project UUID/owner, authorized EAS login, staging public HTTPS API/asset/Socket.IO values and Android/iOS signing credentials/device registrations. Remaining product gates are the device acceptance checklist, official asset/metadata approval, public website deletion-page publication, privacy/store disclosures and store review materials. Reservations remain requests confirmed by restaurant staff; automated capacity checking is not an existing backend capability.

## Files changed

- backend/src/test/java/com/lordgainsborough/restaurant/controller/PostgresPg020IntegrationSupport.java
- frontend/src/app/delete-account/page.tsx
- frontend/src/components/Footer.tsx
- mobile/.gitignore
- mobile/app.config.js
- mobile/app.json
- mobile/assets/android-icon-background.png
- mobile/assets/android-icon-foreground.png
- mobile/assets/android-icon-monochrome.png
- mobile/assets/brand-icon.svg
- mobile/assets/favicon.png
- mobile/assets/icon.png
- mobile/assets/splash-icon.png
- mobile/docs/RELEASE_PREPARATION.md
- mobile/eas.json
- mobile/package-lock.json
- mobile/package.json
- mobile/src/api/__tests__/auth.test.ts
- mobile/src/api/__tests__/releaseConfig.test.ts
- mobile/src/api/auth.ts
- mobile/src/api/legal.ts
- mobile/src/api/runtime.ts
- mobile/src/app/(tabs)/profile.tsx
- mobile/src/app/auth/__tests__/forgotPassword.test.tsx
- mobile/src/app/auth/__tests__/signup.test.tsx
- mobile/src/app/auth/__tests__/signupConsent.test.tsx
- mobile/src/app/auth/forgot-password.tsx
- mobile/src/app/checkout.tsx
- mobile/src/app/profile/__tests__/edit.test.tsx
- mobile/src/app/profile/delete.tsx
- mobile/src/app/reserve.tsx
- mobile/src/auth/AuthContext.tsx
- mobile/src/auth/__tests__/deleteAccountData.test.ts
- mobile/src/auth/deleteAccountData.ts
- mobile/src/auth/lifecycle.ts
- mobile/src/auth/secureSession.ts
- mobile/src/cart/CartContext.tsx
- mobile/src/checkout/__tests__/uncertainRecovery.test.tsx
- mobile/src/checkout/idempotency.ts
- mobile/src/components/DishImage.tsx
- mobile/src/screens/__tests__/reserve.test.tsx
- mobile/src/screens/auth/__tests__/forgotPassword.test.tsx
- mobile/src/screens/auth/__tests__/signup.test.tsx
- mobile/src/screens/auth/__tests__/signupConsent.test.tsx
- mobile/src/screens/profile/__tests__/delete.test.tsx
- mobile/src/screens/profile/__tests__/edit.test.tsx
- mobile/store/listing.json
- mobile/.env.example
