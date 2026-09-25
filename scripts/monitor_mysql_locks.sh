#!/usr/bin/env bash

set -euo pipefail

MYSQL_BIN="${MYSQL_BIN:-mysql}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
MYSQL_DATABASE="${MYSQL_DATABASE:-}"
INTERVAL="${INTERVAL:-0.2}"
SAMPLES="${SAMPLES:-0}"

MYSQL_ARGS=(
  "-h${MYSQL_HOST}"
  "-P${MYSQL_PORT}"
  "-u${MYSQL_USER}"
  "--protocol=TCP"
  "--batch"
  "--raw"
  "--skip-column-names"
)

if [[ -n "$MYSQL_PASSWORD" ]]; then
  export MYSQL_PWD="$MYSQL_PASSWORD"
fi

SCHEMA_FILTER=""
if [[ -n "$MYSQL_DATABASE" ]]; then
  SCHEMA_FILTER="AND dl.OBJECT_SCHEMA = '${MYSQL_DATABASE}'"
fi

read -r -d '' LOCK_WAIT_SQL <<SQL || true
SELECT
  NOW(3) AS sampled_at,
  w.REQUESTING_ENGINE_TRANSACTION_ID AS waiting_trx,
  w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_trx,
  dl.OBJECT_SCHEMA,
  dl.OBJECT_NAME,
  dl.INDEX_NAME,
  dl.LOCK_TYPE,
  dl.LOCK_MODE,
  dl.LOCK_STATUS,
  r.PROCESSLIST_ID AS waiting_thread,
  r.PROCESSLIST_INFO AS waiting_sql,
  b.PROCESSLIST_ID AS blocking_thread,
  b.PROCESSLIST_INFO AS blocking_sql
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks dl
  ON dl.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
LEFT JOIN performance_schema.threads r
  ON r.THREAD_ID = w.REQUESTING_THREAD_ID
LEFT JOIN performance_schema.threads b
  ON b.THREAD_ID = w.BLOCKING_THREAD_ID
WHERE 1 = 1
${SCHEMA_FILTER}
ORDER BY dl.OBJECT_SCHEMA, dl.OBJECT_NAME, waiting_trx;
SQL

read -r -d '' INNODB_TRX_SQL <<SQL || true
SELECT
  NOW(3) AS sampled_at,
  trx_id,
  trx_state,
  trx_started,
  trx_wait_started,
  trx_mysql_thread_id,
  LEFT(REPLACE(REPLACE(COALESCE(trx_query, ''), '\\n', ' '), '\\t', ' '), 240) AS trx_query
FROM information_schema.innodb_trx
WHERE trx_state = 'LOCK WAIT'
ORDER BY trx_wait_started;
SQL

echo "MySQL row-lock monitor"
echo "  target: ${MYSQL_USER}@${MYSQL_HOST}:${MYSQL_PORT}"
if [[ -n "$MYSQL_DATABASE" ]]; then
  echo "  schema filter: ${MYSQL_DATABASE}"
else
  echo "  schema filter: all schemas"
fi
echo "  interval: ${INTERVAL}s"
echo "  samples: ${SAMPLES} (0 means until Ctrl-C)"
echo

count=0
while true; do
  count=$((count + 1))
  printf '\n===== sample %s %s =====\n' "$count" "$(date '+%H:%M:%S')"

  echo "[data_lock_waits]"
  lock_waits="$("$MYSQL_BIN" "${MYSQL_ARGS[@]}" -e "$LOCK_WAIT_SQL" 2>&1 || true)"
  if [[ -n "$lock_waits" ]]; then
    echo "$lock_waits"
  else
    echo "empty"
  fi

  echo "[innodb_trx LOCK WAIT]"
  trx_waits="$("$MYSQL_BIN" "${MYSQL_ARGS[@]}" -e "$INNODB_TRX_SQL" 2>&1 || true)"
  if [[ -n "$trx_waits" ]]; then
    echo "$trx_waits"
  else
    echo "empty"
  fi

  if [[ "$SAMPLES" -gt 0 && "$count" -ge "$SAMPLES" ]]; then
    break
  fi

  sleep "$INTERVAL"
done
