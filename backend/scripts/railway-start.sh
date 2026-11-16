#!/bin/sh
# Railway startup wrapper. Materialize the Supabase CA, then exec the existing Java command.
# Usage: sh scripts/railway-start.sh java [current Java arguments...]
set -eu

fail() {
  printf '%s\n' "PostgreSQL CA startup failed: $1" >&2
  exit 1
}

if [ "$#" -eq 0 ]; then
  fail "Java startup arguments are required."
fi

requested_mode=$(printf '%s' "${DB_SSL_MODE:-verify-full}" | tr '[:upper:]' '[:lower:]')
if [ "$requested_mode" != "verify-full" ]; then
  fail "DB_SSL_MODE must be verify-full."
fi

if printf '%s' "${SPRING_DATASOURCE_URL-}" | grep -qi 'sslmode='; then
  datasource_mode=$(printf '%s' "${SPRING_DATASOURCE_URL}" | tr '[:upper:]' '[:lower:]' | sed -n 's/.*sslmode=\([^&]*\).*/\1/p' | head -n 1)
  if [ "$datasource_mode" != "verify-full" ]; then
    fail "SPRING_DATASOURCE_URL must use sslmode=verify-full."
  fi
fi

encoded=${DB_SSL_ROOT_CERT_BASE64-}
stripped=$(printf '%s' "$encoded" | tr -d ' \t\r\n')
if [ -z "$stripped" ]; then
  fail "DB_SSL_ROOT_CERT_BASE64 is required."
fi
if ! printf '%s' "$stripped" | grep -Eq '^[A-Za-z0-9+/]+={0,2}$'; then
  fail "DB_SSL_ROOT_CERT_BASE64 is not valid Base64."
fi
length=${#stripped}
if [ "$length" -lt 4 ] || [ $((length % 4)) -ne 0 ]; then
  fail "DB_SSL_ROOT_CERT_BASE64 is not valid Base64."
fi

command -v base64 >/dev/null 2>&1 || fail "base64 is required to decode the CA certificate."
command -v openssl >/dev/null 2>&1 || fail "openssl is required to validate the CA certificate."

destination=/tmp/postgres-ca.pem
temporary=$(mktemp /tmp/postgres-ca.XXXXXX)
chmod 0600 "$temporary"
if ! printf '%s' "$stripped" | base64 -d > "$temporary" 2>/dev/null; then
  rm -f "$temporary"
  fail "DB_SSL_ROOT_CERT_BASE64 is not valid Base64."
fi

size=$(wc -c < "$temporary" | tr -d ' ')
if [ "$size" -eq 0 ] || [ "$size" -gt 65536 ]; then
  rm -f "$temporary"
  fail "Decoded certificate data has an invalid size."
fi
if grep -q "PRIVATE KEY" "$temporary"; then
  rm -f "$temporary"
  fail "Certificate data must not include a private key."
fi
begins=$(grep -c "BEGIN CERTIFICATE" "$temporary" || true)
ends=$(grep -c "END CERTIFICATE" "$temporary" || true)
if [ "$begins" -lt 1 ] || [ "$begins" != "$ends" ]; then
  rm -f "$temporary"
  fail "Decoded certificate data is not a PEM certificate."
fi
if ! openssl x509 -in "$temporary" -noout >/dev/null 2>&1; then
  rm -f "$temporary"
  fail "Decoded certificate data is not a valid X.509 PEM certificate."
fi

old_umask=$(umask)
umask 077
mv -f "$temporary" "$destination"
chmod 0600 "$destination"
mode=$(stat -c %a "$destination" 2>/dev/null || stat -f %OLp "$destination")
umask "$old_umask"
case $mode in
  600|0600) ;;
  *)
    rm -f "$destination"
    fail "Could not restrict the CA file to mode 0600."
    ;;
esac

export DB_SSL_MODE=verify-full
export DB_SSL_ROOT_CERT=$destination
exec "$@"