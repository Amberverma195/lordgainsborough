# PG-027 real website acceptance report

Completed 2026-10-27, America/Toronto. Starting main commit: 4dc1db11dedc52aefcf2057c524658b6e74f0613. This report covers local acceptance, with no production deployment or cloud database changes.

## Executed results

| Check | Observed result |
| --- | --- |
| Production frontend build, npm run build | Passed: compilation, TypeScript and static generation |
| Frontend unit tests, npm test | 28 passed across 5 files |
| Backend regression | 649 passed, 0 failures/errors/skips; BUILD SUCCESS |
| Real Chromium website suite, npm run test:e2e | 17 passed, 0 failed/skipped/flaky; 81.772 seconds |
| Scoped ESLint for changed React files/hooks | Passed |
| Flyway on fresh E2E database | Both existing migrations validated/applied; schema v2 |

Final browser run started 2026-10-27T22:03:31.313Z (18:03:31 Toronto), after the final production build. It used Next.js 16.2.6 in **production start mode**, real Spring Boot on 8082, real Socket.IO on 9092, and PostgreSQL 17.10 on loopback 54353. Chromium was Playwright's Chrome for Testing 156. Java was Corretto 25.0.3 compiling the backend's release-21 target; a separate Java 21 runtime was not exercised.

Dedicated final database: lgb_e2e_921bd188a26f4841a7402bd16c5dc7eb, inside the verified owned disposable local cluster. It is separate from the backend regression database. SMTP delivered only to a local rejecting/collecting sink for @example.test recipients. There were no real customer emails, payments or production orders.

## Browser coverage

All cases below passed in the final production-server run. The suite uses independent customer, kitchen and administrator browser contexts, actual navigation/forms/reloads, server responses and SQL assertions.

| Case | Flow verified |
| --- | --- |
| 01 | Administrator login, identity checks, settings save, allowed kitchen access |
| 02 | Signup, wrong then valid emailed OTP, session reload, cart quantity/removal, checkout and persisted order; 13.99 subtotal + 1.82 tax = 15.81 |
| 03 | Customer/kitchen messages, ready-time change, acceptance, ready, picked up, reload/reconnection, persisted messages and actual received Socket.IO events |
| 04 | Order review, duplicate prevention, administrator publish/hide and persisted moderation status |
| 05 | Reservation submission, administrator approval/deletion and SQL state |
| 06 | Administrator customer search by name, email and phone |
| 07 | Unauthenticated/ordinary customer administrator denial, privileged API denial and private order denial |
| 08 | Profile update, logout, wrong password, email and phone login |
| 09 | Forgot/reset password through actual local SMTP OTP, persisted token-version increment, old JWT rejected with 401, new login |
| 10 | Duplicate registration denied without a duplicate database user |
| 11 | Wrong tracking token and unrelated customer's real JWT denied, invalid socket authentication and remote-IP kitchen denial, private-event isolation checks |
| 12 | Second order, accepted/ready/no-show flow, live customer status and persisted no-show count |
| 13 | Administrator category/menu create/edit/delete, real browser image loading, persisted values and UI cleanup |
| 14 | Primary Home/Menu/About/Contact/Book a Table navigation and denied kitchen page |
| 15-17 | Actual customer checkout/orders at 1440 x 900, 768 x 1024 and 390 x 844; mobile navigation, cart, login dialog, buttons, no horizontal overflow and SQL-confirmed 1.70 totals |

KDS uses the existing IP allowlist policy. Local browser contexts share loopback; the negative remote-customer test uses the application's trusted loopback proxy with X-Forwarded-For 198.51.100.29. This verifies actual IP enforcement, **not a new staff-JWT requirement**. A customer on an allowed kitchen network is subject to the existing IP policy. Room isolation and negative auth checks use the actual socket server; browser traces also prove real events were received.

## Defects reproduced and fixed

| Defect and reproduction | Fix and recheck |
| --- | --- |
| Open KDS from a denied address: Verifying access remained indefinitely; unauthorized admin/private-order rendering also used asynchronous notFound calls | Store denial state, then throw notFound during render so the Next boundary handles it. Real unauthorized UI/API scenarios pass. |
| At mobile/tablet width, navbar hid its entire action group, preventing access to cart/login/booking | Preserve action buttons and add an accessible navigation toggle. All three viewport checkout journeys pass. |
| Existing About/Contact navigation targets returned 404 | Add small pages using existing address/phone information and working menu/reservation links; primary navigation case passes. |
| Customer and kitchen message APIs existed, but neither screen offered a composer | Add a shared accessible message panel wired to existing authorized endpoints and actual refreshed/server-pushed messages. Bidirectional persisted messaging passes. |
| Administrator loaded reviews/reservations/orders once; switching tabs after customer activity showed stale data | Refresh the relevant dataset on authorized tab activation. Customer-created reviews/reservations are moderated through the actual admin UI. |
| Icon-only cart controls lacked names; pages nested main landmarks | Add descriptive accessible control names and avoid the redundant main wrapper on pages that own one. Scoped lint/build and browser cases pass. |

No backend production source, JWT policy, database schema, prices, order calculations or permissions were changed. The React review also removed an unnecessary settings memo and moved the cart's pickup-disabled refresh into its existing asynchronous settings result.

Intermediate runs failed while reproducing these defects and while correcting test assumptions about actual labels, default ready times, checkout login auto-submission and asynchronous reset completion. Cascading failures after worker restart were shared-journey prerequisite failures. An early old-JWT request ran before reset completion; waiting for the real success heading and committed token version produced the expected 401. It was not established as a backend authentication defect. Redundant manual tracing was removed because Playwright already traces the contexts. These intermediate runs are not included in final pass totals.

## Diagnostics and artifacts

The final diagnostics contain 33 received Socket.IO event records and zero uncaught page errors. Nine failed requests were net::ERR_ABORTED during navigation, replacement searches or mutations. The 17 saved traces contain 18 console resource errors: expected negative-test 400/401/404/409 responses **and existing missing privacy/terms page prefetches**. They also contain 27 CSS-preload warnings. Therefore this is a passing acceptance suite, not a claim of a completely clean browser console or a finished legal/content review.

Local, Git-ignored evidence:

- frontend/playwright-report/index.html - full browser report.
- frontend/test-results/**/trace.zip - 17 browser traces, with console/network/actions; per-test customer/admin/kitchen screenshots alongside them.
- frontend/.e2e/results.json and browser-diagnostics.json - machine-readable results and sanitized event/request diagnostics.
- frontend/.e2e/responsive-desktop.png, responsive-tablet.png, responsive-mobile.png - successful real order confirmations; dialog screenshots also retained.
- frontend/.e2e/kds-denied-stuck-before.png, broken-about-before.png, broken-contact-before.png, stale-reviews-before.png - defect evidence.
- frontend/.e2e/final-playwright.log, final-build.log, final-unit.log, final-eslint.log, backend.log and frontend.log.
- backend/pg027-regression.log and backend/target/pg027-package.log.

Mobile and tablet success screenshots were visually inspected. Artifacts remain local because traces/state can contain **synthetic** credentials and OTPs. No real credentials are committed.

## Exact verification commands

Executed from backend in PowerShell:

```powershell
.\mvnw.cmd package '-DskipTests'
.\mvnw.cmd test '-Dtest.postgres.port=54353' '-Dtest.postgres.mandatory=true'
```

Packaging succeeded on October 26 at 23:09 Toronto; the separate 649-test regression finished October 27 at 17:51:46 Toronto. The regression command was test, not clean test, because the packaged application was concurrently in use on Windows.

Executed from frontend:

```powershell
npx playwright install chromium
$env:NEXT_PUBLIC_BACKEND_ORIGIN='http://localhost:8082'
$env:NEXT_PUBLIC_SOCKET_URL='http://localhost:9092'
npm run build
npm test
$env:E2E_FRONTEND_MODE='production'
node e2e/start.mjs
# Separate terminal, once services report ready:
npm run test:e2e
```

The exact scoped lint command (from frontend) was:

```powershell
npx eslint src/app/admin/page.tsx src/app/kds/page.tsx src/app/orders/`[id`]/OrderStatusClient.tsx src/app/about/page.tsx src/app/contact/page.tsx src/components/AppShell.tsx src/components/CartSidebar.tsx src/components/Navbar.tsx src/components/OrderMessages.tsx src/hooks/useRequireAdmin.ts src/hooks/useRequireKdsAccess.ts
```

ESLint was scoped to the changed/new React components, pages and authorization hooks; this is not a claim that a repository-wide lint command was run. Dependency installation added @playwright/test, pg, @types/pg, smtp-server, @types/smtp-server and bcryptjs as development dependencies. See frontend/e2e/README.md for clean-checkout setup, startup checks and report/trace commands.

## Remaining limits

Testing is local and synthetic. No hosted deployment, real payment settlement, public SMTP delivery, Java 21 runtime matrix or arbitrary network topology was tested. Existing privacy/terms links and placeholder social/legal content still require owner-supplied content. Dependency installation reported 18 audit advisories; broad dependency upgrades were outside these narrow fixes. Existing business policy and APIs were preserved. No Docker, MongoDB, data-copy migration or external cloud resource change was used.
