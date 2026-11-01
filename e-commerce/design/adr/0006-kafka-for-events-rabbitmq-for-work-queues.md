# 0006. Kafka for events, RabbitMQ for work queues

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

The system needs two kinds of messaging: durable, replayable domain events read by many consumers, and individual jobs (send this email) that need per-message acknowledgement, priorities and delayed retries.

## Decision

Use Apache Kafka for domain events (ordered per key, retained for 7 days, many consumer groups). Use RabbitMQ for the email job queue: quorum queues, priorities, retries via TTL and dead-letter exchanges.

## Alternatives considered

| Option | Why not |
|---|---|
| Kafka only | Priorities and per-message delays would have to be built by hand. |
| RabbitMQ only | No log retention or replay; weaker for many independent readers of the same events. |

## Consequences

* Each tool is used for what it does best.
* Two brokers to operate; both run as operators on Kubernetes and as single containers locally.
