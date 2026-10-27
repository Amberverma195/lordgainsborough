# Local website acceptance tests

Runs real Chromium against Next.js, Spring Boot, Socket.IO and PostgreSQL. No HTTP, authentication, persistence or socket mocks; no Docker or MongoDB.

Prerequisites: Node/npm, Java compatible with the backend, a packaged backend JAR, and the existing disposable PostgreSQL 17 cluster. The launcher verifies the server major version and an owned temporary data-directory name (lgb_pg017_<32 hex>/data or lgb_test_<32 hex>/data). It refuses other clusters. Default PostgreSQL port is 54353; override E2E_POSTGRES_PORT if needed. The postgres/postgres credential is solely the existing disposable local-cluster fixture, never a website account credential. This launcher does not install or start PostgreSQL.

From backend (PowerShell):

```powershell
.\mvnw.cmd package '-DskipTests'
.\mvnw.cmd test '-Dtest.postgres.port=54353' '-Dtest.postgres.mandatory=true'
```

From frontend:

```powershell
npm ci
npx playwright install chromium
$env:NEXT_PUBLIC_BACKEND_ORIGIN='http://localhost:8082'
$env:NEXT_PUBLIC_SOCKET_URL='http://localhost:9092'
npm run build
npm test
$env:E2E_FRONTEND_MODE='production'
npm run e2e:up
```

Keep that terminal running. In a second frontend terminal:

```powershell
npm run test:e2e
npm run e2e:report
```

Ports 3000, 8082, 9092, 2525 and 8025 must be free before launch (management also uses 8083). The launcher creates a unique lgb_e2e_<32 hex> database, runs the actual backend/Flyway initialization, seeds one synthetic administrator and loopback kitchen allowlist, and starts the frontend. E2E_FRONTEND_MODE defaults to development if omitted. Database names are checked again before SQL assertions. Business transactions run through the actual website; direct SQL is limited to bootstrap and verification.

The SMTP sink binds loopback, accepts only @example.test recipients and never forwards mail. Signup/reset use actual JavaMail delivery and an in-memory local OTP inbox. Administrator passwords, JWT secrets and abuse-hashing secrets are randomly generated. Local .e2e/state.json contains the synthetic administrator password; do not publish it. Traces can contain synthetic credentials and OTPs, so artifacts are ignored by Git.

Run the full 17-case journey in its numbered order with one worker. Later cases intentionally share the customer/order contexts created earlier; filtering a later case alone does not create those prerequisites. Tests use unique synthetic customer identities. Start a fresh launcher/database for a reproducible clean run.

Artifacts: .e2e/results.json, .e2e/browser-diagnostics.json, .e2e/backend.log, .e2e/frontend.log, responsive screenshots, test-results/**/trace.zip and per-actor screenshots, plus playwright-report/index.html. Playwright traces record browser console output and network activity. Use npx playwright show-trace <path-to-trace.zip> to inspect one.

Ctrl+C stops the launcher and its service children. The synthetic database is retained for review; no unrelated database is dropped. The backend regression owns its own separate database. See ../../docs/PG027_E2E_REPORT.md for observed results and limitations.
