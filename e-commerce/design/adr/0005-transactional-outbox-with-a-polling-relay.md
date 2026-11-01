# 0005. Transactional outbox with a polling relay

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

A service must change its state and publish an event. Writing to the database and to Kafka separately can lose or invent events when one of the two fails.

## Decision

Write the event to an `outbox` table in the same transaction as the state change. A relay in every replica polls unpublished rows with `FOR UPDATE SKIP LOCKED`, publishes them to Kafka with an idempotent producer, and marks them published. Consumers deduplicate by event id.

## Alternatives considered

| Option | Why not |
|---|---|
| Change data capture with Debezium | No polling, but adds Kafka Connect to operate and couples event shape to table shape. A good upgrade path when outbox volume grows. |
| Publish after commit | Loses events when the process dies between commit and publish. |

## Consequences

* Events are published if and only if the transaction commits (at least once).
* Publication latency is about the polling interval (100 ms).
* Outbox rows are deleted after 7 days by a scheduled job.
