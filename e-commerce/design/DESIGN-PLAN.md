# Design plan

Every design deliverable for the e-commerce platform: what it is, where it lives, and its status. The implementation work is in
[IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md); the overview and milestones are in [PLAN.md](PLAN.md).

Status: ✅ done · 🔄 in progress · ⏳ planned

## 1. Rules every design document follows

1. **Two formats.** Every Markdown file has an HTML counterpart beside it, with light, medium and dark themes.
2. **Diagrams are images.** Diagrams are SVG files in a light and a dark variant, rendered from editable sources in
   `design/diagrams/src`. A diagram that does not render fails the documentation build.
3. **Matches the code.** Names of classes, tables, endpoints and events in the documents are the names used in the code.
4. **Two levels.** The whole system is designed in `e-commerce/design/`. Each service repeats the parts it owns in more detail in
   `services/<service>/design/`.
5. **Animated.** Every design has an animated walkthrough with themes and optional narration (an on/off button, female voice).

## 2. Whole-system design (`e-commerce/design/`)

| # | Deliverable | Contents | Status |
|---|---|---|---|
| S1 | [SPEC.md](SPEC.md) | Requirements, capacity estimates, architecture, flows, data, cross-cutting concerns, technology, deployment, failure modes, scaling, polyglot persistence, patterns, languages | ✅ |
| S2 | [PLAN.md](PLAN.md) | Goals, non-goals, technology summary, milestones, principles, risks | ✅ |
| S3 | [01 High-level design](01-high-level-design.md) | C4 context and containers, layers, hexagon, request and event paths, technology map, quality attributes | ✅ |
| S4 | [02 Data-flow diagrams](02-data-flow-diagrams.md) | DFD levels 0, 1 and 2 (place order), data stores, trust boundaries | ✅ |
| S5 | [03 Deployment design](03-deployment.md) | Kubernetes topology across zones, node pools, objects per service, network policies, CI/CD, environments, local stack, disaster recovery | ✅ |
| S6 | [04 Database design](04-database-design.md) | ER diagrams and DDL per store, partitioning, Cassandra tables, Redis keys, OpenSearch mapping, RabbitMQ topology, ClickHouse tables, retention | ✅ |
| S7 | [05 Sequence diagrams](05-sequence-diagrams.md) | Sign-in, browse, search, cart, checkout (happy path and every failure), cancellation, outbox, idempotent consumer, indexing, uploads, reviews, emails, recommendations | ✅ |
| S8 | [06 Flow diagrams](06-flow-diagrams.md) | Checkout decisions, saga recovery, reservation expiry, gateway pipeline, rate limiting, retry and dead letters, cache-aside, flash sale, recommendations | ✅ |
| S9 | [07 State diagrams](07-state-diagrams.md) | Order, saga step, payment, reservation, email job | ✅ |
| S10 | [08 Class and UML diagrams](08-class-diagrams.md) | Component, domain classes per context, ports and adapters, outbox, package and UML deployment views | ✅ |
| S11 | [09 API catalogue](09-api-catalogue.md) | Conventions, every public and internal endpoint, error codes | ✅ |
| S12 | [10 Event catalogue](10-event-catalogue.md) | Envelope, topics, every event payload, consumers, evolution rules | ✅ |
| S13 | 11 System, module and service diagrams | System diagram, module diagram of every service, service-interaction map, UML communication diagrams of the key flows | 🔄 |
| S14 | 12 Security design | Threat model (STRIDE per trust boundary), authentication and authorisation flows, secrets, data protection | ⏳ |
| S15 | 13 Observability and SLOs | SLIs and SLOs per journey, dashboards, alerts with burn rates, tracing and log conventions | ⏳ |
| S16 | 14 Capacity and performance model | Per-service throughput, connection and partition budgets at 1× and 10×, load-test plan and budgets | ⏳ |
| S17 | 15 Runbooks | Outbox backlog, DLQ replay, stuck sagas, hot SKU, database failover, Kafka broker loss, cache stampede | ⏳ |
| S18 | [ADRs](adr/README.md) | 11 decision records (microservices, no shared code, saga, outbox, brokers, languages, polyglot persistence, ids, JDBC, diagrams) | ✅ (new ADRs as decisions arise) |
| S19 | System walkthrough (`walkthrough/`) | Animated: architecture tour (system → services → modules), browse and search, cart, checkout happy path, out of stock, payment declined, payment timeout, cancellation and refund, event fan-out, flash sale, failure of a dependency | ⏳ |
| S20 | [PREREQUISITES.md](../PREREQUISITES.md) | Tools, versions, resources, ports, verification | ✅ |

## 3. Per-service design (`services/<service>/design/`)

Each service's design folder contains the same sections, so they are easy to compare:

| Section | Contents |
|---|---|
| Responsibilities | What it does and, just as important, what it does not do |
| Guarantees | The correctness properties it promises and how each is achieved |
| Module diagram | Its internal modules (domain, application, ports, adapters) and their dependencies |
| Interaction diagram | Who calls it, whom it calls, which events it publishes and consumes |
| Class diagram | Domain model, ports and adapters with their main methods |
| Sequence diagrams | Its main use cases end to end |
| State diagrams | Lifecycles it owns |
| Data model | ER diagram or key/table design of its own store, with a link to the migration or schema |
| Background work | Scheduled jobs and consumers: where they run, how often, what they do |
| Scaling and performance | Replicas, pools, partitions, hot spots, caching, budgets |
| Failure handling | Each dependency failing, and what the service does then |
| Tests | Each test suite and what it proves |
| Walkthrough | `walkthrough/index.html`: an animated version of the sections above |

Each service also has its own `README.md` (build, run, configure, operate) and `PREREQUISITES.md`.

| Service | Language | Design doc | Walkthrough | README + prerequisites |
|---|---|---|---|---|
| inventory-service | Java | ✅ | ✅ | ✅ |
| order-service | Java | 🔄 | ⏳ | ⏳ |
| payment-service | Java | ⏳ | ⏳ | ⏳ |
| cart-service | Java | ⏳ | ⏳ | ⏳ |
| catalog-service | Java | ⏳ | ⏳ | ⏳ |
| search-service | Java | ⏳ | ⏳ | ⏳ |
| review-service | Java | ⏳ | ⏳ | ⏳ |
| api-gateway (+ NGINX edge) | Java | ⏳ | ⏳ | ⏳ |
| notification-service | Go | ⏳ | ⏳ | ⏳ |
| recommendation-service | Python | ⏳ | ⏳ | ⏳ |
| analytics | ClickHouse SQL | ⏳ | ⏳ | ⏳ |

## 4. Order of work

1. Finish the system diagrams in S13. They anchor every per-service module and interaction diagram.
2. Write each service's design together with its implementation, in the order of [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md). The document is updated in the same commit as the code it describes.
3. Write the system walkthrough (S19) once checkout works end to end, so every animated step mirrors real behaviour.
4. Finish the cross-cutting documents (S14 to S17) once the services, compose stack and dashboards exist, so they describe real
   configuration.
