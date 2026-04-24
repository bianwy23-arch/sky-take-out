#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/oversell_stress_test.sh http://localhost:8080 token_list.txt
#
# token_list.txt format (one token per line):
#   eyJ...
#   eyJ...
# Notes:
# - Ensure each user token has cart prepared with the same target dish and quantity.
# - This script sends /user/order/submit concurrently to validate anti-oversell behavior.

BASE_URL="${1:-http://localhost:8080}"
TOKEN_FILE="${2:-token_list.txt}"
TMP_DIR="${TMPDIR:-/tmp}/oversell_stress_$$"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "token file not found: $TOKEN_FILE"
  exit 1
fi

mkdir -p "$TMP_DIR"
cp "$TOKEN_FILE" "$TMP_DIR/tokens.txt"

echo "start oversell stress test..."
echo "base url: $BASE_URL"
echo "token count: $(wc -l < "$TMP_DIR/tokens.txt" | tr -d ' ')"

cat "$TMP_DIR/tokens.txt" | nl -ba | xargs -n2 -P20 bash -c '
  idx="$1"
  token="$2"
  curl -s -X POST "'"$BASE_URL"'/user/order/submit" \
    -H "Content-Type: application/json" \
    -H "authentication: ${token}" \
    -d "{\"addressBookId\":2,\"payMethod\":1,\"remark\":\"stress\",\"estimatedDeliveryTime\":\"2026-12-31 23:59:00\",\"deliveryStatus\":0,\"tablewareNumber\":0,\"tablewareStatus\":0,\"packAmount\":1,\"amount\":13}" \
    > "'"$TMP_DIR"'/resp_${idx}.json"
' _

success_count=$(grep -h "\"code\":0" "$TMP_DIR"/resp_*.json | wc -l | tr -d ' ')
failed_count=$(grep -h "\"code\":1" "$TMP_DIR"/resp_*.json | wc -l | tr -d ' ')

echo "oversell stress result:"
echo "success_count=$success_count"
echo "failed_count=$failed_count"
echo "raw response dir: $TMP_DIR"

