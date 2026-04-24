#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/sky-server"
TARGET_DIR="$MODULE_DIR/target"
LOG_DIR="$ROOT_DIR/logs"

PROFILE="${PROFILE:-prod}"
SIZE="${SIZE:-medium}"
APP_NAME="${APP_NAME:-sky-server}"
JAR_PATH="${JAR_PATH:-}"
JAVA_BIN="${JAVA_BIN:-java}"

mkdir -p "$LOG_DIR"

usage() {
  cat <<'EOF'
Usage:
  scripts/run-sky-server.sh [profile] [size]

Arguments:
  profile   dev | test | prod   (default: prod)
  size      small | medium | large   (default: medium)

Environment variables:
  JAVA_BIN       Java executable, default: java
  JAVA_OPTS      Extra JVM options appended at the end
  SPRING_OPTS    Extra Spring Boot options
  JAR_PATH       Explicit jar path, otherwise auto-detect from sky-server/target
  APP_NAME       Application name used in logs, default: sky-server

Examples:
  scripts/run-sky-server.sh dev small
  PROFILE=prod SIZE=large JAVA_OPTS="-XX:MaxRAMPercentage=70" scripts/run-sky-server.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ge 1 ]]; then
  PROFILE="$1"
fi

if [[ $# -ge 2 ]]; then
  SIZE="$2"
fi

case "$PROFILE" in
  dev|test|prod) ;;
  *)
    echo "Unsupported profile: $PROFILE"
    usage
    exit 1
    ;;
esac

case "$SIZE" in
  small)
    JVM_PRESET="-Xms512m -Xmx512m -XX:MaxMetaspaceSize=256m -Xss512k"
    ;;
  medium)
    JVM_PRESET="-Xms1g -Xmx1g -XX:MaxMetaspaceSize=384m -Xss512k"
    ;;
  large)
    JVM_PRESET="-Xms2g -Xmx2g -XX:MaxMetaspaceSize=512m -Xss512k"
    ;;
  *)
    echo "Unsupported size preset: $SIZE"
    usage
    exit 1
    ;;
esac

COMMON_GC_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$LOG_DIR -Xlog:gc*:file=$LOG_DIR/${APP_NAME}-gc.log:time,uptime,level,tags:filecount=5,filesize=20m"
COMMON_RUNTIME_OPTS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Dspring.profiles.active=$PROFILE"

SPRING_OPTS="${SPRING_OPTS:-}"
JAVA_OPTS="${JAVA_OPTS:-}"

if [[ -z "$JAR_PATH" ]]; then
  JAR_PATH="$(find "$TARGET_DIR" -maxdepth 1 -type f -name '*.jar' ! -name '*sources.jar' ! -name '*javadoc.jar' | sort | tail -n 1 || true)"
fi

if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
  echo "Jar not found under $TARGET_DIR"
  echo "Build it first with:"
  echo "  mvn -pl sky-server -am clean package -DskipTests"
  exit 1
fi

echo "Starting $APP_NAME"
echo "  profile: $PROFILE"
echo "  size:    $SIZE"
echo "  jar:     $JAR_PATH"
echo "  gc log:  $LOG_DIR/${APP_NAME}-gc.log"

exec "$JAVA_BIN" \
  $JVM_PRESET \
  $COMMON_GC_OPTS \
  $COMMON_RUNTIME_OPTS \
  $JAVA_OPTS \
  -jar "$JAR_PATH" \
  $SPRING_OPTS
