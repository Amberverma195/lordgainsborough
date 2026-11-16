#!/bin/sh
# Runs inside the Railpack runtime image. Prints no certificate material.
set -eu

fail() {
  printf '%s\n' "runtime image check failed: $1" >&2
  exit 1
}

command -v sh >/dev/null 2>&1 || fail "sh is not available"
command -v base64 >/dev/null 2>&1 || fail "base64 is not available"
command -v openssl >/dev/null 2>&1 || fail "openssl is not available"
command -v grep >/dev/null 2>&1 || fail "grep is not available"
command -v sed >/dev/null 2>&1 || fail "sed is not available"
command -v mktemp >/dev/null 2>&1 || fail "mktemp is not available"
command -v stat >/dev/null 2>&1 || fail "stat is not available"
sh -c 'exit 0' || fail "sh cannot execute"
decoded=$(printf 'YQ==' | base64 -d)
test "$decoded" = "a" || fail "base64 cannot decode"
openssl version >/dev/null 2>&1 || fail "openssl cannot execute"

test -f /app/target/railway-start.sh || fail "startup script missing at /app/target/railway-start.sh"
test ! -e /app/scripts/railway-start.sh || fail "legacy scripts/railway-start.sh must not be the runtime path"
test -f /app/target/restaurant-api-0.0.1-SNAPSHOT.jar || fail "application jar missing"
if [ -z "${START_COMMAND:-}" ]; then
  fail "START_COMMAND is required"
fi

cd /app
if sh scripts/railway-start.sh java -jar target/restaurant-api-0.0.1-SNAPSHOT.jar >/tmp/old-path-out 2>/tmp/old-path-err; then
  fail "legacy scripts path unexpectedly started"
fi
grep -q "No such file" /tmp/old-path-err || fail "legacy path was not rejected as a missing file"

openssl req -x509 -newkey rsa:2048 -keyout /tmp/lgb-image-key.pem -out /tmp/lgb-image-cert.pem \
  -days 1 -nodes -subj "/CN=LordGainsboroughTestCA" >/dev/null 2>&1
saved_certificate=$(base64 -w 0 /tmp/lgb-image-cert.pem)
rm -f /tmp/lgb-image-key.pem /tmp/lgb-image-cert.pem
export DB_SSL_ROOT_CERT_BASE64="$saved_certificate"
export DB_SSL_MODE=verify-full

mkdir -p /tmp/bin
cat > /tmp/bin/java << 'EOF'
#!/bin/sh
printf 'ARG:%s\n' "$@"
printf 'MODE:%s\n' "$DB_SSL_MODE"
printf 'ROOT:%s\n' "$DB_SSL_ROOT_CERT"
stat -c %a "$DB_SSL_ROOT_CERT"
openssl x509 -in "$DB_SSL_ROOT_CERT" -noout -subject
if grep -q "PRIVATE KEY" "$DB_SSL_ROOT_CERT"; then
  printf '%s\n' "PRIVATE_KEY_PRESENT"
  exit 9
fi
EOF
chmod 755 /tmp/bin/java
export PATH="/tmp/bin:${PATH:-}"
export JAVA_OPTS="-Xmx256m"

if ! eval "$START_COMMAND" >/tmp/start-out 2>/tmp/start-err; then
  cat /tmp/start-err >&2
  fail "configured startup command failed"
fi
grep -qx 'ARG:-Xmx256m' /tmp/start-out || fail "JAVA_OPTS argument was not preserved"
grep -qx 'ARG:-jar' /tmp/start-out || fail "jar flag was not preserved"
grep -qx 'ARG:target/restaurant-api-0.0.1-SNAPSHOT.jar' /tmp/start-out || fail "jar path was not preserved"
grep -qx 'MODE:verify-full' /tmp/start-out || fail "DB_SSL_MODE was not verify-full"
grep -qx 'ROOT:/tmp/postgres-ca.pem' /tmp/start-out || fail "DB_SSL_ROOT_CERT was not set"
grep -qx '600' /tmp/start-out || fail "CA file mode was not 0600"
grep -q 'LordGainsboroughTestCA' /tmp/start-out || fail "CA subject was not preserved"
test "$(stat -c %a /tmp/postgres-ca.pem)" = "600" || fail "CA file was not mode 0600 on disk"
if grep -q "PRIVATE KEY" /tmp/start-out; then
  fail "private key material was exposed"
fi

cat > /tmp/bin/java << 'EOF'
#!/bin/sh
touch /tmp/java-started
exit 0
EOF
chmod 755 /tmp/bin/java
rm -f /tmp/java-started /tmp/postgres-ca.pem
unset DB_SSL_ROOT_CERT_BASE64
if eval "$START_COMMAND" >/tmp/missing-out 2>/tmp/missing-err; then
  fail "startup succeeded without a certificate"
fi
test ! -f /tmp/java-started || fail "Java started without a certificate"
test ! -f /tmp/postgres-ca.pem || fail "CA file remained after a missing certificate"
grep -q "DB_SSL_ROOT_CERT_BASE64 is required" /tmp/missing-err || fail "missing certificate was not rejected"

export DB_SSL_ROOT_CERT_BASE64="$saved_certificate"
export DB_SSL_MODE=prefer
rm -f /tmp/java-started
if eval "$START_COMMAND" >/tmp/prefer-out 2>/tmp/prefer-err; then
  fail "startup succeeded with DB_SSL_MODE=prefer"
fi
test ! -f /tmp/java-started || fail "Java started with DB_SSL_MODE=prefer"
grep -q "DB_SSL_MODE must be verify-full" /tmp/prefer-err || fail "prefer was not rejected"
unset saved_certificate DB_SSL_ROOT_CERT_BASE64

cat /tmp/start-out
printf 'SH:%s\n' "$(command -v sh)"
printf 'BASE64:%s\n' "$(command -v base64)"
printf 'OPENSSL:%s\n' "$(openssl version)"
printf '%s\n' "RUNTIME_IMAGE_OK"
