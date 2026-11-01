# E-commerce platform: delivery plan

This plan covers how the e-commerce system described in [SPEC.md](SPEC.md) is built, in what order, and how each step is verified.

* **Plan and spec** live on `main`.
* **Implementation** happens on `system-design-e-commerce-draft-1` and is merged to `main` once it is reviewed.

## 1. Goals

1. A production-grade **reference design** for an online store, written as a system design spec with supporting design documents.
2. A **working implementation**:
   * Java 25 and Gradle as the main stack, open-source technology only.
   * Organised so each service can be built, tested, deployed and scaled on its own.
3. **Visual walkthroughs** of the architecture and the key flows, which:
   * animate the flows in the browser;
   * support light, medium and dark themes;
   * offer optional voice narration with an on/off button.

## 2. Non-goals (first release)

* **Real payment processors.** Payment uses a pluggable provider port with a simulated provider. A real provider (for example Stripe or Adyen) is an adapter added later.
* **A full storefront UI.** A small demo storefront is planned as a later milestone; the first release exposes the platform through APIs.
* **Recommendations, marketplace sellers, multi-currency tax engines.** These are listed as extensions in the spec.

## 3. Technology choices (summary)

All components are open source. See [SPEC.md §9](SPEC.md#9-technology-choices) for the reasoning behind each choice.

| Concern | Choice |
|---|---|
| Language / build | Java 25, Gradle 9 (Kotlin DSL, version catalog) |
| Service framework | Spring Boot 4.0, Spring Cloud 2025.1 |
| API gateway | Spring Cloud Gateway |
| Identity | Keycloak (OIDC / OAuth 2.1, JWT) |
| Relational data | PostgreSQL 17 (catalog, inventory, orders, payments), Flyway migrations, PgBouncer; Citus when orders need sharding |
| Wide-column data | Apache Cassandra 5 (reviews) |
| Object storage | MinIO, S3 API (product images, presigned uploads) |
| Cache, cart, rate limits | Redis 8 (Valkey-compatible) |
| Events | Apache Kafka 4 (KRaft), transactional outbox |
| Search | OpenSearch 3 |
| Resilience | Resilience4j (timeouts, retries, circuit breakers, bulkheads) |
| Observability | OpenTelemetry, Prometheus, Grafana, Tempo, Loki |
| Local platform | Docker Compose |
| Production platform | Kubernetes (Kustomize manifests, HPA, PDB) |
| Testing | JUnit 6, AssertJ, ArchUnit, Testcontainers |
| Load testing | Gatling (Java DSL) with p99 budgets in CI |
| Scheduled jobs | ShedLock (one runner per job across replicas) |
| CI | GitHub Actions |

## 4. Repository layout (target)

```
system-design/
├─ docs/                    plan, spec, ADRs, per-service designs, runbooks
│  └─ visual/               animated walkthroughs (themes + narration)
├─ platform/                shared libraries (no business logic of their own)
│  ├─ shared-kernel/        Money, identifiers, domain primitives
│  ├─ event-contracts/      versioned event schemas (Java records + JSON schema)
│  └─ outbox/               transactional outbox + relay
├─ services/
│  ├─ api-gateway/
│  ├─ catalog-service/
│  ├─ search-service/
│  ├─ inventory-service/
│  ├─ cart-service/
│  ├─ order-service/        checkout saga orchestrator
│  ├─ payment-service/
│  ├─ review-service/       Cassandra
│  └─ notification-service/
├─ deploy/
│  ├─ compose/              local stack: Postgres, Kafka, Redis, OpenSearch, Keycloak, observability
│  └─ k8s/                  Kustomize base + overlays
├─ gradle/libs.versions.toml
└─ .github/workflows/
```

Each service follows a **hexagonal** layout:
* `domain` holds pure Java with no framework imports;
* `application` holds use cases and ports;
* `adapter` holds the web, persistence, messaging and external clients.

ArchUnit tests enforce the dependency direction.

## 5. Milestones

| # | Milestone | Content | Done when |
|---|---|---|---|
| M0 | Plan and spec | `docs/PLAN.md`, `docs/SPEC.md` on `main` | Reviewed and pushed |
| M1 | Build skeleton | Gradle multi-project, version catalog, convention plugins, CI | `./gradlew build` is green in CI |
| M2 | Platform libraries | shared-kernel, event-contracts, outbox | Unit tests, outbox integration test |
| M3 | Catalog and search | product/category CRUD, outbox events, OpenSearch indexing and faceted search | API tests, indexing integration test |
| M4 | Cart and inventory | Redis cart, stock with reservations and expiry | Concurrency test: no overselling under parallel reservations |
| M5 | Orders and payments | checkout saga, idempotency keys, payment provider port | Saga tests: happy path, payment failure, inventory failure, timeout |
| M5b | Reviews and media | Cassandra-backed reviews, presigned image uploads | Review pagination test, verified-purchase consumer test |
| M6 | Gateway, identity, notifications | routing, JWT validation, rate limits, email notifications | End-to-end flow through the gateway |
| M7 | Operations | Compose stack, Kubernetes manifests, dashboards, alerts, runbooks | Stack starts locally; manifests pass `kubectl --dry-run` |
| M8 | Design docs | ADRs, per-service designs, data model, API and event catalogues, capacity model, failure modes | Docs cover every decision in the spec |
| M8b | Performance | Gatling simulations, AppCDS/AOT images, index and cache tuning | p99 budgets from SPEC §2.2 met in CI |
| M9 | Visual walkthroughs | animated architecture and flows, themes, narration toggle | Every scenario plays end to end with no console errors |

## 6. Design principles

* **Scale up, then out:** every tier has a documented vertical and horizontal growth path (SPEC §13.1).
* **Modular:** one bounded context per service, hexagonal inside, shared code limited to platform libraries (SPEC §13.2).
* **High performance:** virtual threads, layered caching, read models, batching and keyset pagination, with budgets enforced by load tests (SPEC §13.3).
* **Right store for the data:** polyglot persistence, one store per service (SPEC §14).
* **Proven patterns, named:** each one is mapped to its project in [jk-boot-up/java-design-patterns](https://github.com/jk-boot-up/java-design-patterns) (SPEC §15).

## 7. Working agreements

* **Branching:** all implementation goes to `system-design-e-commerce-draft-1`. Commits are small and describe one change each.
* **Quality gates on every commit:**
  * compile;
  * unit tests;
  * ArchUnit rules;
  * Testcontainers integration tests, wherever a container runtime is available (always in CI).
* **Contracts first:** event schemas and OpenAPI definitions change through versioned, backward-compatible additions. Breaking changes need a new version.
* **Every decision is recorded:** one ADR in `docs/adr/` per significant choice.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Distributed transactions across services | Saga with explicit compensations; outbox avoids dual writes; idempotent consumers |
| Overselling during flash sales | Conditional atomic stock decrement, short reservation expiry, queue-based admission for hot items |
| Duplicate orders or charges on retries | Client idempotency keys stored with the result; provider idempotency keys on payment calls |
| Cascading failures | Timeouts on every remote call, circuit breakers, bulkheads, load shedding at the gateway |
| Search index drift | Rebuildable index from catalog events plus a periodic full reindex job |
| Local environment without Docker | Pure-domain and in-memory adapter tests always run; container tests are tagged and run when Docker is present |
