# analytics (ClickHouse)

Business numbers in near real time. ClickHouse reads `order.events` straight from Kafka with a **Kafka engine table**; a
**materialised view** parses each message into `order_facts`; **refreshable materialised views** compute exact rollups
(revenue per minute within 30 seconds, daily figures every hour); a **Grafana** dashboard shows them. No service code: the whole
pipeline is SQL plus configuration.

| | |
|---|---|
| Engine | ClickHouse 26.8 (LTS) |
| Consumes | `order.events` (group `analytics-orders`): `OrderPlaced`, `OrderConfirmed`, `OrderCancelled` |
| Tables | `order_facts` (ReplacingMergeTree, monthly partitions, 25-month TTL), `order_event_errors` (30 days), `revenue_per_minute`, `orders_per_day` |
| Dashboard | Grafana 13.2 with the ClickHouse plugin 4.23: [grafana/dashboards/orders.json](grafana/dashboards/orders.json) |
| Privacy | Customer ids are hashed (`cityHash64`); no emails or names are stored |
| Ports | 8123 (HTTP), 9000 (native), 9363 (Prometheus) |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
analytics/
├─ README.md, PREREQUISITES.md, design/, contracts/consumed/ (order event schemas)
├─ sql/001_order_events.sql     Kafka engine table, facts, errors, parsing views
├─ sql/002_rollups.sql          refreshable views: revenue_per_minute (30 s), orders_per_day (1 h)
├─ config.d/                    Kafka named collection (brokers from KAFKA_BROKERS), Prometheus endpoint
├─ users.d/                     named-collection grant; read-only grafana user (password from the environment)
├─ grafana/                     datasource and dashboard provisioning, orders.json
├─ tests/                       the image + Kafka in containers (pytest)
├─ Dockerfile, k8s/
```

## Build, test, run

```bash
python3.14 -m venv .venv && .venv/bin/pip install -r tests/requirements.txt && .venv/bin/pytest
docker build -t e-commerce/analytics .
docker run -d -p 8123:8123 -e KAFKA_BROKERS=host.docker.internal:9092 -e CLICKHOUSE_GRAFANA_PASSWORD=change-me e-commerce/analytics
curl -s 'localhost:8123/?query=SELECT+*+FROM+analytics.revenue_per_minute+ORDER+BY+minute+DESC+LIMIT+5'
```

| Variable | Meaning |
|---|---|
| `KAFKA_BROKERS` | Broker list for the Kafka engine table |
| `CLICKHOUSE_PASSWORD` | Password of the `default` (schema owner) user; set it outside local use |
| `CLICKHOUSE_GRAFANA_PASSWORD` | Password of the read-only `grafana` user |
| `CLICKHOUSE_HOST` (Grafana) | Where the datasource connects |

## Operations

| Signal | Where |
|---|---|
| Consumer health | `SELECT * FROM system.kafka_consumers` (assignments, last exception); Kafka lag of group `analytics-orders` |
| Freshness | Dashboard panel "Ingestion delay (p95)"; `system.view_refreshes` for the rollups' last success |
| Bad messages | `analytics.order_event_errors` (dashboard panel "Unreadable events") |
| Rebuild | `TRUNCATE analytics.order_facts`, reset the group's offsets to the earliest, then `SYSTEM REFRESH VIEW` on both rollups |
