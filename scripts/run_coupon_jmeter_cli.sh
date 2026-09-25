#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JMETER_BIN="${JMETER_BIN:-jmeter}"
JMX_FILE="${JMX_FILE:-$ROOT_DIR/scripts/coupon_jmeter_cli.jmx}"
TOKEN_CSV="${TOKEN_CSV:-$ROOT_DIR/scripts/test_tokens.csv}"

USERS="${USERS:-${1:-200}}"
LOOPS="${LOOPS:-${2:-10}}"
RAMP="${RAMP:-${3:-10}}"
COUPON_ID="${COUPON_ID:-${4:-24}}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-5000}"
RESPONSE_TIMEOUT="${RESPONSE_TIMEOUT:-10000}"

RUN_ID="coupon_jmeter_$(date +%Y%m%d_%H%M%S)_u${USERS}_l${LOOPS}"
RESULT_DIR="${RESULT_DIR:-$ROOT_DIR/scripts/report/$RUN_ID}"
JTL_FILE="$RESULT_DIR/results.jtl"
HTML_DIR="$RESULT_DIR/html"
SUMMARY_FILE="$RESULT_DIR/summary.txt"

if [[ ! -f "$JMX_FILE" ]]; then
  echo "JMX file not found: $JMX_FILE" >&2
  exit 1
fi

if [[ ! -f "$TOKEN_CSV" ]]; then
  echo "Token CSV not found: $TOKEN_CSV" >&2
  exit 1
fi

if ! command -v "$JMETER_BIN" >/dev/null 2>&1; then
  echo "JMeter command not found: $JMETER_BIN" >&2
  echo "Set JMETER_BIN=/path/to/jmeter or add jmeter to PATH." >&2
  exit 1
fi

mkdir -p "$RESULT_DIR"

echo "JMeter CLI pressure test"
echo "  url: http://$HOST:$PORT/user/coupon/grab/$COUPON_ID"
echo "  users: $USERS"
echo "  loops: $LOOPS"
echo "  total requests target: $((USERS * LOOPS))"
echo "  ramp seconds: $RAMP"
echo "  jtl: $JTL_FILE"

"$JMETER_BIN" \
  -n \
  -t "$JMX_FILE" \
  -Jhost="$HOST" \
  -Jport="$PORT" \
  -JcouponId="$COUPON_ID" \
  -Jusers="$USERS" \
  -Jloops="$LOOPS" \
  -Jramp="$RAMP" \
  -JtokenCsv="$TOKEN_CSV" \
  -JconnectTimeout="$CONNECT_TIMEOUT" \
  -JresponseTimeout="$RESPONSE_TIMEOUT" \
  -l "$JTL_FILE" \
  -e \
  -o "$HTML_DIR"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/coupon-jmeter-summary.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

awk -F',' -v elapsed_file="$TMP_DIR/elapsed.txt" -v code_file="$TMP_DIR/codes.txt" -v error_file="$TMP_DIR/errors.txt" '
NR == 1 { next }
{
  total++
  elapsed = $2 + 0
  sum += elapsed
  print elapsed > elapsed_file
  print $4 > code_file
  if ($8 == "true") {
    ok++
  } else {
    err++
    if (err <= 10) {
      print $0 > error_file
    }
  }
  ts = $1 + 0
  if (total == 1 || ts < minTs) minTs = ts
  if (ts > maxTs) maxTs = ts
}
END {
  if (total == 0) {
    print "No samples found"
    exit 0
  }
  durationSec = (maxTs - minTs) / 1000
  if (durationSec <= 0) durationSec = 1
  printf("total=%d\n", total)
  printf("success=%d\n", ok)
  printf("errors=%d\n", err)
  printf("errorRate=%.2f%%\n", err * 100 / total)
  printf("throughput=%.2f req/s\n", total / durationSec)
  printf("avgMs=%.2f\n", sum / total)
  printf("durationSec=%.2f\n", durationSec)
  printf("p95Rank=%d\n", int(total * 0.95))
  printf("p99Rank=%d\n", int(total * 0.99))
}
' "$JTL_FILE" > "$TMP_DIR/base.txt"

P95_RANK="$(awk -F= '$1 == "p95Rank" { print $2 }' "$TMP_DIR/base.txt")"
P99_RANK="$(awk -F= '$1 == "p99Rank" { print $2 }' "$TMP_DIR/base.txt")"
P95_RANK="${P95_RANK:-1}"
P99_RANK="${P99_RANK:-1}"
P95_MS="$(sort -n "$TMP_DIR/elapsed.txt" | awk -v rank="$P95_RANK" 'NR == rank { print; exit }')"
P99_MS="$(sort -n "$TMP_DIR/elapsed.txt" | awk -v rank="$P99_RANK" 'NR == rank { print; exit }')"

{
  grep -v 'Rank=' "$TMP_DIR/base.txt"
  echo "p95Ms=${P95_MS:-0}"
  echo "p99Ms=${P99_MS:-0}"
  echo "responseCodes:"
  sort "$TMP_DIR/codes.txt" | uniq -c | awk '{ printf("  %s=%s\n", $2, $1) }'
  if [[ -s "$TMP_DIR/errors.txt" ]]; then
    echo "sampleErrors:"
    cat "$TMP_DIR/errors.txt"
  fi
} | tee "$SUMMARY_FILE"

echo "html report: $HTML_DIR/index.html"
