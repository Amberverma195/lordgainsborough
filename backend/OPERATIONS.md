# Backend operations

## Signup OTP observability

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

Login request counters and password-reset request counters are MongoDB fixed-window counters.
Login identifier/IP cooldown state is stored separately in `login_failure_states` so
`blockedUntil` and successful-login clearing are distributed and atomic. Password-reset
challenge state is stored in `password_reset_otp_challenges`; authorization always checks
status and expiry timestamps because MongoDB TTL cleanup is asynchronous.

The broad login IP counter consumes every request that reaches `AuthController.login`,
including missing-field validation failures. JSON that cannot be deserialized is rejected by
Spring before the controller and therefore does not consume a login counter unit.

`auth_security_operations_total` uses only bounded `flow`, `outcome`, and `reason` labels.
Never add email, IP, user IDs, challenge IDs, provider messages, codes, digests, or passwords
as metric labels.

Password reset uses a MongoDB transaction for the security-critical claim, user mutation,
and challenge finalization on the deployed replica-set topology:

1. A correct code atomically moves one challenge from `ACTIVE` to `PROCESSING` with a stable
   reset operation ID.
2. The user password, `tokenVersion`, and `passwordChangedAt` are updated atomically on the
   user document, guarded by that operation ID.
3. The challenge is finalized as `CONSUMED`.

A safe idempotent claim/update/finalize implementation remains available for focused tests
or a topology where a transaction manager is not supplied. A retry with the same idempotency
key can resume an interrupted `PROCESSING` operation. Different operations cannot claim or
complete the same challenge.

Required production configuration includes `ABUSE_IP_HASH_SECRET`. The application fails
startup when it is absent so keyed IP/identifier hashes never use a predictable fallback.
Configure `ABUSE_TRUSTED_PROXIES` with every trusted reverse-proxy hop. Forwarded chains are
evaluated from the trusted edge toward the first untrusted client address.

The password-reset global send default is 100 operations per minute. Alert on sustained
`flow="password_reset", outcome="denied"` increases and tune the threshold only after
reviewing normal traffic and Brevo account capacity.
