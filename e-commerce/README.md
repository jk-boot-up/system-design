# E-commerce platform

A production-grade online store: scalable, modular and high-performance, built from independent services in Java, Go and Python
over PostgreSQL, Cassandra, Redis, OpenSearch, ClickHouse, MinIO, Kafka and RabbitMQ.

| Read | |
|---|---|
| [Specification](docs/SPEC.md) | Requirements, capacity estimates, architecture, flows, data, operations, patterns, languages |
| [Plan](docs/PLAN.md) | Goals, technology, milestones, working agreements |
| [System design documents](docs/design/README.md) | HLD, data flow, deployment, database, sequence, flow, state and class diagrams, API and event catalogues |
| [Animated walkthrough](docs/visual/index.html) | The whole system in motion, with themes and narration |

## Layout

```
e-commerce/
├─ docs/                         whole-system documents
│  ├─ SPEC.md, PLAN.md
│  ├─ design/                    system-level design documents
│  ├─ adr/                       architecture decision records
│  ├─ diagrams/                  SVG diagrams (light + dark) and their sources in diagrams/src
│  └─ visual/                    whole-system animated walkthrough
├─ services/                     one standalone project per service — nothing shared between them
│  └─ <service>/
│     ├─ README.md               what it does, how to build, run and test it
│     ├─ docs/                   the service's own design: low-level design, diagrams, animation
│     │  ├─ design.md
│     │  ├─ diagrams/
│     │  └─ visual/index.html
│     ├─ contracts/              APIs it exposes (OpenAPI) and events it publishes or consumes (JSON Schema)
│     ├─ src/ … or cmd/, internal/ (Go) … or src/, tests/ (Python)
│     ├─ build files             gradlew + settings.gradle.kts + build.gradle.kts + gradle/libs.versions.toml (Java),
│     │                          go.mod (Go), pyproject.toml (Python)
│     ├─ Dockerfile
│     └─ k8s/                    the service's Kubernetes base (Deployment, Service, HPA, PDB, NetworkPolicy)
├─ deploy/
│  ├─ compose/                   local stack: data stores, brokers, Keycloak, observability, all services
│  └─ k8s/overlays/              dev / staging / prod overlays composing every service's k8s base
└─ tests/
   ├─ e2e/                       end-to-end tests against the running stack
   └─ performance/               Gatling simulations with p99 budgets
```

## Services

| Service | Language | Store | Project |
|---|---|---|---|
| api-gateway | Java 25 | Redis | [services/api-gateway](services/api-gateway/README.md) |
| catalog-service | Java 25 | PostgreSQL, MinIO | [services/catalog-service](services/catalog-service/README.md) |
| search-service | Java 25 | OpenSearch | [services/search-service](services/search-service/README.md) |
| cart-service | Java 25 | Redis | [services/cart-service](services/cart-service/README.md) |
| inventory-service | Java 25 | PostgreSQL | [services/inventory-service](services/inventory-service/README.md) |
| order-service | Java 25 | PostgreSQL | [services/order-service](services/order-service/README.md) |
| payment-service | Java 25 | PostgreSQL | [services/payment-service](services/payment-service/README.md) |
| review-service | Java 25 | Cassandra | [services/review-service](services/review-service/README.md) |
| recommendation-service | Python 3.12 | Redis | [services/recommendation-service](services/recommendation-service/README.md) |
| notification-service | Go 1.24 | RabbitMQ, Redis | [services/notification-service](services/notification-service/README.md) |
| analytics | ClickHouse SQL | ClickHouse | [services/analytics](services/analytics/README.md) |

## Build a service

Every service builds and tests on its own; nothing at this level builds them all.

```bash
cd services/order-service && ./gradlew build                     # Java
cd services/notification-service && go test ./...                # Go
cd services/recommendation-service && pip install -e '.[dev]' && pytest   # Python
```

## Run everything locally

```bash
cd deploy/compose && docker compose up -d     # data stores, brokers, Keycloak, observability and all services
```
