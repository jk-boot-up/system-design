# recommendation-service (Python)

"Customers who bought this also bought." Every confirmed order with two or more products increases a counter for each pair of
products bought together; a product page reads the strongest partners. Counts live in **Redis sorted sets**, and one **Lua script**
both marks the order as seen and counts its pairs, so each order counts exactly once even when Kafka delivers it again.

| | |
|---|---|
| Language / framework | Python 3.14, FastAPI, aiokafka, redis-py (asyncio), Prometheus client |
| Consumes | `order.OrderConfirmed` on `order.events` (group `recommendation-service`); other types ignored |
| Store | Redis: `reco:also:<sku>` sorted set `otherSku → times bought together` (top 200 kept), `reco:seen:<eventId>` (14 days) |
| Dead letters | Unreadable events → `order.events.dlq` with their key and headers, plus `x-reason` and `x-original-*` (replay with [dlq-replay](../../deploy/tools/dlq-replay/README.md)) |
| Called by | api-gateway: `GET /api/v1/products/{sku}/recommendations?limit=10` |
| Port | 8089 (`/healthz`, `/readyz`, `/metrics`) |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
recommendation-service/
├─ README.md, PREREQUISITES.md, design/, contracts/consumed/order.OrderConfirmed.v1.json
├─ src/recommendation/
│  ├─ domain.py                 parse OrderConfirmed → Purchase (distinct SKUs, at most 50), pairs
│  ├─ app.py                    learn() and recommend() + ports (CoPurchaseStore, DeadEvents)
│  ├─ adapters/redis_store.py   Lua script: seen-check and all ZINCRBYs in one atomic step; top N with ZRANGE REV
│  ├─ adapters/kafka_consumer.py  manual commits after each event; retry with back-off; dead-letter producer
│  ├─ adapters/http.py          FastAPI routes, problem responses, metrics
│  └─ main.py                   API and consumer in one asyncio process
├─ tests/                       domain, use cases with fakes, API, import boundaries, Redis + Kafka containers
├─ Dockerfile, k8s/, pyproject.toml (exact versions)
```

## Build, test, run

```bash
python3.14 -m venv .venv && .venv/bin/pip install -e '.[test]'
.venv/bin/ruff check src tests && .venv/bin/pytest            # unit, API, architecture
.venv/bin/pytest -m containers                                # Redis and Kafka containers
cd ../../deploy/compose && docker compose up -d kafka redis && cd - && .venv/bin/python -m recommendation.main
curl -s localhost:8089/api/v1/products/SKU-1001/recommendations?limit=5
```

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `REDIS_URL` | `redis://localhost:6379` | Co-purchase counts |
| `PORT` | `8089` | HTTP port |

## API

`GET /api/v1/products/{sku}/recommendations?limit=10` (limit 1–50) →
`200 {"sku":"SKU-1001","items":[{"sku":"SKU-2002","score":41}, …]}`, `Cache-Control: public, max-age=300`.
A product nobody has bought with anything else gets `items: []`. Redis down → `503 DEPENDENCY_UNAVAILABLE`, `Retry-After: 5`
(the product page renders without the strip).

## Operations

| Signal | Where |
|---|---|
| Events by outcome | `recommendation_events_total{result="counted|duplicate|ignored|dead_letter"}` |
| Lag | Kafka lag of group `recommendation-service`; recommendations are minutes behind at worst, never wrong |
| Memory | About 200 partners × ~30 bytes per product with co-purchases; 100 000 products ≈ 600 MB at most |
