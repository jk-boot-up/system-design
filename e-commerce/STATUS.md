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
| Services (11) | ██████████ 100% | all eleven complete | — |
| Walkthroughs | █████████░ 92% | every service | System walkthrough |
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
| notification-service (Go) | ✅ | ✅ 13 passing (incl. Kafka + RabbitMQ + Redis + Mailpit) | ✅ (consumed) | ✅ | ✅ | ✅ | ✅ |
| recommendation-service (Python) | ✅ | ✅ 16 passing (incl. Redis + Kafka) | ✅ (consumed) | ✅ | ✅ | ✅ | ✅ |
| analytics (ClickHouse) | ✅ | ✅ 1 end-to-end (ClickHouse image + Kafka) + Grafana check | ✅ (consumed) | ✅ | ✅ | ✅ | ✅ |

## Recently completed

* Local stack (`deploy/compose`): every service and store, a Keycloak realm with test users, topics from the event catalogue, profiles
  so it runs in 4 GB, and `smoke.sh` buying through the NGINX edge (verified, plus async, search, reviews and analytics profiles).
  Bringing it up found and fixed: stale upstream addresses in the gateway after a container is replaced, browser-reachable upload
  URLs in catalog, an optional JWK set URI for the gateway, bash health checks, OpenSearch memory.

* analytics (ClickHouse 26.8): Kafka engine table with a named collection and error streaming, facts deduplicated by event id,
  rollups as refreshable views over `FINAL` so a re-delivered event never doubles revenue (the design's insert-triggered rollup would
  have), unreadable messages kept with reasons, hashed customer ids, read-only Grafana user, provisioned Grafana 13.2 dashboard
  whose every query is tested.

* recommendation-service (Python 3.14, FastAPI): co-purchase counts in Redis sorted sets, with the seen check and every increment
  in one Lua script so an order counts exactly once (three deliveries and 20 concurrent duplicates count once) and is never marked
  seen without being counted; top-200 trimming; 503 problem when Redis is down; offsets committed only after counting.

* notification-service (Go 1.27): router commits Kafka offsets only after RabbitMQ confirms the job; worker with a Redis sent log
  and claim (three copies of one event → one email, 20 concurrent duplicates → one send), delayed retries through TTL quorum queues
  (30 s, 2 min, 10 min), 5xx and fifth failures to `email.dead`, unreadable events to `order.events.dlq`; distroless image,
  router and worker deployed separately, workers scaled by queue depth.

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

* Platform: k8s overlays (P2), end-to-end tests (P3), Gatling (P4), observability (P5); design documents S13–S17; system walkthrough (S19).
* System, module and service diagrams (design document 11).

## Risks and notes

| Item | Note |
|---|---|
| Maven Central rate limits (HTTP 429) during builds | Builds retry with back-off; CI caches dependencies |
| Whole local stack needs about 16 GB of memory | Each service's README shows how to start only the infrastructure it needs |
