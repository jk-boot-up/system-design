# 1. High-level design

## 1.1 System context (C4 level 1)

```mermaid
flowchart TB
    customer([Customer<br/>web / mobile])
    staff([Staff<br/>catalogue and stock managers])
    subgraph platform[E-commerce platform]
        core[Online store backend]
    end
    idp[Keycloak<br/>identity provider]
    psp[Payment provider<br/>card tokenisation and charges]
    smtp[Email relay<br/>SMTP]
    cdn[CDN + WAF]

    customer -->|browse, search, cart, checkout| cdn
    staff -->|manage catalogue and stock| cdn
    cdn -->|HTTPS| core
    customer -.->|sign in| idp
    staff -.->|sign in| idp
    core -->|validate tokens - JWKS| idp
    core -->|authorise, capture, refund| psp
    customer -.->|card details, tokenised client side| psp
    core -->|order emails| smtp
```

The platform never sees card numbers; the browser tokenises them directly with the payment provider. That keeps the platform in
PCI DSS SAQ A scope.

## 1.2 Containers (C4 level 2)

```mermaid
flowchart TB
    subgraph edge[Edge]
        cdn[CDN + WAF]
        gw[api-gateway<br/>Spring Cloud Gateway]
    end

    subgraph services[Microservices - Java 25 / Spring Boot 4, plus Go and Python]
        cat[catalog-service]
        srch[search-service]
        cart[cart-service]
        inv[inventory-service]
        ord[order-service<br/>saga orchestrator]
        pay[payment-service]
        rev[review-service]
        notif[notification-service<br/>Go]
        reco[recommendation-service<br/>Python]
    end

    subgraph data[Data stores - one per service]
        pgcat[(PostgreSQL<br/>catalog)]
        minio[(MinIO<br/>images)]
        os[(OpenSearch<br/>products index)]
        rcart[(Redis<br/>carts)]
        pginv[(PostgreSQL<br/>inventory)]
        pgord[(PostgreSQL<br/>orders)]
        pgpay[(PostgreSQL<br/>payments)]
        cass[(Cassandra<br/>reviews)]
        rnot[(Redis<br/>dedupe)]
        rmq[(RabbitMQ<br/>email jobs)]
        rreco[(Redis<br/>co-purchase sets)]
        ch[(ClickHouse<br/>analytics)]
        rgw[(Redis<br/>rate limits)]
    end

    kafka{{Kafka<br/>catalog / inventory / order / payment / review topics}}

    cdn --> gw
    gw --> cat & srch & cart & ord & rev
    gw --- rgw
    cat --- pgcat
    cat --- minio
    srch --- os
    cart --- rcart
    inv --- pginv
    ord --- pgord
    pay --- pgpay
    rev --- cass
    notif --- rnot
    notif --- rmq
    reco --- rreco
    gw --> reco

    cart -->|price lookup| cat
    ord -->|checkout snapshot| cart
    ord -->|reserve / commit / release| inv
    ord -->|authorise / capture / void / refund| pay

    cat -. ProductChanged .-> kafka
    inv -. StockChanged .-> kafka
    ord -. OrderPlaced / Confirmed / Cancelled .-> kafka
    pay -. PaymentCaptured / Failed / Refunded .-> kafka
    rev -. ReviewPosted .-> kafka
    kafka -. ProductChanged, StockChanged .-> srch
    kafka -. OrderConfirmed .-> cart
    kafka -. OrderConfirmed .-> rev
    kafka -. OrderCancelled .-> inv
    kafka -. Order events .-> notif
    kafka -. OrderConfirmed .-> reco
    kafka -. order + payment events .-> ch
```

**Solid arrows are synchronous HTTP calls** (inside the cluster, with mTLS). **Dotted arrows are events** published through the
transactional outbox and consumed by idempotent consumers.

## 1.3 Layers

```mermaid
flowchart LR
    subgraph L1[Client layer]
        web[Web / mobile apps]
    end
    subgraph L2[Edge layer]
        cdn2[CDN, WAF]
        gw2[API gateway<br/>auth, rate limit, routing]
    end
    subgraph L3[Service layer]
        sync[Request services<br/>catalog, cart, order, review]
        async[Event processors<br/>search indexer, notifications, recommendations, analytics]
        core2[Core transactional services<br/>inventory, payment]
    end
    subgraph L4[Data layer]
        sql[(Relational)]
        kv[(Key-value)]
        idx[(Search)]
        wide[(Wide-column)]
        obj[(Object)]
        log{{Event log}}
    end
    subgraph L5[Platform layer]
        k8s[Kubernetes, autoscaling]
        obs[OpenTelemetry, Prometheus,<br/>Grafana, Tempo, Loki]
        sec[Keycloak, secrets store]
    end
    L1 --> L2 --> L3 --> L4
    L5 -.supports.- L3
```

## 1.4 Inside every service: hexagonal architecture

```mermaid
flowchart LR
    subgraph adapters_in[Driving adapters]
        rest[REST controllers]
        consumer[Kafka consumers]
        job[Scheduled jobs]
    end
    subgraph app[Application]
        uc[Use cases<br/>one transaction each]
        portsin[Inbound ports]
        portsout[Outbound ports]
    end
    subgraph dom[Domain - pure Java]
        agg[Aggregates, value objects,<br/>domain events, policies]
    end
    subgraph adapters_out[Driven adapters]
        db[Persistence<br/>JDBC, Cassandra, Redis, OpenSearch]
        clients[HTTP clients<br/>with Resilience4j]
        outbox[Outbox writer]
    end
    rest & consumer & job --> portsin --> uc --> agg
    uc --> portsout
    portsout -. implemented by .-> db & clients & outbox
```

Dependencies point inwards. The `domain` package imports nothing from Spring or any adapter. ArchUnit tests in every service enforce
this.

## 1.5 Request path and event path

| Path | Example | Mechanism | Consistency |
|---|---|---|---|
| Read | Product page, search | Gateway → service → cache or read model | Eventually consistent read models (< 1 s typical) |
| Command | Add to cart | Gateway → cart-service → Redis | Immediate within the service |
| Business transaction | Checkout | Gateway → order-service → orchestrated calls to cart, inventory, payment | Saga with compensations; final state is always consistent |
| Propagation | Search indexing, emails, cart clearing | Outbox → Kafka → idempotent consumer | Eventually consistent, at-least-once delivery, effectively-once effect |

## 1.6 Technology map

| Concern | Technology |
|---|---|
| Runtime | Java 25 (virtual threads), Spring Boot 4.0, Spring Cloud 2025.1; Go 1.24 for notification-service; Python 3.12 + FastAPI for recommendation-service (ADR 0007) |
| Build | Gradle 9 multi-project, version catalog, convention plugins |
| Edge | CDN + WAF (provider choice), Spring Cloud Gateway |
| Identity | Keycloak, OAuth 2.1 / OIDC, JWT access tokens |
| Relational | PostgreSQL 17 + PgBouncer, Flyway |
| Key-value | Redis 8 |
| Wide-column | Apache Cassandra 5 |
| Search | OpenSearch 3 |
| Object storage | MinIO (S3 API) |
| Messaging | Apache Kafka 4 (KRaft) for events; RabbitMQ 4 for email jobs |
| Analytics | ClickHouse (Kafka table engine), Grafana |
| Resilience | Resilience4j through Spring Cloud CircuitBreaker |
| Observability | OpenTelemetry, Micrometer, Prometheus, Grafana, Tempo, Loki |
| Deployment | Docker images (layered, AppCDS), Kubernetes + Kustomize |
| Tests | JUnit 6, AssertJ, ArchUnit, Testcontainers, Gatling |

## 1.7 Quality attributes and how the design meets them

| Quality | Design response |
|---|---|
| Scalability | Stateless services behind HPA; partitioned Kafka; read replicas; Cassandra and OpenSearch scale out; Redis Cluster |
| Availability | Two or more replicas per service across three zones; PodDisruptionBudgets; graceful degradation (browse and search survive checkout outages) |
| Performance | Virtual threads; layered caching; read models; keyset pagination; batching; conditional single-statement updates |
| Correctness | Saga with compensations; idempotency keys; outbox; idempotent consumers; database constraints as a final guard |
| Modularity | One bounded context per service; hexagonal layers; platform libraries with no business logic |
| Security | OIDC + JWT; ownership checks; mTLS; secrets from a secret store; no card data |
| Operability | Traces, metrics and logs correlated by trace id; SLO burn-rate alerts; runbooks |
