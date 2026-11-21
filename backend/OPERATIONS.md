# Backend operations

## Password reset delivery recovery

Every API instance automatically sweeps the PostgreSQL reset email queue five
seconds after startup and then every ten seconds after the previous sweep finishes.
Railway's existing API start command starts this worker; no separate cron service
is required. A sweep processes up to 50 due tasks. Atomic claims use a one-minute
lease and increment the attempt number; completion updates require the same
attempt and a live lease. An expired lease can be reclaimed after a crash.
Provider requests reuse the original send operation as their idempotency key and
the identical email body, so recovery after provider acceptance can be deduplicated.
This is at-least-once delivery, with deduplication supplied by the provider.

Set `PASSWORD_RESET_QUEUE_ENCRYPTION_KEY` to a persistent secret shared by all
instances before deploying. Generate a random 32-byte key, encoded as hex or
Base64, and store it in deployment secrets. Startup rejects an ephemeral queue
key because another process could not decrypt pending messages. Keep this key
stable across restarts; key rotation requires draining queued messages first.
Existing messages encrypted with a lost ephemeral key cannot be recovered.
Flyway migration V10 adds retry scheduling and queue indexes automatically.

Ambiguous failures (including provider timeouts, 429 and 5xx responses) retry with
delays of 10, 20, 40 and 80 seconds, for a maximum of five total attempts,
including abandoned attempts. Definite rejections fail immediately. Exhausted
ambiguous deliveries retain the challenge's unknown delivery outcome, since a
code may already have arrived. Success and terminal failures clear encrypted OTP
payloads. Each sweep retires expired, superseded, missing or inactive challenges
and exhausted tasks once any processing lease has expired. Terminal records,
including synthetic tasks, are deleted after 24 hours, up to 500 per sweep.

Tune `PASSWORD_RESET_QUEUE_INITIAL_DELAY`, `PASSWORD_RESET_QUEUE_POLL_INTERVAL`,
`PASSWORD_RESET_QUEUE_MAX_ATTEMPTS`, `PASSWORD_RESET_QUEUE_RETRY_BACKOFF`,
`PASSWORD_RESET_QUEUE_MAX_RETRY_BACKOFF`, and `PASSWORD_RESET_QUEUE_RETENTION`
with Spring duration values such as `PT10S` (except the integer attempt limit).
Keep `PASSWORD_RESET_SEND_LEASE` longer than the provider request timeout.
Expired codes are never retried, even if attempts remain. If recovery logs
`Password reset queue recovery failed`, check database availability; the next
scheduled sweep retries automatically.

## PostgreSQL verified TLS

Remote PostgreSQL connections default to `DB_SSL_MODE=verify-full`. Set `DB_HOST`
to the provider's certificate-matching DNS hostname. Railpack's Java provider
copies only `target/` into the runtime image, so Maven copies
`scripts/railway-start.sh` to `target/railway-start.sh` during `process-resources`
without filtering. The Railway start command is `sh target/railway-start.sh`
followed by the existing Java command
(`java ${JAVA_OPTS:-} -jar target/restaurant-api-0.0.1-SNAPSHOT.jar`). The runtime
image must provide `sh`, `base64`, and `openssl`; `railpack.json` installs
`openssl` and `coreutils` alongside Railpack's own runtime packages. The wrapper
reads the Base64-encoded PEM certificate from `DB_SSL_ROOT_CERT_BASE64`, decodes it
to `/tmp/postgres-ca.pem` with mode `0600`, exports `DB_SSL_ROOT_CERT` to that
path and `DB_SSL_MODE=verify-full`, then executes those Java arguments unchanged.

Missing, blank, non-Base64, non-PEM, private-key, oversized, or cryptographically
invalid certificate data exits before Java starts. An explicit `DB_SSL_MODE` other
than `verify-full`, or a `SPRING_DATASOURCE_URL` whose `sslmode` is not
`verify-full`, also exits before Java starts. Obtain the CA from the Supabase
dashboard under Database settings, SSL configuration. Do not trust a certificate
captured from an unverified connection. Do not commit the certificate or its
Base64 value.

Startup validates the effective Hikari/pgJDBC settings before Flyway or Hibernate
connects. Remote hosts reject `disable`, `allow`, `prefer`, `require`, `verify-ca`,
non-validating SSL factories, custom hostname verifiers, and missing/unreadable CA
files. This also applies to `SPRING_DATASOURCE_URL` overrides and multi-host URLs;
every host must be loopback to use the local exception. URL SSL parameters take
precedence over Hikari driver properties, matching pgJDBC's interpretation.

For an explicitly local development/test database, use `DB_HOST=127.0.0.1` and
`DB_SSL_MODE=disable`. This exception is limited to literal `127.0.0.1`, `::1`, and
`localhost`; private-network and remote database hosts still require verified TLS.
The legacy `postgres-migration` profile inherits the same secure defaults.

If verification fails, replace the CA data or use the certificate-matching database
hostname. Do not work around a certificate error by weakening `sslmode` or adding a
non-validating SSL factory. Existing deployments with `DB_SSL_MODE=prefer` must
set `verify-full` and `DB_SSL_ROOT_CERT_BASE64` before restarting with this change.

## Signup OTP observability

`GET /api/auth/signup/otp-status` requires the originating signup attempt's
`X-Signup-Attempt-Token` header before returning challenge metadata. Email addresses
and challenge IDs are lookup identifiers, not authorization credentials. Missing
or incorrect tokens, mismatched IDs, and nonexistent challenges return the same
generic response; legacy challenges without a token hash do not expose status.
Status responses use `Cache-Control: no-store`. Never put the attempt token in a
URL, analytics event, or cross-tab broadcast.

Prometheus metrics are served from the separate management listener at
`http://127.0.0.1:8083/actuator/prometheus` by default. Keep this listener private.
For a secured remote scraper, explicitly set `MANAGEMENT_SERVER_ADDRESS` and firewall or
authenticate the management port.

The counter `signup_otp_operations_total` has one bounded `result` label. It does not use
email, IP, challenge, or operation identifiers as metric labels.

Structured OTP logs contain only correlation IDs, opaque challenge/send IDs, keyed hashes,
stable result codes, attempt counts, and provider result categories. Request bodies and
provider payloads must not be enabled in framework or HTTP-wire logging.

## Unusual send-volume alert

The default global provider-send limit is 200 sends per minute. Alert at 80% of that capacity
sustained over two five-minute windows:

```promql
sum(
  increase(signup_otp_operations_total{
    result=~"initial_send_accepted|resend_accepted"
  }[5m])
) > 800
```

Recommended alert duration: `for: 10m`.

Recalculate `800` whenever `ABUSE_GLOBAL_PROVIDER_SENDS` or its window changes. After at least
two weeks of production traffic, also lower the threshold if the normal five-minute p99 is
materially below 800. Investigate IP-limit rejections, provider timeouts, delivery cost, and
whether a small number of keyed client identifiers dominate the structured logs.

## Login and password-reset security

Account deletion returns `409 ACCOUNT_HAS_ACTIVE_ORDERS` while the authenticated
customer has any `NEW`, `ACCEPTED`, or `READY` orders (case-insensitive email
ownership). The check runs inside the deletion transaction after locking the user
and order rows. A refused deletion preserves the account, orders, saved cart,
authentication, and no-show count, and emits no deletion events. Checkout shares
the user lock, so a concurrently accepted order blocks deletion; if deletion
commits first, checkout cannot create an order for the deleted account.

Deletion and kitchen no-show updates can contend on user/order locks in opposite
orders. Both deletion and kitchen status updates retry only PostgreSQL deadlock
errors (`40P01`), with at most three complete transaction attempts and a short
jittered backoff. The failed transaction finishes rollback before a new
`REQUIRES_NEW` transaction starts. Deletion rechecks identity, password/token
version, and active orders on each attempt.
Constraint failures, active-order refusals, lock timeouts, and other errors are not
retried. Interruptions stop retries. Deletion events are emitted only after a
successful final commit, never for rolled-back or refused attempts. Kitchen status
events likewise run only after their final successful commit. The regression test
forces each transaction to be the deadlock victim in turn.

### Reservation deletion policy

Successful account deletion permanently removes existing reservation rows whose
email matches the authenticated account email, ignoring case and surrounding
spaces. This applies to past and upcoming bookings in every status, including
guest bookings made under that email. The complete row is deleted, including
names, phone numbers, and free-text special requests. The confirmation dialog
explicitly includes upcoming reservations in the deletion scope.

Reservation cleanup shares the account/order deletion transaction: an active-order
refusal or any database failure preserves reservations and rolls back the entire
deletion. Records matching only a name or phone number are not treated as owned;
shared or reassigned contact details must not delete another customer's bookings.
The admin reservation list refreshes on focus and periodically while visible.

No reservation-retention exception is defined by the current application. A future
documented retention requirement must specify its purpose, duration, and the data
that must be retained before changing this policy. This policy covers application
database rows; it does not define backup or external email-provider retention.

Login request counters and password-reset request counters are PostgreSQL fixed-window counters using atomic upserts.
Login identifier/IP cooldown state is stored separately in `login_failure_states` so
`blockedUntil` and successful-login clearing are distributed and atomic. Password-reset
challenge state is stored in `password_reset_otp_challenges`; authorization always checks
status and expiry timestamps independently of expired-row cleanup.

The broad login IP counter consumes every request that reaches `AuthController.login`,
including missing-field validation failures. JSON that cannot be deserialized is rejected by
Spring before the controller and therefore does not consume a login counter unit.

`auth_security_operations_total` uses only bounded `flow`, `outcome`, and `reason` labels.
Never add email, IP, user IDs, challenge IDs, provider messages, codes, digests, or passwords
as metric labels.

Password reset uses one PostgreSQL transaction for the security-critical claim, user
mutation and challenge finalization:

1. A correct code atomically moves one challenge from `ACTIVE` to `PROCESSING` with a stable
   reset operation ID.
2. The user password, `tokenVersion`, and `passwordChangedAt` are updated atomically on the
   users row, guarded by that operation ID.
3. The challenge is finalized as `CONSUMED`.

A retry with the same idempotency key can resume an interrupted `PROCESSING` operation.
Different operations cannot claim or complete the same challenge. The deployed store uses
the application PostgreSQL transaction manager.

Required production configuration includes `ABUSE_IP_HASH_SECRET`. The application fails
startup when it is absent so keyed IP/identifier hashes never use a predictable fallback.
Configure `ABUSE_TRUSTED_PROXIES` with every trusted reverse-proxy hop. Forwarded chains are
evaluated from the trusted edge toward the first untrusted client address.

The password-reset global send default is 100 operations per minute. Alert on sustained
`flow="password_reset", outcome="denied"` increases and tune the threshold only after
reviewing normal traffic and Brevo account capacity.

## Production monitoring and endpoint smoke tests

### HTTP request error observability
`CorrelationIdFilter` captures `method`, `uri`, `status`, and `duration_ms` with the request
correlation ID. Any unhandled exception or HTTP response with status >= 500 is logged at `ERROR`
level so 5xx responses in production can be immediately attributed to specific paths and root causes:
```text
HTTP server error response: method=GET uri=/api/orders status=500 duration_ms=42
```
To maintain data privacy, request bodies, query parameters, and authorization headers are never logged.

### Endpoint smoke tests
Before and after releases, run the endpoint smoke test against the live deployment or staging URL:
```bash
sh scripts/smoke-test.sh https://your-railway-app.up.railway.app
```
Or on Windows PowerShell:
```powershell
.\scripts\smoke-test.ps1 -BaseUrl "https://your-railway-app.up.railway.app"
```
The suite verifies that:
- Public endpoints (`/api/welcome`, `/api/menu`, `/api/categories`, `/api/settings`, `/api/reviews`) return HTTP 200.
- Protected endpoints (`/api/cart`, `/api/auth/profile`, `/api/orders`, `/api/kds/orders`) require authorization and return HTTP 401 without throwing 5xx errors.
- Non-existent endpoints return HTTP 404 without internal server errors.

## Current-device account session revocation

Migration V11 creates revoked_account_sessions. POST /api/auth/logout authenticates and revokes only the presented JWT. All account JWT validation paths consult this shared PostgreSQL digest denylist; a store failure is an error, not proof that a token is already invalid. Raw JWTs are never stored. New JWTs include random unique IDs; old byte-identical JWTs issued before this change share revocation scope. Revocations expire with the JWT and a scheduled sweep deletes up to 5000 expired digests per minute.

Deploy this backend before the mobile logout change. Other devices remain signed in. Password reset/account deletion still invalidate all account access. In-flight authorized operations, separate tracking credentials and existing Socket.IO room grants are not retroactively revoked by this bearer-token endpoint. Existing clients should disconnect their own sockets on logout. No sign-out-all-devices endpoint is introduced.
