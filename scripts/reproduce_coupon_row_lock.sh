#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ID="${RUN_ID:-coupon_row_lock_$(date +%Y%m%d_%H%M%S)}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/scripts/report/$RUN_ID}"

MYSQL_BIN="${MYSQL_BIN:-mysql}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
MYSQL_DATABASE="${MYSQL_DATABASE:-sky_order_0}"

WORKERS="${WORKERS:-20}"
HOLD_SECONDS="${HOLD_SECONDS:-10}"
MONITOR_INTERVAL="${MONITOR_INTERVAL:-0.2}"
MONITOR_SAMPLES="${MONITOR_SAMPLES:-80}"

mkdir -p "$OUTPUT_DIR"

if [[ -n "$MYSQL_PASSWORD" ]]; then
  export MYSQL_PWD="$MYSQL_PASSWORD"
fi

MYSQL_ARGS=(
  "-h${MYSQL_HOST}"
  "-P${MYSQL_PORT}"
  "-u${MYSQL_USER}"
  "--protocol=TCP"
  "--batch"
  "--raw"
)

TEST_COUPON_NAME="row_lock_test_${RUN_ID}"
META_FILE="$OUTPUT_DIR/meta.txt"
LOCK_LOG="$OUTPUT_DIR/mysql_locks.txt"
WORKER_LOG="$OUTPUT_DIR/workers.log"

run_mysql() {
  "$MYSQL_BIN" "${MYSQL_ARGS[@]}" "$MYSQL_DATABASE" "$@"
}

run_mysql_skip_names() {
  "$MYSQL_BIN" "${MYSQL_ARGS[@]}" "--skip-column-names" "$MYSQL_DATABASE" "$@"
}

cleanup() {
  if [[ -n "${LOCK_HOLDER_PID:-}" ]]; then
    wait "$LOCK_HOLDER_PID" 2>/dev/null || true
  fi
  if [[ -n "${MONITOR_PID:-}" ]]; then
    wait "$MONITOR_PID" 2>/dev/null || true
  fi
  for pid in "${WORKER_PIDS[@]:-}"; do
    wait "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

echo "Creating isolated test coupon in ${MYSQL_DATABASE}"
run_mysql -e "
INSERT INTO coupon(name, type, threshold, discount, total_count, remaining_count, status, start_time, end_time, create_time)
VALUES('${TEST_COUPON_NAME}', 1, 0, 1, ${WORKERS}, ${WORKERS}, 1, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), NOW());
"

COUPON_ID="$(run_mysql_skip_names -e "SELECT id FROM coupon WHERE name = '${TEST_COUPON_NAME}' ORDER BY id DESC LIMIT 1;")"

{
  echo "runId=$RUN_ID"
  echo "database=$MYSQL_DATABASE"
  echo "couponId=$COUPON_ID"
  echo "workers=$WORKERS"
  echo "holdSeconds=$HOLD_SECONDS"
  echo "monitorInterval=$MONITOR_INTERVAL"
  echo "monitorSamples=$MONITOR_SAMPLES"
  echo "oldPathSql=UPDATE coupon SET remaining_count = remaining_count - 1 WHERE id = ${COUPON_ID} AND remaining_count > 0"
} | tee "$META_FILE"

read -r -d '' LOCK_SQL <<SQL || true
SELECT
  NOW(3),
  w.REQUESTING_ENGINE_TRANSACTION_ID,
  w.BLOCKING_ENGINE_TRANSACTION_ID,
  dl.OBJECT_SCHEMA,
  dl.OBJECT_NAME,
  dl.INDEX_NAME,
  dl.LOCK_TYPE,
  dl.LOCK_MODE,
  dl.LOCK_STATUS,
  r.PROCESSLIST_ID,
  LEFT(REPLACE(REPLACE(COALESCE(r.PROCESSLIST_INFO, ''), '\\n', ' '), '\\t', ' '), 180),
  b.PROCESSLIST_ID,
  LEFT(REPLACE(REPLACE(COALESCE(b.PROCESSLIST_INFO, ''), '\\n', ' '), '\\t', ' '), 180)
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks dl
  ON dl.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
LEFT JOIN performance_schema.threads r
  ON r.THREAD_ID = w.REQUESTING_THREAD_ID
LEFT JOIN performance_schema.threads b
  ON b.THREAD_ID = w.BLOCKING_THREAD_ID
WHERE dl.OBJECT_SCHEMA = '${MYSQL_DATABASE}'
  AND dl.OBJECT_NAME = 'coupon'
ORDER BY w.REQUESTING_ENGINE_TRANSACTION_ID;
SQL

echo "Starting lock holder transaction"
run_mysql -e "
SET autocommit = 0;
START TRANSACTION;
UPDATE coupon SET remaining_count = remaining_count - 1 WHERE id = ${COUPON_ID} AND remaining_count > 0;
SELECT SLEEP(${HOLD_SECONDS});
COMMIT;
" > "$OUTPUT_DIR/lock_holder.log" 2>&1 &
LOCK_HOLDER_PID="$!"

sleep 1

echo "Starting lock monitor"
(
  for sample in $(seq 1 "$MONITOR_SAMPLES"); do
    {
      echo
      echo "===== sample ${sample} $(date '+%H:%M:%S') ====="
      result="$(run_mysql_skip_names -e "$LOCK_SQL" 2>&1 || true)"
      if [[ -n "$result" ]]; then
        echo "$result"
      else
        echo "empty"
      fi
    } >> "$LOCK_LOG"
    sleep "$MONITOR_INTERVAL"
  done
) &
MONITOR_PID="$!"

sleep 0.5

echo "Starting ${WORKERS} old-path decrement workers"
WORKER_PIDS=()
for worker in $(seq 1 "$WORKERS"); do
  (
    run_mysql -e "
    SET innodb_lock_wait_timeout = 20;
    START TRANSACTION;
    UPDATE coupon SET remaining_count = remaining_count - 1 WHERE id = ${COUPON_ID} AND remaining_count > 0;
    COMMIT;
    " >> "$WORKER_LOG" 2>&1
    echo "worker ${worker} done" >> "$WORKER_LOG"
  ) &
  WORKER_PIDS+=("$!")
done

for pid in "${WORKER_PIDS[@]}"; do
  wait "$pid" || true
done

wait "$LOCK_HOLDER_PID" || true
wait "$MONITOR_PID" || true

FINAL_ROW="$(run_mysql_skip_names -e "SELECT id, total_count, remaining_count FROM coupon WHERE id = ${COUPON_ID};")"
LOCK_HIT_COUNT="$(awk '
  $0 == "empty" { next }
  $0 == "" { next }
  $0 ~ /^=/ { next }
  { count++ }
  END { print count + 0 }
' "$LOCK_LOG")"

{
  echo "finalCouponRow=${FINAL_ROW}"
  echo "lockWaitNonEmptyLines=${LOCK_HIT_COUNT}"
  echo "meta=$META_FILE"
  echo "lockLog=$LOCK_LOG"
  echo "workerLog=$WORKER_LOG"
} | tee "$OUTPUT_DIR/summary.txt"

if [[ "${KEEP_TEST_COUPON:-0}" != "1" ]]; then
  echo "Cleaning test coupon ${COUPON_ID}"
  run_mysql -e "DELETE FROM coupon WHERE id = ${COUPON_ID} AND name = '${TEST_COUPON_NAME}';"
fi

echo "summary: $OUTPUT_DIR/summary.txt"
