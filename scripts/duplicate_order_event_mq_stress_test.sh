#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/duplicate_order_event_mq_stress_test.sh [publish_count]
#
# Example:
#   ./scripts/duplicate_order_event_mq_stress_test.sh 30
#
# This script publishes the SAME eventId repeatedly to RabbitMQ exchange,
# so OrderEventMqConsumer should print "mq duplicate ignored" after first consume.

PUBLISH_COUNT="${1:-20}"

RABBIT_HOST="${RABBIT_HOST:-localhost}"
RABBIT_MGMT_PORT="${RABBIT_MGMT_PORT:-15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
RABBIT_VHOST="${RABBIT_VHOST:-/}"

EXCHANGE="${EXCHANGE:-sky.order.event.exchange}"
ROUTING_KEY="${ROUTING_KEY:-order.created}"
EVENT_ID="${EVENT_ID:-evt-order-dup-001}"
BIZ_KEY="${BIZ_KEY:-order-dup-biz-001}"
EVENT_TYPE="${EVENT_TYPE:-ORDER_CREATED}"
PAYLOAD="${PAYLOAD:-orderId=9999,userId=4,amount=13}"

if ! [[ "$PUBLISH_COUNT" =~ ^[0-9]+$ ]] || [[ "$PUBLISH_COUNT" -le 0 ]]; then
  echo "publish_count must be positive integer"
  exit 1
fi

if [[ "$RABBIT_VHOST" == "/" ]]; then
  VHOST_ENCODED="%2F"
else
  VHOST_ENCODED="$RABBIT_VHOST"
fi

API_URL="http://${RABBIT_HOST}:${RABBIT_MGMT_PORT}/api/exchanges/${VHOST_ENCODED}/${EXCHANGE}/publish"

echo "start duplicate order-event publish..."
echo "api: ${API_URL}"
echo "eventId: ${EVENT_ID}"
echo "count: ${PUBLISH_COUNT}"

for ((i=1; i<=PUBLISH_COUNT; i++)); do
  BODY=$(cat <<EOF
{
  "properties": {
    "headers": {
      "eventId": "${EVENT_ID}",
      "bizKey": "${BIZ_KEY}",
      "eventType": "${EVENT_TYPE}"
    }
  },
  "routing_key": "${ROUTING_KEY}",
  "payload": "${PAYLOAD}",
  "payload_encoding": "string"
}
EOF
)

  RESP=$(curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
    -H "content-type: application/json" \
    -X POST "${API_URL}" \
    -d "${BODY}")

  if [[ "$RESP" != *"\"routed\":true"* ]]; then
    echo "publish failed at #${i}: ${RESP}"
    exit 1
  fi
done

echo "publish done."
echo "expected:"
echo "  1) OrderEventMqConsumer logs first as 'mq consume success'"
echo "  2) remaining logs as 'mq duplicate ignored'"
echo "check SQL:"
echo "  select consumer_name, event_id, count(*)"
echo "  from take_out_v2.order_event_consume_log"
echo "  where event_id='${EVENT_ID}'"
echo "  group by consumer_name, event_id;"
