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
