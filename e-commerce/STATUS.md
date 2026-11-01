# Status

Current progress of the e-commerce platform. Updated with every change of state; the detailed work lists are in
[design/DESIGN-PLAN.md](design/DESIGN-PLAN.md) and [design/IMPLEMENTATION-PLAN.md](design/IMPLEMENTATION-PLAN.md).

**Last updated:** 2026-11-01 · **Branch:** `system-design-e-commerce-draft-1` (fast-forwarded to `main`)

## At a glance

| Area | Progress | Done | Next |
|---|---|---|---|
| Specification and plans | ██████████ 100% | SPEC, PLAN, design plan, implementation plan, prerequisites | Keep in step with changes |
| System design documents | ███████░░░ 70% | HLD, DFD, deployment, database, sequence, flow, state, class/UML, API and event catalogues, 11 ADRs | System/module/service diagrams; security, observability, capacity, runbooks |
| Documentation tooling | ██████████ 100% | Diagrams → SVG (light + dark), Markdown → HTML, link checker, walkthrough engine with themes and narration | — |
| Services (11) | █████░░░░░ 45% | inventory, order, payment, cart complete | catalog-service, then search, review, gateway, notification, recommendation, analytics |
| Walkthroughs | ████░░░░░░ 40% | inventory, order, payment, cart | Each new service, system walkthrough |
| Local stack and Kubernetes overlays | ░░░░░░░░░░ 0% | inventory-service `k8s/` base | Compose stack, overlays |
| End-to-end and performance tests | ░░░░░░░░░░ 0% | — | After checkout works end to end |

## Services

| Service | Code | Tests | Contracts | Docker + k8s | CI | Docs | Walkthrough |
|---|---|---|---|---|---|---|---|
| inventory-service | ✅ | ✅ 19 passing (incl. PostgreSQL + Kafka containers) | ✅ | ✅ | ✅ | ✅ | ✅ |
| order-service | ✅ | ✅ 29 passing (incl. PostgreSQL + Kafka + HTTP) | ✅ | ✅ | ✅ | ✅ | ✅ |
| payment-service | ✅ | ✅ 17 passing (incl. PostgreSQL + Kafka) | ✅ | ✅ | ✅ | ✅ | ✅ |
| cart-service | ✅ | ✅ 16 passing (incl. Redis + Kafka) | ✅ | ✅ | ✅ | ✅ | ✅ |
| catalog-service | ⏳ | | | | | | |
| search-service | ⏳ | | | | | | |
| review-service | ⏳ | | | | | | |
| api-gateway + NGINX | ⏳ | | | | | | |
| notification-service (Go) | ⏳ | | | | | | |
| recommendation-service (Python) | ⏳ | | | | | | |
| analytics (ClickHouse) | ⏳ | | | | | | |

## Recently completed

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

* catalog-service: products and categories (PostgreSQL + JSONB), ETag concurrency, Redis cache-aside, presigned MinIO uploads, ProductChanged outbox.
* System, module and service diagrams (design document 11).

## Risks and notes

| Item | Note |
|---|---|
| Maven Central rate limits (HTTP 429) during builds | Builds retry with back-off; CI caches dependencies |
| Whole local stack needs about 16 GB of memory | Each service's README shows how to start only the infrastructure it needs |
