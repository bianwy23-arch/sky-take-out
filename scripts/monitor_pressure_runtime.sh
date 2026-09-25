#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ID="${RUN_ID:-runtime_$(date +%Y%m%d_%H%M%S)}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/scripts/report/$RUN_ID/monitor}"

INTERVAL="${INTERVAL:-1}"
SAMPLES="${SAMPLES:-0}"
JAVA_MAIN="${JAVA_MAIN:-com.sky.SkyApplication}"
JAVA_PID="${JAVA_PID:-}"

MYSQL_BIN="${MYSQL_BIN:-mysql}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
MYSQL_DATABASE="${MYSQL_DATABASE:-}"

mkdir -p "$OUTPUT_DIR"

if [[ -z "$JAVA_PID" ]]; then
  JAVA_PID="$(jps -l | awk -v main="$JAVA_MAIN" '$2 == main { print $1; exit }' || true)"
fi

if [[ -z "$JAVA_PID" ]]; then
  JAVA_PID="$(lsof -nP -iTCP:8080 -sTCP:LISTEN 2>/dev/null | awk '$1 == "java" { print $2; exit }' || true)"
fi

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
  "--skip-column-names"
)

SCHEMA_FILTER=""
if [[ -n "$MYSQL_DATABASE" ]]; then
  SCHEMA_FILTER="AND dl.OBJECT_SCHEMA = '${MYSQL_DATABASE}'"
fi

read -r -d '' LOCK_WAIT_SQL <<SQL || true
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
  LEFT(REPLACE(REPLACE(COALESCE(r.PROCESSLIST_INFO, ''), '\\n', ' '), '\\t', ' '), 160),
  b.PROCESSLIST_ID,
  LEFT(REPLACE(REPLACE(COALESCE(b.PROCESSLIST_INFO, ''), '\\n', ' '), '\\t', ' '), 160)
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks dl
  ON dl.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
LEFT JOIN performance_schema.threads r
  ON r.THREAD_ID = w.REQUESTING_THREAD_ID
LEFT JOIN performance_schema.threads b
  ON b.THREAD_ID = w.BLOCKING_THREAD_ID
WHERE 1 = 1
${SCHEMA_FILTER}
ORDER BY dl.OBJECT_SCHEMA, dl.OBJECT_NAME, w.REQUESTING_ENGINE_TRANSACTION_ID;
SQL

read -r -d '' INNODB_TRX_SQL <<SQL || true
SELECT
  NOW(3),
  trx_id,
  trx_state,
  trx_started,
  trx_wait_started,
  trx_mysql_thread_id,
  LEFT(REPLACE(REPLACE(COALESCE(trx_query, ''), '\\n', ' '), '\\t', ' '), 240)
FROM information_schema.innodb_trx
WHERE trx_state = 'LOCK WAIT'
ORDER BY trx_wait_started;
SQL

SUMMARY_FILE="$OUTPUT_DIR/summary.txt"
CPU_FILE="$OUTPUT_DIR/cpu_top.txt"
JVM_FILE="$OUTPUT_DIR/jvm_gcutil.txt"
LOCK_FILE="$OUTPUT_DIR/mysql_locks.txt"
META_FILE="$OUTPUT_DIR/meta.txt"

{
  echo "runId=$RUN_ID"
  echo "startedAt=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "interval=$INTERVAL"
  echo "samples=$SAMPLES"
  echo "javaMain=$JAVA_MAIN"
  echo "javaPid=${JAVA_PID:-not_found}"
  echo "mysql=${MYSQL_USER}@${MYSQL_HOST}:${MYSQL_PORT}"
  echo "mysqlDatabase=${MYSQL_DATABASE:-all}"
  echo "outputDir=$OUTPUT_DIR"
} | tee "$META_FILE"

if [[ -z "$JAVA_PID" ]]; then
  echo "WARN: Java PID not found for main class $JAVA_MAIN" | tee -a "$META_FILE"
fi

echo "sample,time,java_pid,java_cpu_pct,java_rss,mysqld_cpu_pct,mysqld_rss,jmeter_cpu_pct,jmeter_rss" > "$CPU_FILE"
{
  echo "===== JVM GCUTIL ====="
  if [[ -n "$JAVA_PID" ]]; then
    jstat -gcutil "$JAVA_PID" 1 1 2>&1 || true
  else
    echo "Java PID not found"
  fi
} > "$JVM_FILE"

echo "===== MYSQL LOCKS =====" > "$LOCK_FILE"

count=0
while true; do
  count=$((count + 1))
  now="$(date '+%Y-%m-%d %H:%M:%S')"

  java_line="$(LC_ALL=C ps -p "${JAVA_PID:-0}" -o %cpu= -o rss= 2>/dev/null | awk 'NF >= 2 { print; exit }' || true)"
  java_cpu="$(awk '{ print $1 }' <<<"$java_line")"
  java_rss="$(awk '{ print $2 }' <<<"$java_line")"

  mysqld_pid="$(pgrep -x mysqld 2>/dev/null | head -n 1 || true)"
  mysqld_line="$(LC_ALL=C ps -p "${mysqld_pid:-0}" -o %cpu= -o rss= 2>/dev/null | awk 'NF >= 2 { print; exit }' || true)"
  mysqld_cpu="$(awk '{ print $1 }' <<<"$mysqld_line")"
  mysqld_rss="$(awk '{ print $2 }' <<<"$mysqld_line")"

  jmeter_pid="$(pgrep -f 'ApacheJMeter|jmeter' 2>/dev/null | awk -v app_pid="${JAVA_PID:-0}" '$1 != app_pid { print; exit }' || true)"
  jmeter_line="$(LC_ALL=C ps -p "${jmeter_pid:-0}" -o %cpu= -o rss= 2>/dev/null | awk 'NF >= 2 { print; exit }' || true)"
  jmeter_cpu="$(awk '{ print $1 }' <<<"$jmeter_line")"
  jmeter_rss="$(awk '{ print $2 }' <<<"$jmeter_line")"

  echo "$count,$now,${JAVA_PID:-},${java_cpu:-},${java_rss:-},${mysqld_cpu:-},${mysqld_rss:-},${jmeter_cpu:-},${jmeter_rss:-}" >> "$CPU_FILE"

  {
    echo
    echo "===== sample $count $now ====="
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
  } >> "$LOCK_FILE"

  if [[ -n "$JAVA_PID" ]]; then
    {
      echo
      echo "===== sample $count $now ====="
      jstat -gcutil "$JAVA_PID" 1 1 2>&1 || true
    } >> "$JVM_FILE"
  fi

  if [[ "$SAMPLES" -gt 0 && "$count" -ge "$SAMPLES" ]]; then
    break
  fi

  sleep "$INTERVAL"
done

lock_wait_count="$(awk '
  $0 == "empty" { next }
  $0 == "" { next }
  $0 ~ /^=/ { next }
  $0 ~ /^\[/ { next }
  $0 == "===== MYSQL LOCKS =====" { next }
  { count++ }
  END { print count + 0 }
' "$LOCK_FILE")"
full_gc_count="unknown"
if [[ -n "$JAVA_PID" ]]; then
  full_gc_count="$(awk 'NF >= 9 && $1 ~ /^[0-9.]+$/ { value=$9; found=1 } END { if (found) print value + 0; else print "unavailable" }' "$JVM_FILE")"
fi

{
  echo "finishedAt=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "samples=$count"
  echo "lockWaitNonEmptyLines=$lock_wait_count"
  echo "lastFullGcCount=$full_gc_count"
  echo "cpuLog=$CPU_FILE"
  echo "jvmLog=$JVM_FILE"
  echo "mysqlLockLog=$LOCK_FILE"
} | tee "$SUMMARY_FILE"

echo "monitor summary: $SUMMARY_FILE"
