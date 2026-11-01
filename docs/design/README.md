# Design documents

The detailed design of the e-commerce platform. Start with the [specification](../SPEC.md) for requirements and the reasons behind
the design, then read these documents in order.

| # | Document | What it contains |
|---|---|---|
| 1 | [High-level design](01-high-level-design.md) | System context, containers, layers, request and event paths, technology map |
| 2 | [Data-flow diagrams](02-data-flow-diagrams.md) | DFD level 0, level 1 and level 2 for checkout; data stores; trust boundaries |
| 3 | [Deployment design](03-deployment.md) | Kubernetes topology across zones, networking, CI/CD pipeline, environments, local stack |
| 4 | [Database design](04-database-design.md) | ER diagrams, tables, indexes, partitioning, Cassandra tables, Redis keys, OpenSearch mapping, retention |
| 5 | [Sequence diagrams](05-sequence-diagrams.md) | Checkout (happy path and every failure), cancellation, outbox relay, idempotent consumer, sign-in, uploads, search indexing, reviews |
| 6 | [Flow diagrams](06-flow-diagrams.md) | Checkout decisions, saga recovery, reservation expiry, rate limiting, consumer retry and dead letters, cache-aside |
| 7 | [State diagrams](07-state-diagrams.md) | Order, saga step, payment and reservation state machines |
| 8 | [Class and UML diagrams](08-class-diagrams.md) | Domain models, ports and adapters, platform libraries, component and package diagrams |
| 9 | [API catalogue](09-api-catalogue.md) | Every public and internal endpoint with request and response shapes |
| 10 | [Event catalogue](10-event-catalogue.md) | Every event, topic, key, producer, consumer and payload |
| 11 | [Low-level design per service](services/) | One document per microservice |
| 12 | [Architecture decision records](../adr/) | One record per significant decision |

## Microservices

| Service | Language | Port | Store | Low-level design |
|---|---|---|---|---|
| api-gateway | Java 25 | 8080 | Redis | [api-gateway.md](services/api-gateway.md) |
| catalog-service | Java 25 | 8081 | PostgreSQL, MinIO | [catalog-service.md](services/catalog-service.md) |
| search-service | Java 25 | 8082 | OpenSearch | [search-service.md](services/search-service.md) |
| cart-service | Java 25 | 8083 | Redis | [cart-service.md](services/cart-service.md) |
| inventory-service | Java 25 | 8084 | PostgreSQL | [inventory-service.md](services/inventory-service.md) |
| order-service | Java 25 | 8085 | PostgreSQL | [order-service.md](services/order-service.md) |
| payment-service | Java 25 | 8086 | PostgreSQL | [payment-service.md](services/payment-service.md) |
| review-service | Java 25 | 8087 | Cassandra | [review-service.md](services/review-service.md) |
| recommendation-service | Python 3.12 | 8089 | Redis | [recommendation-service.md](services/recommendation-service.md) |
| notification-service | Go 1.24 | 8088 | RabbitMQ, Redis | [notification-service.md](services/notification-service.md) |
| analytics | ClickHouse SQL | 8123 | ClickHouse | [analytics.md](services/analytics.md) |

All diagrams are written in [Mermaid](https://mermaid.js.org), which GitHub renders in place. The animated walkthroughs of the same
flows are in [`docs/visual`](../visual/).
