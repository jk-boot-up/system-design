# 0010. Plain JDBC (JdbcClient) instead of JPA

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

The hot paths depend on exact SQL: conditional stock updates, `SKIP LOCKED` outbox polling, partitioned tables and keyset pagination.

## Decision

Java services use Spring's `JdbcClient` with hand-written SQL in persistence adapters (Data Mapper pattern). Domain classes stay free of persistence annotations.

## Alternatives considered

| Option | Why not |
|---|---|
| JPA / Hibernate | Productive for CRUD but hides the SQL that matters here and invites N+1 queries. |

## Consequences

* Every query is visible, reviewable and tunable.
* A little more mapping code in each adapter.
