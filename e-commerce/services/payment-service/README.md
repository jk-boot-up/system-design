# payment-service

Takes money for orders through a payment provider, safely. It guarantees **one payment per order**, **no double charge on
retries**, and **no charge for a cancelled order**, even when the provider answers late.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0 (virtual threads) |
| Store | PostgreSQL: `payments` (one per order), `payment_attempts` (append-only audit), `outbox`, `shedlock` |
| Provider | `PaymentProvider` port; `SimulatedPaymentProvider` adapter (test tokens `tok_ok`, `tok_decline`, `tok_timeout`) |
| Publishes | `payment.PaymentCaptured`, `payment.PaymentFailed`, `payment.PaymentRefunded` on `payment.events` |
| Called by | order-service (internal API) |
| Port | 8086 |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
payment-service/
├─ README.md, PREREQUISITES.md, design/, contracts/ (openapi.yaml, events/)
├─ src/main/java/com/jkbootup/ecommerce/payment/
│  ├─ domain/                   Payment (aggregate), PaymentStatus, Money
│  ├─ application/              PaymentService, PaymentReconciler + port/ (PaymentRepository, PaymentProvider, PaymentEvents, Transactions)
│  ├─ adapter/web/              PaymentController (status codes encode outcomes), ProblemHandler
│  ├─ adapter/provider/         SimulatedPaymentProvider (deadline, idempotency ledger, late success)
│  ├─ adapter/persistence/      JdbcPaymentRepository, OutboxWriter, OutboxPaymentEvents, UuidV7
│  ├─ adapter/messaging/        OutboxRelay
│  ├─ adapter/scheduling/       ReconcilerJob (ShedLock)
│  └─ config/
├─ src/main/resources/          application.yaml, db/migration
├─ src/test/java/…              domain, service (with the real simulated provider), ArchUnit, containers
├─ Dockerfile, k8s/, build files (own Gradle wrapper and version catalog)
```

## Build, test, run

```bash
./gradlew build                      # unit + architecture tests
./gradlew test -PwithContainers      # + PostgreSQL and Kafka
cd ../../deploy/compose && docker compose up -d postgres kafka && cd - && ./gradlew bootRun
```

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/payments`, `payments`, `payments` | Database |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `PORT` | `8086` | HTTP port |
| `payment.provider.timeout` | `1500ms` | Deadline for provider calls; below order-service's 2 s, so this service answers 504 itself |
| `payment.reconcile.settle-after` | `30s` | Age after which unsettled payments are reconciled |

## Try it

```bash
O=0193a1b0-0000-7000-8000-0000000000aa
curl -X POST localhost:8086/internal/payments/authorize -H 'content-type: application/json' \
  -d "{\"orderId\":\"$O\",\"amount\":{\"amount\":\"179.80\",\"currency\":\"EUR\"},\"paymentToken\":\"tok_ok\"}"
curl -X POST localhost:8086/internal/payments/$O/capture
curl -X POST localhost:8086/internal/payments/$O/refund
```

## Operations

| Signal | Query / where |
|---|---|
| Unsettled payments | `select count(*) from payments where status in ('AUTHORIZING','UNKNOWN') and updated_at < now() - interval '5 minutes'` should be 0 |
| Failed voids | `select count(*) from payments where status = 'AUTHORIZED' and void_requested` should be 0 after one reconcile pass |
| Audit of one order | `select * from payment_attempts a join payments p on p.id = a.payment_id where p.order_id = '…' order by a.created_at` |
