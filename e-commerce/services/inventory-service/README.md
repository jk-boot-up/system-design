# inventory-service

Owns stock levels and stock reservations. It guarantees the platform **never sells stock it does not have**, even when thousands of
customers check out the same item at the same moment.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0 (virtual threads) |
| Store | PostgreSQL (`stock`, `reservations`, `outbox`, `processed_events`, `shedlock`) |
| Publishes | `inventory.StockChanged` on `inventory.events` |
| Consumes | `order.OrderCancelled` from `order.events` (safety-net release) |
| Called by | order-service (checkout saga) |
| Port | 8084 |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
inventory-service/
├─ README.md, PREREQUISITES.md
├─ design/                      low-level design, diagrams, walkthrough
├─ contracts/                   openapi.yaml, events/ (published), consumed/ (own copies)
├─ src/main/java/com/jkbootup/ecommerce/inventory/
│  ├─ domain/                   Sku, Quantity, Reservation, ReservationStatus, ReserveOutcome (pure Java)
│  ├─ application/              ReservationService (use cases) + port/ (interfaces)
│  ├─ adapter/web/              REST controllers, problem details
│  ├─ adapter/persistence/      JDBC repositories, outbox writer, processed-events ledger
│  ├─ adapter/messaging/        outbox relay → Kafka, OrderCancelled listener
│  ├─ adapter/scheduling/       reservation expiry job (ShedLock)
│  └─ config/                   Spring wiring
├─ src/main/resources/          application.yaml, db/migration (Flyway)
├─ src/test/java/…              domain, application (in-memory doubles), ArchUnit, container integration tests
├─ Dockerfile                   layered jar + class-data-sharing archive, non-root
├─ k8s/                         Deployment, Service, HPA, PDB, NetworkPolicy (Kustomize base)
└─ build.gradle.kts, settings.gradle.kts, gradle/ (own wrapper and version catalog)
```

## Build and test

```bash
./gradlew build                     # compile, unit + architecture tests, jar
./gradlew test -PwithContainers     # also run the PostgreSQL + Kafka integration tests (needs Docker)
docker build -t e-commerce/inventory-service .
```

## Run locally

```bash
# infrastructure only (from e-commerce/deploy/compose)
docker compose up -d postgres kafka
# the service
./gradlew bootRun
```

Configuration (environment variables):

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/inventory` | JDBC URL (PgBouncer in production) |
| `DB_USER` / `DB_PASSWORD` | `inventory` / `inventory` | Database credentials (from a secret in Kubernetes) |
| `DB_POOL_SIZE` | `20` | HikariCP pool size per pod |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka bootstrap servers |
| `PORT` | `8084` | HTTP port |

## Try it

```bash
curl -X PUT localhost:8084/internal/stock/SKU-1001 -H 'content-type: application/json' -d '{"available": 3}'
curl -X POST localhost:8084/internal/reservations -H 'content-type: application/json' \
     -d '{"orderId":"0193a1b0-0000-7000-8000-000000000001","lines":[{"sku":"SKU-1001","quantity":2}]}'
curl localhost:8084/internal/stock/SKU-1001        # {"available":1,"reserved":2,...}
curl -X POST localhost:8084/internal/reservations/0193a1b0-0000-7000-8000-000000000001/commit
```

## Operations

| Signal | Where |
|---|---|
| Health | `/actuator/health/liveness`, `/actuator/health/readiness` |
| Metrics | `/actuator/prometheus` (HTTP, JDBC pool, Kafka producer and consumer, JVM) |
| Outbox backlog | `select count(*) from outbox where published_at is null` — alert when the oldest row is older than 30 s |
| Stuck reservations | `select count(*) from reservations where status = 'RESERVED' and expires_at < now() - interval '5 minutes'` should be 0 |
