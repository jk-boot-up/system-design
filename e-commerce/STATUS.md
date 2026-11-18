# Status

Current progress of the e-commerce platform. Updated with every change of state; the detailed work lists are in
[design/DESIGN-PLAN.md](design/DESIGN-PLAN.md) and [design/IMPLEMENTATION-PLAN.md](design/IMPLEMENTATION-PLAN.md).

**Last updated:** 2026-11-18 · **Branch:** `system-design-e-commerce-draft-1` (fast-forwarded to `main`)

## At a glance

| Area | Progress | Done | Next |
|---|---|---|---|
| Specification and plans | ██████████ 100% | SPEC, PLAN, design plan, implementation plan, prerequisites | Keep in step with changes |
| System design documents | ███████░░░ 70% | HLD, DFD, deployment, database, sequence, flow, state, class/UML, API and event catalogues, 11 ADRs | System/module/service diagrams; security, observability, capacity, runbooks |
| Documentation tooling | ██████████ 100% | Diagrams → SVG (light + dark), Markdown → HTML, link checker, walkthrough engine with themes and narration | — |
| Services (11) | ████████░░ 82% | inventory, order, payment, cart, catalog, search, review, gateway complete | notification, recommendation, analytics |
| Walkthroughs | ████████░░ 80% | inventory, order, payment, cart, catalog, search, review, gateway | Each new service, system walkthrough |
| Local stack and Kubernetes overlays | ░░░░░░░░░░ 0% | inventory-service `k8s/` base | Compose stack, overlays |
| End-to-end and performance tests | ░░░░░░░░░░ 0% | — | After checkout works end to end |

## Services

| Service | Code | Tests | Contracts | Docker + k8s | CI | Docs | Walkthrough |
|---|---|---|---|---|---|---|---|
| inventory-service | ✅ | ✅ 19 passing (incl. PostgreSQL + Kafka containers) | ✅ | ✅ | ✅ | ✅ | ✅ |
| order-service | ✅ | ✅ 29 passing (incl. PostgreSQL + Kafka + HTTP) | ✅ | ✅ | ✅ | ✅ | ✅ |
| payment-service | ✅ | ✅ 17 passing (incl. PostgreSQL + Kafka) | ✅ | ✅ | ✅ | ✅ | ✅ |
| cart-service | ✅ | ✅ 16 passing (incl. Redis + Kafka) | ✅ | ✅ | ✅ | ✅ | ✅ |
| catalog-service | ✅ | ✅ 22 passing (incl. PostgreSQL + Redis + Kafka + S3) | ✅ | ✅ | ✅ | ✅ | ✅ |
| search-service | ✅ | ✅ 13 passing (incl. OpenSearch + Kafka) | ✅ | ✅ | ✅ | ✅ | ✅ |
| review-service | ✅ | ✅ 15 passing (incl. Cassandra + Kafka) | ✅ | ✅ | ✅ | ✅ | ✅ |
| api-gateway + NGINX | ✅ | ✅ 15 passing (incl. Redis + stub services + NGINX image) | — (routes in README) | ✅ | ✅ | ✅ | ✅ |
| notification-service (Go) | ⏳ | | | | | | |
| recommendation-service (Python) | ⏳ | | | | | | |
| analytics (ClickHouse) | ⏳ | | | | | | |

## Recently completed

* api-gateway + NGINX edge: routes for every public API (reviews and recommendations before the catalogue route), JWT checks with
  issuer and audience, request ids end to end, token buckets shared through one Redis Lua script that fall back to a stricter local
  bucket when Redis does not answer in 50 ms (proved with Redis unreachable), per-route circuit breakers with 503 problem
  fallbacks; NGINX with TLS, HTTP/2, micro-caching with a cache lock (proved MISS → HIT with one upstream call) and `/internal` blocked.

* review-service: Cassandra tables per query with month buckets, one review per customer through a lightweight transaction,
  counters incremented only by the caller that flips `counted` (10 concurrent posts by one customer count once; a crash before any
  step is finished by the retry), verified badge from `OrderConfirmed`.

* search-service: OpenSearch read model keyed by SKU with two version guards in one scripted upsert (catalogue and stock), so
  late, repeated and reordered events are no-ops (proved with stock-before-product, v2-before-v1 and stock 9-before-8); multi-select
  brand facets, cursor pages with `search_after`, retry-forever indexing and a dead-letter topic for unreadable events.
  inventory-service now adds `stockVersion` to `StockChanged`.

* catalog-service: products and categories (PostgreSQL + JSONB), conditional edits with ETag / If-Match (428 without, 412 when
  stale, proved against a write that commits between the check and the update), Redis cache-aside evicted after commit, presigned
  uploads straight to S3 storage with confirm-only-if-present, ProductChanged outbox carrying the catalogue version.

* cart-service: Redis carts with atomic line + expiry writes, guest merge on sign-in, prices re-read from the catalogue with a
  degraded "pricing pending" mode, checkout snapshot that refuses stale prices, cart emptied once per confirmed order.

* payment-service: one payment per order, provider calls outside transactions, void requests that always win, reconciler that
  voids late provider successes (proved with a real late-success race), append-only audit trail.

* order-service: checkout saga with every compensation path, idempotency keys, crash recovery, monthly partitions with id-based
  pruning, JWT-secured API, circuit breakers and retries; container tests caught and fixed a millisecond/microsecond mismatch
  between order ids and partition keys.

* inventory-service: no-overselling reservations, expiry job, outbox relay, cancellation safety net, REST API with problem details;
  tests prove 50 concurrent orders for 10 units reserve exactly 10.
* Walkthrough engine: animated diagrams, light / medium / dark themes, narration on/off with a female voice, automated step check.
* Repository structure: `e-commerce/` self-contained, with `design/` (system), `services/` (independent projects), `deploy/`,
  `tests/` and `tools/`.

## In progress

* notification-service (Go): Kafka → RabbitMQ quorum priority queue → SMTP, TTL retries, dead letters, Redis dedupe.
* System, module and service diagrams (design document 11).

## Risks and notes

| Item | Note |
|---|---|
| Maven Central rate limits (HTTP 429) during builds | Builds retry with back-off; CI caches dependencies |
| Whole local stack needs about 16 GB of memory | Each service's README shows how to start only the infrastructure it needs |
