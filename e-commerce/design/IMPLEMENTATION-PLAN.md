# Implementation plan

Every implementation work item for the e-commerce platform, in the order it is built, with its definition of done and status. The
design work is in [DESIGN-PLAN.md](DESIGN-PLAN.md); the overview and milestones are in [PLAN.md](PLAN.md).

Status: ✅ done · 🔄 in progress · ⏳ planned

## 1. Definition of done (every service)

A service is done when all of these hold:

| # | Criterion |
|---|---|
| D1 | Standalone project in `services/<service>/` with its own build (Gradle wrapper and version catalog, Go module or Python project); builds alone with one command |
| D2 | Hexagonal structure: `domain` (no framework code), `application` (use cases and ports), `adapter.*`; enforced by architecture tests (ArchUnit in Java, import rules in Go and Python) |
| D3 | Unit tests for the domain and the use cases (with in-memory test doubles) |
| D4 | Container integration tests against the real store and brokers (Testcontainers), tagged so they run when Docker is available |
| D5 | Contracts: `contracts/openapi.yaml`, JSON Schemas for published events, the consumer's own copies of consumed events |
| D6 | Production readiness: health probes, Prometheus metrics, structured logs, graceful shutdown, timeouts and circuit breakers on every remote call, idempotent commands and consumers |
| D7 | `Dockerfile` (non-root, layered or distroless, small) and `k8s/` base (Deployment, Service, HPA, PDB, NetworkPolicy, ConfigMap) |
| D8 | CI workflow `.github/workflows/e-commerce-<service>.yml`, triggered only by changes to that service |
| D9 | Documents: README, PREREQUISITES, `design/README.md` (low-level design with diagrams) and `design/walkthrough/` (animation), with HTML counterparts |
| D10 | Wired into `deploy/compose` and `deploy/k8s/overlays` |

## 2. Services, in build order

The order follows the checkout path first, because it carries the hardest correctness requirements. Read paths and asynchronous
consumers come after it.

| # | Service | Main work | Proves | Status |
|---|---|---|---|---|
| 1 | **inventory-service** (Java) | Conditional stock updates, all-or-nothing reservations, expiry job, outbox relay, `OrderCancelled` safety-net consumer | 50 concurrent orders for 10 units → exactly 10 reserved | ✅ D1–D9 (D10 with the compose stack) |
| 2 | **order-service** (Java) | Order aggregate and state machine, checkout saga with compensations, idempotency keys, recovery job, monthly partitions, keyset history, JWT security | Every saga path: happy, out of stock, declined, unknown, capture failure, crash recovery, customer cancellation | ✅ D1–D9 |
| 3 | **payment-service** (Java) | Payment aggregate, provider port with a simulated provider (approve / decline / timeout tokens), idempotent authorise / capture / void / refund, reconciler for unknown outcomes, attempt audit log | A late provider success after a timeout is voided; no double charge on retries | ✅ D1–D9 |
| 4 | **cart-service** (Java) | Redis hash carts for guests and customers, merge on sign-in, re-pricing through catalog with a fallback, checkout snapshot, `OrderConfirmed` consumer that clears the cart | Degraded pricing when catalog is down; idempotent clearing | ⏳ |
| 5 | **catalog-service** (Java) | Products and categories (PostgreSQL + JSONB), optimistic locking with ETags, Redis cache-aside, presigned image uploads to MinIO, `ProductChanged` outbox | Cache invalidation after commit; stale writes rejected with 412 | ⏳ |
| 6 | **search-service** (Java) | OpenSearch index and alias, consumers for `ProductChanged` and `StockChanged` with external versioning, faceted search with `search_after` cursors | Out-of-order events never overwrite newer data | ⏳ |
| 7 | **review-service** (Java) | Cassandra tables per query, LWT guard (one review per customer per product), counters, verified purchases from `OrderConfirmed` | Second review rejected; verified badge only after a purchase | ⏳ |
| 8 | **api-gateway** (Java) + **NGINX edge** | Spring Cloud Gateway routes, JWT validation, Redis token-bucket rate limits, request ids, circuit-breaker fallbacks; NGINX TLS, compression, micro-caching and static documentation | 429 with `Retry-After` past the limit; 503 fallback when a service is down | ⏳ |
| 9 | **notification-service** (Go) | Kafka router → RabbitMQ quorum priority queue → email worker (SMTP to Mailpit), retries via TTL and dead-letter exchanges, Redis dedupe | Each email sent once even with duplicate events; poison jobs dead-lettered | ⏳ |
| 10 | **recommendation-service** (Python) | FastAPI, Kafka consumer for `OrderConfirmed`, Redis sorted-set co-purchase counts, top-N endpoint | Pairs counted once per order event | ⏳ |
| 11 | **analytics** (ClickHouse) | Kafka engine tables, materialised views, rollups, Grafana dashboard | Revenue per minute available within a minute of an order | ⏳ |

## 3. Platform and system-level work

| # | Item | Contents | Status |
|---|---|---|---|
| P1 | Local stack `deploy/compose` | Compose project `e-commerce`: PostgreSQL (one database per service), Kafka (KRaft), Redis, RabbitMQ, OpenSearch, Cassandra, ClickHouse, MinIO, Keycloak (realm import), Mailpit, NGINX, OpenTelemetry collector, Prometheus, Grafana, Tempo, Loki, plus every service; health checks and start order | ⏳ |
| P2 | Kubernetes overlays `deploy/k8s/overlays` | `dev`, `staging`, `prod` composing every service's `k8s/` base; namespaces `e-commerce-shop`, `e-commerce-platform`; operator manifests for data stores | ⏳ |
| P3 | End-to-end tests `tests/e2e` | Journeys against the running stack: browse, search, cart, checkout, cancel, review, email received, analytics row present | ⏳ |
| P4 | Performance tests `tests/performance` | Gatling simulations for browse, search, add to cart and checkout, with p99 budgets from the spec | ⏳ |
| P5 | Observability | Grafana dashboards (RED per service, saga outcomes, outbox backlog, consumer lag, DLQ depth), Prometheus alert rules with burn rates | ⏳ |
| P6 | Documentation pipeline `tools/` | Diagrams → SVG (light + dark), Markdown → HTML, walkthrough builder and checker, link checker | ✅ |
| P7 | Walkthrough engine | Animated diagrams, themes, narration on/off (female voice, "Amy" when available), keyboard controls, automated check | ✅ |

## 4. Testing strategy (pyramid)

| Level | Where | Runs | Purpose |
|---|---|---|---|
| Domain unit | Every service | Every build | Business rules and state machines, in milliseconds |
| Application with test doubles | Every service | Every build | Use cases and saga paths without infrastructure |
| Architecture rules | Every service | Every build | Dependency direction stays hexagonal |
| Container integration | Every service | `-PwithContainers` locally, always in CI | Real SQL, real Kafka, real Redis, OpenSearch and Cassandra behaviour |
| Contract | Producers and consumers | Every build | Published events match their schemas; consumers parse their copies |
| End to end | `tests/e2e` | Nightly and before release | Journeys across services |
| Performance | `tests/performance` | Before release | p99 budgets at target load |

## 5. Commit and push routine

* Small commits, each describing one change, pushed to `system-design-e-commerce-draft-1` and fast-forwarded to `main` about every
  two minutes of work.
* Before each push: the documentation pipeline regenerates diagrams, HTML and walkthroughs. Changed services must compile, and their
  tests must pass before a service is marked done.
* No build output is committed (see `.gitignore`), and commit messages describe the change only.
