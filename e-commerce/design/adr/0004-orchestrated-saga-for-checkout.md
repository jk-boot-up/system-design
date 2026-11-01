# 0004. Orchestrated saga for checkout

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

Checkout spans cart, inventory and payment, each with its own database. A distributed two-phase commit is not available across these stores and would hurt availability.

## Decision

order-service orchestrates checkout as a saga (process manager): it calls inventory and payment synchronously, records each step on the order, and runs compensations (release stock, void or refund payment) when a step fails. A recovery job resumes interrupted sagas from the recorded step.

## Alternatives considered

| Option | Why not |
|---|---|
| Choreography (services react to each other's events) | No central place to see or change the flow; hard to reason about compensation for a flow this central. |
| Two-phase commit | Blocking, poorly supported across heterogeneous stores, reduces availability. |

## Consequences

* The checkout flow is readable in one class and easy to test.
* order-service depends at runtime on inventory and payment; mitigated with timeouts, circuit breakers and fast failure.
* The customer gets a definite answer in one request (201, 402, 409 or 503).
