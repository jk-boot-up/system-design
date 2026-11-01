# 0001. Record architecture decisions

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

Design choices for a system this size are revisited for years. Without a record, the reasons behind a choice are lost and the same debates recur.

## Decision

Every significant decision is written as a short Architecture Decision Record in this folder: context, decision, alternatives and consequences. Records are immutable; a changed decision gets a new record that supersedes the old one.

## Alternatives considered

| Option | Why not |
|---|---|
| Wiki pages | Drift away from the code and are not reviewed with changes. |
| Comments in code | Too scattered for cross-cutting decisions. |

## Consequences

* Decisions are reviewed in pull requests like code.
* New contributors can read why the system looks the way it does.
