# 14. Capacity and performance model

How much load each part of the system carries at the planned volume (1×) and at ten times it (10×), where the first limit
is, and how the load tests check the budgets. Inputs are the estimates in [SPEC §3](SPEC.md#3-capacity-estimates); scaling
steps are in [SPEC §13](SPEC.md#13-scalability-modularity-and-performance); sizes in Kubernetes are in the service bases
and [`deploy/k8s`](../deploy/k8s/README.md).

## 14.1 Load model

| Quantity | 1× average | 1× peak | 10× peak | Derivation |
|---|---|---|---|---|
| Reads at the edge | 290 rps | 25 k rps | 250 k rps | 1 M DAU × 25 reads / 86,400 s; ×8 daily peak; ×10 for an event |
| Reads reaching services | ~60 rps | ~5 k rps | ~50 k rps | about 80 % of reads (images, static pages, anonymous product pages) are served by the CDN and edge |
| Orders | 1.2 /s | 500 /s (flash sale) | 5 k /s | 100 k orders a day; flash-sale design point |
| Cart writes | 12 /s | 5 k /s | 50 k /s | ~10 per order |
| Kafka messages | 12 /s | 5 k /s | 50 k /s | ~10 events per order across topics |
| Searches | ~30 rps | ~2.5 k rps | ~25 k rps | about half of the reads that reach services |

## 14.2 Per-service budget

Replica ranges are the HPA bounds in each service's `k8s/hpa.yaml`. "Per pod at peak" is the 1× peak divided by the
maximum replicas: the rate one pod must sustain before the next step is needed.

| Service | Carries | 1× peak | HPA min–max | Per pod at 1× peak | First limit at 10× | Next step |
|---|---|---|---|---|---|---|
| api-gateway | every API call | ~5 k rps | 3–20 | 250 rps | pod count | raise `maxReplicas`; non-blocking (WebFlux), so CPU bound |
| catalog-service | product pages not cached at the edge | ~1.5 k rps | 2–10 | 150 rps | Redis cache hit rate | more cache, read replicas |
| search-service | searches | ~2.5 k rps | 2–10 | 250 rps | OpenSearch query throughput | more replicas of the index, then shards |
| cart-service | cart reads and writes | ~5 k rps | 2–10 | 500 rps | Redis operations per node | Redis Cluster |
| order-service | checkouts | 500 /s | 3–20 | 25 /s | the orders primary (14.3) | sharding by `customer_id` |
| inventory-service | reservations | 500 /s, all on hot SKUs in a sale | 3–20 | 25 /s | row contention on one SKU | admission control at the gateway; sub-bucket rows (SPEC §6.4) |
| payment-service | authorise, capture | 1 k /s | 2–10 | 100 /s | the provider's rate limit | negotiated limit; queue authorisations |
| review-service | review reads | ~500 rps | 2–10 | 50 rps | — | Cassandra nodes |
| recommendation-service | recommendation reads | ~1 k rps | 2–6 | 170 rps | — | more replicas |

## 14.3 Connection budget (PostgreSQL)

Each service with PostgreSQL connects through PgBouncer in transaction mode, so the number of pods does not multiply the
connections the database must hold.

| Database | Client pool per pod | Max pods | Client connections to PgBouncer | Server connections (PgBouncer → PostgreSQL) | Queries at 1× peak |
|---|---|---|---|---|---|
| orders | 30 (`DB_POOL_SIZE`) | 20 | 600 of 2,000 (`max_client_conn`) | 50 (`default_pool_size`) | ~2.5 k/s (500 orders × ~5) |
| inventory | 20 | 20 | 400 of 2,000 | 50 | ~1.5 k/s |
| payments | 20 | 10 | 200 of 2,000 | 50 | ~1 k/s |
| catalog | 20 | 10 | — (direct, no pooler) | 200 of `max_connections` 200 | mostly cache misses |

Catalog connects directly: at its maximum of 10 pods it would use every one of the database's 200 connections, leaving
none for migrations or operators. Add `pgbouncer-catalog` (as for the others) or lower its pool before raising its
`maxReplicas`.

At 1× one primary per database is comfortable. At 10× the orders primary would need ~25 k writes and reads a second;
order history moves to read replicas first, then orders are sharded by `customer_id` (Citus), which keeps a customer's
orders on one shard.

## 14.4 Partition budget (Kafka)

| Topic | Partitions | Max consumers per group | Messages at 1× peak | Per partition | At 10× |
|---|---|---|---|---|---|
| `order.events` | 12 | 12 | ~2.5 k/s | ~210/s | ~2.1 k/s per partition: raise to 48 ahead of need |
| `catalog.events` | 12 | 12 | low (staff edits, imports) | — | bulk imports are the peak |
| `inventory.events` | 12 | 12 | ~1 k/s in a sale | ~85/s | raise with `order.events` |
| `payment.events` | 12 | 12 | ~1 k/s | ~85/s | — |
| `review.events` | 6 | 6 | low | — | — |

KEDA scales consumers on lag, up to the partition count. Partitions can be added but never removed, and adding them moves
keys to new partitions (order per aggregate holds only for new events), so they are raised ahead of need in a quiet hour.

## 14.5 Storage growth (per year at 1×)

| Store | Growth | Plan |
|---|---|---|
| Orders (PostgreSQL) | ~73 GB | monthly partitions; old partitions detached to cold storage |
| Payments | ~30 GB | as orders |
| Search index | 50 GB with a replica | shard count fixed at creation; reindex behind an alias |
| Kafka | < 100 GB per broker (7 days) | retention per topic |
| Carts (Redis) | ~4 GB | 30-day expiry |

## 14.6 Load-test plan and results

The Gatling simulations in [`tests/performance`](../tests/performance/README.md) hold each journey to its p99 budget after
a warm-up and fail on a regression of more than 10 % against the environment's baseline.

| Journey | Budget (p99) | Local rate | Local p99 (4 GB Docker VM) | Target rate (production-like) |
|---|---|---|---|---|
| Product page | 150 ms | 8 visitors/s | 9 ms | 300 visitors/s |
| Search | 300 ms | 4 visitors/s | not measurable locally (OpenSearch OOM-killed or starved); warm single request ~20 ms | 150 visitors/s |
| Add to cart | 100 ms | 6 visitors/s | 54 ms | 200 visitors/s |
| Place order (whole saga) | 1.5 s | 1 order/s | 320 ms | 50 orders/s |

The local numbers show the budgets are reachable and catch regressions on one machine. They do not measure capacity: one
laptop runs every service, store and broker at once.

Still to run, on a production-like cluster with several load generators:

1. The target rates above (about 2,000 requests a second in steady state), to measure per-pod throughput and fill in the
   "per pod" column of 14.2 with measured numbers.
2. A flash sale: 500 orders a second on ten SKUs, checking admission control, inventory row contention and that no SKU
   is oversold.
3. A soak of several hours at the steady state, watching memory, connection counts and consumer lag.
4. Failure under load: kill a broker, a database primary and a gateway pod in turn; the SLO burn must stay under the page
   threshold.
