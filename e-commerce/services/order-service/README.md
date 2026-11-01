# order-service

Owns orders and runs **checkout as a saga**: it takes the priced cart, reserves stock, authorises and captures payment, and either
confirms the order or undoes every step it took. It also cancels confirmed orders (refund and stock return) and finishes any saga
interrupted by a crash or an outage.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0 (virtual threads), Resilience4j |
| Store | PostgreSQL: `orders` and `order_lines` partitioned by month, `idempotency_keys`, `outbox`, `shedlock` |
| Publishes | `order.OrderPlaced`, `order.OrderConfirmed`, `order.OrderCancelled` on `order.events` |
| Calls | cart-service (snapshot), inventory-service (reserve, commit, release, return), payment-service (authorise, capture, void, refund) |
| Called by | api-gateway (customers with a JWT) |
| Port | 8085 |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
order-service/
├─ README.md, PREREQUISITES.md
├─ design/                      low-level design, diagrams, walkthrough
├─ contracts/                   openapi.yaml, events/ (OrderPlaced, OrderConfirmed, OrderCancelled)
├─ src/main/java/com/jkbootup/ecommerce/order/
│  ├─ domain/                   Order (aggregate), OrderLine, Money, OrderStatus, SagaStep, CancelReason, OrderEvent
│  ├─ application/              CheckoutSaga, OrderQueries, SagaRecovery + port/ (OrderRepository, CartPort, InventoryPort, PaymentPort, …)
│  ├─ adapter/web/              OrderController (JWT), ProblemHandler
│  ├─ adapter/client/           Cart/Inventory/Payment HTTP clients with circuit breaker + retry (ResilientCall)
│  ├─ adapter/persistence/      JdbcOrderRepository (partitions, optimistic lock), OutboxWriter, OrderPartitions, UuidV7
│  ├─ adapter/messaging/        OutboxRelay → Kafka
│  ├─ adapter/scheduling/       OrderJobs (saga recovery)
│  └─ config/                   wiring, security
├─ src/main/resources/          application.yaml, db/migration (Flyway: partitioned schema + partition function)
├─ src/test/java/…              domain, saga (14 paths with test doubles), ArchUnit, UUIDv7, container integration
├─ Dockerfile, k8s/
└─ build.gradle.kts, settings.gradle.kts, gradle/ (own wrapper and version catalog)
```

## Build and test

```bash
./gradlew build                     # unit, saga and architecture tests
./gradlew test -PwithContainers     # + PostgreSQL, Kafka and real HTTP to fake downstream services (needs Docker)
docker build -t e-commerce/order-service .
```

## Run locally

```bash
cd ../../deploy/compose && docker compose up -d postgres kafka keycloak    # infrastructure
./gradlew bootRun                                                         # needs cart, inventory and payment running for checkout
```

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/orders`, `orders`, `orders` | Database |
| `DB_POOL_SIZE` | `30` | HikariCP pool size per pod |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `JWT_ISSUER` | `http://localhost:8180/realms/shop` | Keycloak realm that signs customer tokens |
| `CART_URL`, `INVENTORY_URL`, `PAYMENT_URL` | `http://localhost:8083`, `:8084`, `:8086` | Downstream services |
| `PORT` | `8085` | HTTP port |

Timeouts: connect 200 ms, read 800 ms (payment 2 s). Circuit breakers open at 50% failures over 20 calls for 10 s.

## Try it

```bash
TOKEN=…   # a customer access token from Keycloak (realm "shop")
curl -X POST localhost:8085/api/v1/orders -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: 6f1c' \
     -H 'content-type: application/json' -d '{"paymentToken":"tok_ok"}'
curl localhost:8085/api/v1/orders -H "Authorization: Bearer $TOKEN"
```

## Operations

| Signal | Where / query |
|---|---|
| Health, metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` (includes circuit-breaker states via Resilience4j metrics) |
| Orders stuck in flight | `select count(*) from orders where status in ('PENDING','CANCELLING') and updated_at < now() - interval '5 minutes'` should be 0 |
| Outbox backlog | `select min(created_at) from outbox where published_at is null` older than 30 s → alert |
| Partitions ready | `select ensure_order_partitions(3)` returns 0 when the next three months exist |
