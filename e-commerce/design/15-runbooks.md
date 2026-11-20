# 15. Runbooks

What to do when something goes wrong in production. Each runbook says how the problem shows itself, how to confirm it,
what to do, and how to know it is over. The per-alert runbook that alerts link to is
[`deploy/observability/RUNBOOK.md`](../deploy/observability/RUNBOOK.md); this document covers the incidents behind those
alerts, including the ones that need several steps or a decision.

Common first steps for every incident:

1. Open *SLOs and error budgets* in Grafana: which journeys are burning, and since when.
2. Check for a deploy in the last hour (`kubectl -n e-commerce-shop rollout history deploy/<service>`). If one lines up,
   roll it back first and investigate afterwards (`kubectl rollout undo`).
3. Take a failing request's `traceId` from a problem response or the logs and open its trace in Tempo.

## 15.1 Outbox backlog

* **Signals:** `OutboxBacklogStale` (page); the *Transactional outboxes* panel shows a service's oldest unpublished event
  growing; downstream read models are stale (search shows old prices, carts are not cleared after orders).
* **Confirm:** the service's log has `Outbox relay pass failed, retrying in … ms` with the Kafka error;
  `outbox_unpublished` is rising.
* **Act:**
  * Kafka unavailable or rejecting writes → 15.6.
  * The relay is not running (the pod is up but nothing is logged) → restart the deployment; the relay starts with the
    application.
  * One event too large or invalid for the topic → the relay keeps failing on the same row. Find it (`select id, type,
    length(payload::text) from outbox where published_at is null order by id limit 5`), fix the cause in code, and do not
    delete the row by hand unless the event is truly unwanted (its consumers would never learn of it).
* **Over when:** `outbox_oldest_unpublished_age_seconds` is back under a second. The relay publishes what Kafka accepts and
  backs off up to 30 s, so it catches up by itself once Kafka is healthy.

## 15.2 Dead-letter queue replay

* **Signals:** `DeadLetterQueueNotEmpty` (ticket); a consumer's effect is missing (a cart not cleared, a review not
  allowed, an email not sent).
* **Confirm:** list the parked events with the group that parked them and the reason
  ([dlq-replay](../deploy/tools/dlq-replay/README.md)):
  ```bash
  dlq_replay.py list --dlq order.events.dlq
  ```
  The `traceparent` header leads to the failed attempt's trace; the consumer's log has the exception.
* **Act:**
  1. Fix the cause (a bug, a missing schema version, a dependency that was down).
  2. Deploy the fix.
  3. Replay: `dlq_replay.py replay --dlq order.events.dlq --dry-run`, check the list, then run it without `--dry-run`
     (narrow it with `--group` or `--reason` if only some events are fixed). It copies each event back onto the main
     topic with its key and headers, then deletes the replayed events from the DLQ, so the alert resolves. Consumers are
     idempotent (processed-events ledger), so replaying an event that did get through is harmless.
* **Over when:** the DLQ depth is zero and the missing effects are visible.

## 15.3 Stuck checkout sagas

* **Signals:** orders stay `PENDING` or `CANCELLING`; their order pages show the order as pending; checkout latency
  SLO burns; `PaymentOutcomeUnknown` may fire.
* **Confirm:**
  ```sql
  select saga_step, status, count(*), min(updated_at)
  from orders where status in ('PENDING', 'CANCELLING') group by 1, 2;
  ```
  Saga recovery runs every 15 s on one replica (ShedLock `order.saga-recovery`) and picks up orders untouched for 30 s.
* **Act:**
  * Recovery logs `Saga recovery looked at N in-flight orders` but the count does not fall → the step it retries is
    failing: look at inventory-service or payment-service (their circuit breakers, their databases, the payment
    provider).
  * Recovery does not log at all → check the lock: a pod killed while holding it releases it after at most 5 minutes
    (`lockAtMostFor`); if it is older, delete the row from `shedlock`.
  * Never mark an order `CONFIRMED` or `CANCELLED` by hand: the payment and stock steps would not match. Let recovery
    finish, or fix the dependency it waits for.
* **Over when:** no order is `PENDING` for longer than a minute.

## 15.4 Hot SKU (flash sale)

* **Signals:** many `429` responses on add-to-cart and checkout for one SKU; inventory latency rises; the checkout latency
  SLO may burn.
* **Confirm:** the gateway's admission control (`hot-sku`: 200 requests a second per SKU, burst 400) is rejecting;
  inventory's reservation latency is high for that SKU only.
* **Act:**
  * This is the design working: admission control keeps a sale from taking down checkout for everyone else. Leave it.
  * If the sale was announced, the SKU's stock is small and the queue is long, lower the per-SKU rate so customers fail
    fast instead of waiting.
  * If inventory still struggles, split the SKU's stock into sub-bucket rows (SPEC §6.4) before the next sale.
* **Over when:** the sale ends or stock reaches zero (`OUT_OF_STOCK` is an answer, not an error).

## 15.5 Database failover (PostgreSQL)

* **Signals:** a burst of 5xx from one service; its logs show connection errors; CloudNativePG reports a failover.
* **Confirm:** `kubectl -n e-commerce-platform get cluster <name>-db` shows a new primary.
* **Act:**
  * CloudNativePG promotes a replica on its own (seconds). PgBouncer reconnects to the new primary; services retry.
    Usually nothing else is needed.
  * Sagas interrupted by the failover are finished by recovery (15.3); outbox rows are published once writes succeed
    again (15.1).
  * If the cluster cannot elect a primary (two of three instances lost), or data was destroyed (a bad migration, a
    deleted table), restore from backup, below.
* **Over when:** the cluster reports healthy with three instances and the service's error rate is normal.

**Restoring from backup.** Every cluster archives its write-ahead log continuously and takes a base backup nightly
(`base/platform/backups.yaml`, kept 30 days), so it can be restored to any moment in that window, to within the last
archived WAL file (seconds).

1. Check that backups are current: `kubectl -n e-commerce-platform get backups` (the latest `<name>-nightly` is
   `completed`) and `kubectl cnpg status <name>-db` (WAL archiving `OK`, last archived WAL recent).
2. Choose the target: the latest state after a lost cluster, or a moment just before the damage (from the audit log or
   the failing deploy's time), in UTC.
3. Scale the owning service to zero (`kubectl -n e-commerce-shop scale deploy/<service> --replicas=0`) so nothing writes
   to a half-restored database. Its outbox keeps the events it had not yet published; consumers are idempotent.
4. Create a new cluster from the backup, alongside the old one:
   ```yaml
   apiVersion: postgresql.cnpg.io/v1
   kind: Cluster
   metadata: { name: orders-db-restore, namespace: e-commerce-platform }
   spec:
     instances: 3                                   # and the same storage, resources and affinity as orders-db
     bootstrap:
       recovery:
         source: orders-db
         recoveryTarget: { targetTime: "2026-11-20 09:41:00+00" }   # omit for the latest state
     externalClusters:
       - name: orders-db
         plugin: { name: barman-cloud.cloudnative-pg.io, parameters: { barmanObjectName: orders-db-backup, serverName: orders-db } }
     plugins:                                       # its own archive, so the old cluster's backups stay intact
       - { name: barman-cloud.cloudnative-pg.io, isWALArchiver: true, parameters: { barmanObjectName: orders-db-restore-backup } }
   ```
   (`orders-db-restore-backup` is an `ObjectStore` like `orders-db-backup` with the prefix `orders-db-restore`.)
5. Check the data (row counts, the latest orders), then point the pooler at it: set `spec.cluster.name` of
   `pgbouncer-orders` to `orders-db-restore`. Scale the service back up.
6. Orders created after the target time are gone from the database but still in `order.events`; reconcile them from
   the topic (and payments from the provider) before telling customers anything. Saga recovery (15.3) finishes the
   in-flight ones.
7. Keep the old cluster until the restored one is confirmed, then delete it and rename in Git on the next release.

## 15.6 Kafka broker loss

* **Signals:** `OutboxBacklogStale` on several services at once; consumer lag rising everywhere; under-replicated
  partitions.
* **Confirm:** `kubectl -n e-commerce-platform get kafka,kafkanodepool`; the Kafka exporter's
  `kafka_topic_partition_under_replicated_partition`.
* **Act:**
  * One broker of three lost: topics have replication factor 3 and `min.insync.replicas` 2, so producers keep writing
    and consumers keep reading. Strimzi recreates the pod; watch it rejoin and catch up.
  * Two brokers lost: producers fail (`NotEnoughReplicas`). Checkouts still complete (the outbox holds events), but
    nothing downstream sees them. Restore brokers first; do not lower `min.insync.replicas`, which would risk losing
    acknowledged events.
  * Disk full on a broker: find the topic growing fastest (`TopicGrowingFasterThanItsEvents`), stop its producer if it
    is looping, then lower that topic's retention temporarily.
* **Over when:** no under-replicated partitions, outbox backlogs drained, lag back to normal.

## 15.7 Cache stampede

* **Signals:** catalog-service and its database CPU spike together, usually right after Redis restarts or is flushed;
  product-latency SLO burns.
* **Confirm:** Redis hit rate drops sharply; many identical `select … from product` queries at once.
* **Why it can still happen here:** expiry is spread (10 minutes ± 10 %) and concurrent misses for one product share one
  query per pod, so expiry alone no longer causes a stampede. Losing Redis does: every product misses at once, and each
  pod reads each hot product once.
* **Act now:** scale catalog-service out; the edge and gateway caches absorb anonymous product pages meanwhile. If Redis
  itself is down, catalog keeps working from PostgreSQL (Redis errors count as misses), so restore Redis.
* **Already in place:** jittered expiry and per-pod single-flight (`RedisProductCache`, `SingleFlight` in catalog-service);
  a read that overlaps a committed edit does not write the old version back into the cache.
* **Over when:** the hit rate is back above 90 % and database CPU is normal.

## 15.8 Identity provider (Keycloak) unavailable

* **Signals:** sign-in fails on the storefront; checkout or add-to-cart start failing a few minutes later with
  `503`/`PRICING_PENDING`; logs of order-service and cart-service show `Service token renewal failed`.
* **Confirm:** `kubectl -n e-commerce-platform get pods -l app=keycloak`; the token endpoint
  (`/realms/shop/protocol/openid-connect/token`) answers 5xx or times out.
* **What keeps working, and for how long:** customers already signed in keep browsing and buying until their access
  token expires (5 minutes; the storefront cannot refresh it). Service calls keep working on the cached service tokens
  for about 5 minutes: each is renewed after 10 of its 15 minutes, and a failed renewal is retried every 5 seconds while
  the current token stays valid ([ADR 0014](adr/0014-service-identity-with-client-credentials-tokens.md)). After that,
  checkout answers `503` and carts show prices as pending; nothing is lost or charged twice.
* **Act now:** restart the failing Keycloak pods; check `keycloak-db` (a failover there stops Keycloak too, see 15.5).
  If a client secret was rotated in the vault, make sure both the realm import and the service picked up the same value
  (both read one key); a mismatch shows as `invalid_client` in the service's log.
* **Over when:** the token endpoint answers, the renewal warnings stop and the checkout SLO stops burning.
