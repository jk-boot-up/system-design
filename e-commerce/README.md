# E-commerce platform

A production-grade online store: scalable, modular and high-performance, built from independent services in Java, Go and Python
over PostgreSQL, Cassandra, Redis, OpenSearch, ClickHouse, MinIO, Kafka and RabbitMQ.

| Read | |
|---|---|
| [Status](STATUS.md) | Current progress: what is done, in progress and next |
| [Design plan](design/DESIGN-PLAN.md) · [Implementation plan](design/IMPLEMENTATION-PLAN.md) | Every design and implementation work item, with status |
| [Prerequisites](PREREQUISITES.md) | Tools, versions, machine resources, ports, and how to verify your setup |
| [Specification](design/SPEC.md) | Requirements, capacity estimates, architecture, flows, data, operations, patterns, languages |
| [Plan](design/PLAN.md) | Goals, technology, milestones, working agreements |
| [System design documents](design/README.md) | HLD, data flow, deployment, database, sequence, flow, state and class diagrams, API and event catalogues |
| [Animated walkthrough](design/walkthrough/index.html) | The whole system in motion, with themes and narration |

## Layout

```
e-commerce/
├─ README.md                     this page
├─ PREREQUISITES.md              tools, versions, resources and ports for the whole system
├─ STATUS.md                     current progress
├─ design/                       WHOLE-SYSTEM DESIGN
│  ├─ README.md                  index of the design documents
│  ├─ SPEC.md, PLAN.md           specification and delivery plan
│  ├─ DESIGN-PLAN.md             every design deliverable with status
│  ├─ IMPLEMENTATION-PLAN.md     every implementation work item, definition of done, test strategy
│  ├─ 01-high-level-design.md … 10-event-catalogue.md
│  ├─ adr/                       architecture decision records
│  ├─ diagrams/                  SVG diagrams (light + dark); editable sources in diagrams/src
│  └─ walkthrough/               animated walkthrough of the whole system (themes + narration)
├─ services/                     IMPLEMENTATION: one standalone project per service, nothing shared
│  └─ <service>/
│     ├─ README.md               what it does; how to build, run and test it
│     ├─ PREREQUISITES.md        what this service alone needs
│     ├─ design/                 the service's own design
│     │  ├─ README.md            low-level design: API, events, data, classes, sequences, scaling, failure handling
│     │  ├─ diagrams/            its SVG diagrams and their sources
│     │  └─ walkthrough/         its animated walkthrough
│     ├─ contracts/              openapi.yaml, events it publishes, copies of events it consumes
│     ├─ source + build          Java: gradlew, settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, src/
│     │                          Go: go.mod, cmd/, internal/      Python: pyproject.toml, src/, tests/
│     ├─ Dockerfile
│     └─ k8s/                    Kubernetes base: Deployment, Service, HPA, PDB, NetworkPolicy, ConfigMap
├─ deploy/                       RUNNING THE WHOLE SYSTEM
│  ├─ compose/                   local stack (project name "e-commerce"): data stores, brokers, Keycloak, observability, services
│  └─ k8s/overlays/              dev / staging / prod overlays composing every service's k8s base
├─ tests/                        WHOLE-SYSTEM TESTS
│  ├─ e2e/                       end-to-end journeys against the running stack
│  └─ performance/               Gatling simulations with p99 budgets
└─ tools/                        documentation pipeline (diagrams → SVG, Markdown → HTML), scoped to this folder
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

## Documentation

Every Markdown file has an HTML counterpart with light, medium and dark themes. Diagrams are SVG images (light and dark) rendered
from the editable sources in each `design/diagrams/src` folder.

```bash
tools/docs.sh                 # extract new diagram sources, render changed diagrams, regenerate HTML
```

## Run everything locally

```bash
cd deploy/compose && docker compose up -d     # project name "e-commerce"; host ports 8080–8099 for services, see deploy/compose/README.md
```
