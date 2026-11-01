# 0002. Microservices with a database per service

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

The store has clearly separate business capabilities (catalogue, cart, ordering, stock, payment, reviews) with very different load profiles: browsing is read-heavy and spiky, checkout is write-heavy and must be correct, reviews are append-heavy.

## Decision

Split the system into services along bounded contexts. Each service owns its data store exclusively; other services use its API or its events, never its tables.

## Alternatives considered

| Option | Why not |
|---|---|
| Modular monolith | Simpler to run, but one deployment unit and one database limit independent scaling and technology choice. A good starting point for a small team; this reference targets production scale. |
| Shared database | Couples schemas and releases; one heavy query can starve every capability. |

## Consequences

* Each service scales, deploys and fails independently.
* Cross-service consistency needs sagas and events (ADR 0004, 0005).
* More moving parts to operate: mitigated by uniform service layout, observability and automation.
