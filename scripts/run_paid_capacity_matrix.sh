#!/bin/bash
# Formal before/after matrix for the paid-coupon reserve+claim capacity probe.
# usage: run_paid_capacity_matrix.sh <out_dir> <name=jar>...
# Each round starts every jar fresh on :8081 (second round in reverse order), idles 45s so the
# startup reconcile task has finished for every version, warms up, then runs each concurrency.
set -u
OUT=$1; shift
VERSIONS=("$@")
CONCURRENCY=(16 64 128 256)
SECONDS_PER_RUN=20
PY=${PY:-/private/tmp/reservation-lab-venv/bin/python}
cd "$(dirname "$0")/.."
mkdir -p "$OUT"
MYSQL_PID=$(pgrep -x mysqld | head -1)

run_version() {
  local name=$1 jar=$2 round=$3
  java -Xms1536m -Xmx1536m -jar "$jar" \
    --spring.profiles.active=dev --server.port=8081 \
    --sky.paid-coupon.benchmark-enabled=true --sky.paid-coupon.demo-enabled=true \
    --sky.paid-coupon.lock-lab.enabled=false \
    --sky.paid-coupon.reservation-ttl-seconds=3600 \
    --sky.paid-coupon.expire-scan-initial-delay-ms=3600000 \
    --logging.level.root=WARN --spring.shardingsphere.props.sql-show=false \
    > "$OUT/${name}_r${round}.log" 2>&1 &
  local pid=$!
  for _ in $(seq 1 90); do curl -s -o /dev/null http://127.0.0.1:8081/ && break; sleep 1; done
  sleep 45
  "$PY" scripts/paid_coupon_capacity.py --concurrency 32 --seconds 10 --pid $pid --mysql-pid "$MYSQL_PID" \
    --output "$OUT/${name}_r${round}_warmup.json" > /dev/null
  for c in "${CONCURRENCY[@]}"; do
    sleep 3
    echo "$name round=$round c=$c $("$PY" scripts/paid_coupon_capacity.py --concurrency $c --seconds $SECONDS_PER_RUN \
      --pid $pid --mysql-pid "$MYSQL_PID" --output "$OUT/${name}_r${round}_c${c}.json" | head -1)"
  done
  kill $pid; wait $pid 2>/dev/null; sleep 3
}

for round in 1 2; do
  if [ $round -eq 1 ]; then order=("${VERSIONS[@]}"); else order=(); for ((i=${#VERSIONS[@]}-1; i>=0; i--)); do order+=("${VERSIONS[$i]}"); done; fi
  for entry in "${order[@]}"; do
    run_version "${entry%%=*}" "${entry#*=}" $round
  done
done
echo MATRIX_DONE
