# Backend operations

## PostgreSQL verified TLS

Remote PostgreSQL connections default to `DB_SSL_MODE=verify-full`. Set `DB_HOST`
to the provider's certificate-matching DNS hostname and mount the provider's CA
certificate bundle into the backend runtime. Set `DB_SSL_ROOT_CERT` to that readable
file's absolute path, for example `/run/certificates/postgresql-provider-ca.pem`.
Obtain the CA from the database provider over its authenticated HTTPS dashboard or
documentation; do not trust a certificate captured from an unverified connection.
Certificate contents are not committed to this repository.

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

If verification fails, fix the CA mount or use the correct database hostname.
Do not work around a certificate error by weakening `sslmode` or adding a
non-validating SSL factory. Existing deployments with `DB_SSL_MODE=prefer` must
update their mode and install the provider CA before restarting with this change.

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
