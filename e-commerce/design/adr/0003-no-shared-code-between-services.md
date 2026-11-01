# 0003. No shared code between services

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

Shared libraries (domain primitives, outbox, event classes) are tempting, but they couple release cycles: upgrading the library forces every service to rebuild and redeploy, and a bug ships everywhere at once.

## Decision

Services share no code. Each service carries its own small copies of helpers such as `Money`, identifiers and the outbox. Services agree only on contracts: OpenAPI documents for APIs and JSON Schemas for events. The producer owns the schema; each consumer keeps its own copy and tests against it.

## Alternatives considered

| Option | Why not |
|---|---|
| Shared platform libraries | Less duplication, but lock-step upgrades and hidden coupling. |
| Generated clients from a shared contract repository | Still a build-time dependency between services. |

## Consequences

* Any service builds, tests and releases alone.
* A few hundred lines are duplicated per service; this is accepted and kept small.
* Contract tests catch drift between a producer's schema and a consumer's copy.
