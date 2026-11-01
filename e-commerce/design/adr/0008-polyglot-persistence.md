# 0008. Polyglot persistence

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

The services' data has very different shapes and access patterns: transactional records, counters under contention, key-value carts, full-text search, append-heavy reviews, analytics over all orders, and binary images.

## Decision

Pick the store per service: PostgreSQL (catalog, inventory, orders, payments), Redis (cart, cache, rate limits, recommendation sets, dedupe), OpenSearch (search), Cassandra (reviews), ClickHouse (analytics), MinIO (images). All are open source and run locally in Docker.

## Alternatives considered

| Option | Why not |
|---|---|
| PostgreSQL for everything | Simpler operations; would work at small scale but loses on search relevance, write scaling for reviews and analytics speed. |

## Consequences

* Each store is used where it is strongest.
* More technologies to operate; each runs behind an operator in Kubernetes and as one container locally.
