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
| Services (11) | ██░░░░░░░░ 15% | inventory-service complete | order-service (in progress), then payment, cart, catalog, search, review, gateway, notification, recommendation, analytics |
| Walkthroughs | █░░░░░░░░░ 10% | inventory-service (8 scenarios, 36 steps) | order-service, system walkthrough |
| Local stack and Kubernetes overlays | ░░░░░░░░░░ 0% | inventory-service `k8s/` base | Compose stack, overlays |
| End-to-end and performance tests | ░░░░░░░░░░ 0% | — | After checkout works end to end |

## Services

| Service | Code | Tests | Contracts | Docker + k8s | CI | Docs | Walkthrough |
|---|---|---|---|---|---|---|---|
| inventory-service | ✅ | ✅ 19 passing (incl. PostgreSQL + Kafka containers) | ✅ | ✅ | ✅ | ✅ | ✅ |
| order-service | 🔄 domain, application, persistence | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| payment-service | ⏳ | | | | | | |
| cart-service | ⏳ | | | | | | |
| catalog-service | ⏳ | | | | | | |
| search-service | ⏳ | | | | | | |
| review-service | ⏳ | | | | | | |
| api-gateway + NGINX | ⏳ | | | | | | |
| notification-service (Go) | ⏳ | | | | | | |
| recommendation-service (Python) | ⏳ | | | | | | |
| analytics (ClickHouse) | ⏳ | | | | | | |

## Recently completed

* inventory-service: no-overselling reservations, expiry job, outbox relay, cancellation safety net, REST API with problem details;
  tests prove 50 concurrent orders for 10 units reserve exactly 10.
* Walkthrough engine: animated diagrams, light / medium / dark themes, narration on/off with a female voice, automated step check.
* Repository structure: `e-commerce/` self-contained, with `design/` (system), `services/` (independent projects), `deploy/`,
  `tests/` and `tools/`.

## In progress

* order-service: checkout saga with compensations and recovery are written; HTTP clients with circuit breakers, REST API with JWT,
  scheduled jobs, tests, documents and walkthrough are next.
* System, module and service diagrams (design document 11).

## Risks and notes

| Item | Note |
|---|---|
| Maven Central rate limits (HTTP 429) during builds | Builds retry with back-off; CI caches dependencies |
| Whole local stack needs about 16 GB of memory | Each service's README shows how to start only the infrastructure it needs |
