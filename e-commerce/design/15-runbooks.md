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
* **Confirm:** read the parked events with their headers:
  ```bash
  kubectl -n e-commerce-platform exec -it kafka-combined-0 -- bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic order.events.dlq --from-beginning --property print.headers=true --property print.key=true
  ```
  The `traceparent` header leads to the failed attempt's trace; the consumer's log has the exception.
* **Act:**
  1. Fix the cause (a bug, a missing schema version, a dependency that was down).
  2. Deploy the fix.
  3. Replay: copy the parked events back onto the main topic with the same key, then empty the DLQ by moving the
     consumer's offset. Consumers are idempotent (processed-events ledger), so replaying an event that did get through is
     harmless.
* **Over when:** the DLQ depth is zero and the missing effects are visible.
* **Note:** there is no replay tool yet; a small one (read DLQ, write main topic, same key and headers) belongs in
  `deploy/tools`.

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
  * If the cluster cannot elect a primary (two of three instances lost), restore from backup. Backups are not yet
    configured ([12 §12.8](12-security-design.md#128-gaps-designed-not-yet-built)); until they are, this is data loss.
* **Over when:** the cluster reports healthy with three instances and the service's error rate is normal.

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

* **Signals:** catalog-service and its database CPU spike together, often right after Redis restarts or at the same
  moment every few minutes; product-latency SLO burns.
* **Confirm:** Redis hit rate drops sharply; many identical `select … from product` queries at once.
* **Why it can happen here:** product entries expire after exactly 10 minutes and a miss reads PostgreSQL directly, with
  no jitter and no single-flight. Entries written together (after a restart, or by an import) expire together.
* **Act now:** scale catalog-service out; the edge and gateway caches absorb anonymous product pages meanwhile. If Redis
  itself is down, catalog keeps working from PostgreSQL (Redis errors count as misses), so restore Redis.
* **Fix for good:** add jitter to the expiry (for example 10 minutes ± 10 %) and collapse concurrent misses for the same
  product into one query; then this runbook only covers Redis loss.
* **Over when:** the hit rate is back above 90 % and database CPU is normal.
