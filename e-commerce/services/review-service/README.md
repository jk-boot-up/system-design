# review-service

Product reviews and star ratings in Cassandra. A customer can review a product **once**; the review shows a **verified** badge
when the customer bought the product, which the service learns from `order.OrderConfirmed`. Every table is shaped for one query,
and posting is **resumable**: if a pod dies half-way, the customer's retry finishes the review and the counters are never
incremented twice.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0 (virtual threads), Apache Cassandra Java driver 4.19 |
| Store | Cassandra keyspace `reviews`: `reviews_by_product`, `review_buckets_by_product`, `rating_summary_by_product` (counters), `purchases_by_customer`, `review_guard` |
| Consumes | `order.OrderConfirmed` (purchases for the verified badge) |
| Publishes | `review.ReviewPosted` on `review.events` (best effort) |
| Called by | api-gateway |
| Port | 8087 |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
review-service/
├─ README.md, PREREQUISITES.md, design/, contracts/ (openapi.yaml, events/, consumed/)
├─ src/main/java/com/jkbootup/ecommerce/review/
│  ├─ domain/                   Review (month bucket), RatingSummary, PostOutcome, ReviewPage
│  ├─ application/              ReviewService (claim → append → count once) + port/ (ReviewStore, PurchaseLedger, ReviewEvents)
│  ├─ adapter/cassandra/        CassandraReviewStore (prepared statements, lightweight transactions), SchemaInitializer
│  ├─ adapter/messaging/        OrderConfirmedListener, KafkaReviewEvents
│  ├─ adapter/web/              ReviewController, ProblemHandler
│  └─ config/                   wiring, security (reads public, posting needs a customer token)
├─ src/main/resources/cassandra/schema.cql
├─ src/test/java/…              domain, service with crash injection, ArchUnit, Cassandra + Kafka containers
├─ Dockerfile, k8s/, build files
```

## Build, test, run

```bash
./gradlew build
./gradlew test -PwithContainers                 # Cassandra + Kafka containers
cd ../../deploy/compose && docker compose up -d cassandra kafka && cd - && ./gradlew bootRun
```

| Variable | Default | Meaning |
|---|---|---|
| `CASSANDRA_CONTACT_POINTS` | `localhost:9042` | Cassandra |
| `CASSANDRA_DC` | `datacenter1` | Local data centre |
| `CASSANDRA_REPLICATION` | `{'class': 'SimpleStrategy', 'replication_factor': 1}` | Keyspace replication; production uses `NetworkTopologyStrategy` with 3 replicas |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `JWT_ISSUER` | `http://localhost:8180/realms/shop` | Keycloak realm |
| `PORT` | `8087` | HTTP port |

## Try it

```bash
curl localhost:8087/api/v1/products/SKU-1001/reviews
curl -X POST localhost:8087/api/v1/products/SKU-1001/reviews -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"rating":5,"title":"Great grip","body":"No slips on wet rock."}'          # 201, then 409 ALREADY_REVIEWED
```

## Operations

| Signal | Where |
|---|---|
| Health, metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` (Cassandra driver metrics included) |
| Unfinished posts | `SELECT count(*) FROM reviews.review_guard WHERE counted = false ALLOW FILTERING` (operator query; should be near 0) |
| Purchase feed | Consumer lag of group `review-service` on `order.events`; `order.events.dlq` depth |
