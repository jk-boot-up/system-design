# 0009. UUIDv7 identifiers

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

Identifiers are created in many services without coordination and are used as primary keys and Kafka keys.

## Decision

Use UUIDv7 (time-ordered) for all generated identifiers. The leading 48 bits hold the creation time in milliseconds.

## Alternatives considered

| Option | Why not |
|---|---|
| Random UUIDv4 | Random inserts fragment B-tree indexes. |
| Database sequences | Need a round trip and leak business volume. |

## Consequences

* Index locality like a sequence, without coordination.
* The order id carries its creation time, which order-service uses for partition pruning.
