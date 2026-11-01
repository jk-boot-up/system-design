# 0007. Java first, Go and Python where they fit

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

Most services hold rich transactional domain logic. A few are small I/O-bound workers or data-oriented services.

## Decision

Java 25 with Spring Boot 4 is the main stack. notification-service is written in Go (small, fast-starting event router and email worker). recommendation-service is written in Python with FastAPI (data-oriented, room to grow into ML models). Every service, in any language, follows the same contracts: HTTP + JSON with problem details, the event envelope, OpenTelemetry, Prometheus metrics, health endpoints, container images.

## Alternatives considered

| Option | Why not |
|---|---|
| Java everywhere | Simpler hiring and tooling, but heavier for tiny workers and awkward for data science. |
| Many languages freely | Operational and skills sprawl. |

## Consequences

* Each language is justified by a concrete benefit.
* Polyglot CI: Gradle, Go and Python pipelines, each scoped to its service.
