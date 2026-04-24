#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/duplicate_callback_stress_test.sh http://localhost:8080 1771651171434 50
#
# Sends concurrent duplicate pay callback requests for the same order number.

BASE_URL="${1:-http://localhost:8080}"
ORDER_NO="${2:-}"
CONCURRENCY="${3:-50}"
TMP_DIR="${TMPDIR:-/tmp}/dup_callback_$$"

if [[ -z "$ORDER_NO" ]]; then
  echo "order number required"
  echo "usage: $0 <base_url> <order_no> [concurrency]"
  exit 1
fi

mkdir -p "$TMP_DIR"

echo "start duplicate callback stress test..."
echo "base url: $BASE_URL"
echo "order no: $ORDER_NO"
echo "concurrency: $CONCURRENCY"

seq 1 "$CONCURRENCY" | xargs -I{} -P20 bash -c '
  i="$1"
  curl -s "'"$BASE_URL"'/notify/paySuccess/mock?outTradeNo='"$ORDER_NO"'&transactionId=TX-DUP-'"$ORDER_NO"'" \
    > "'"$TMP_DIR"'/resp_${i}.json"
' _ {}

success_count=$(grep -h "\"code\":0" "$TMP_DIR"/resp_*.json | wc -l | tr -d ' ')
failed_count=$(grep -h "\"code\":1" "$TMP_DIR"/resp_*.json | wc -l | tr -d ' ')

echo "duplicate callback result:"
echo "success_count=$success_count"
echo "failed_count=$failed_count"
echo "raw response dir: $TMP_DIR"
echo "verify db:"
echo "  select count(*) from pay_transaction where order_no='${ORDER_NO}';"
echo "  select count(*) from outbox_message where biz_key='${ORDER_NO}' and event_type='ORDER_PAID';"

