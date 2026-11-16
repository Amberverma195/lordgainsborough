#!/bin/sh
# Lord Gainsborough API - Production & Staging Endpoint Smoke Test
# Usage:
#   sh scripts/smoke-test.sh [BASE_URL]
# Examples:
#   sh scripts/smoke-test.sh http://localhost:8080
#   sh scripts/smoke-test.sh https://your-railway-app.up.railway.app
set -eu

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
BASE_URL="${BASE_URL%/}"

echo "=========================================================="
echo " Running Lord Gainsborough Endpoint Smoke Tests"
echo " Target: ${BASE_URL}"
echo "=========================================================="

FAILED=0

check_endpoint() {
  METHOD="$1"
  PATH_PART="$2"
  EXPECTED_STATUS="$3"
  DESCRIPTION="$4"
  URL="${BASE_URL}${PATH_PART}"

  START_TIME=$(date +%s%N 2>/dev/null || date +%s)
  HTTP_RESPONSE=$(curl -s -w "\n%{http_code}\n%{time_total}" -X "${METHOD}" "${URL}" \
    -H "Accept: application/json" \
    -H "User-Agent: LGB-SmokeTest/1.0" \
    --connect-timeout 10 --max-time 15 2>/dev/null || echo -e "\n000\n0")

  TIME_TOTAL=$(echo "${HTTP_RESPONSE}" | tail -n 1)
  STATUS_CODE=$(echo "${HTTP_RESPONSE}" | tail -n 2 | head -n 1)

  if [ "${STATUS_CODE}" = "${EXPECTED_STATUS}" ]; then
    printf " [PASS] %-6s %-25s -> HTTP %s (expected %s) in %ss - %s\n" \
      "${METHOD}" "${PATH_PART}" "${STATUS_CODE}" "${EXPECTED_STATUS}" "${TIME_TOTAL}" "${DESCRIPTION}"
  else
    printf " [FAIL] %-6s %-25s -> HTTP %s (expected %s) in %ss - %s\n" \
      "${METHOD}" "${PATH_PART}" "${STATUS_CODE}" "${EXPECTED_STATUS}" "${TIME_TOTAL}" "${DESCRIPTION}" >&2
    FAILED=$((FAILED + 1))
  fi
}

# 1. Public API Endpoints
check_endpoint "GET" "/api/welcome" "200" "System health/welcome probe"
check_endpoint "GET" "/api/menu" "200" "Menu catalog retrieval"
check_endpoint "GET" "/api/categories" "200" "Menu categories retrieval"
check_endpoint "GET" "/api/settings" "200" "Pickup store settings & tax rate"
check_endpoint "GET" "/api/reviews" "200" "Customer reviews retrieval"

# 2. Protected Endpoints (Verifying Auth Gate Returns 401, Not 5xx)
check_endpoint "GET" "/api/cart" "401" "Cart requires Bearer auth"
check_endpoint "GET" "/api/auth/profile" "401" "Profile requires Bearer auth"
check_endpoint "GET" "/api/orders" "401" "Order history requires Bearer auth"
check_endpoint "GET" "/api/kds/orders" "401" "Kitchen display requires Staff/Admin auth"

# 3. Not Found Handler
check_endpoint "GET" "/api/nonexistent-endpoint-smoke" "404" "Non-existent endpoint returns 404"

echo "=========================================================="
if [ "${FAILED}" -eq 0 ]; then
  echo " ALL SMOKE TESTS PASSED!"
  echo "=========================================================="
  exit 0
else
  echo " SMOKE TESTS FAILED: ${FAILED} check(s) did not match expected status." >&2
  echo "=========================================================="
  exit 1
fi
