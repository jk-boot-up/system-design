#!/bin/bash
# Topics from design/10-event-catalogue.md. Brokers do not auto-create topics, as in production.
set -euo pipefail
B=kafka:29092
create() { /opt/kafka/bin/kafka-topics.sh --bootstrap-server $B --create --if-not-exists --topic "$1" --partitions "$2" --replication-factor 1 ${3:+--config $3}; }
for t in catalog.events inventory.events order.events payment.events; do
  create $t 12 retention.ms=604800000
  create $t.dlq 3 retention.ms=2592000000
done
create review.events 6 retention.ms=604800000
create review.events.dlq 3 retention.ms=2592000000
create catalog.products 12 cleanup.policy=compact
echo "topics ready"
