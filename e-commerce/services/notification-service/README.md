# notification-service (Go)

Turns order events into emails. A **router** reads `order.events` from Kafka and queues an email job on **RabbitMQ** (with
publisher confirms, before the Kafka offset is committed). A **worker** sends each job over SMTP, at most once per event thanks to a
**sent log in Redis**, with **delayed retries** through TTL queues and a **dead-letter queue** for jobs that can never be sent.

| | |
|---|---|
| Language | Go 1.27 (franz-go, amqp091-go, go-redis, Prometheus client) |
| Consumes | `order.OrderConfirmed`, `order.OrderCancelled` on `order.events` (group `notification-service`); other types ignored |
| Queues | RabbitMQ quorum queues: `email.send`, `email.retry.<delay>`, `email.dead` |
| Store | Redis: `notif:sent:<eventId>` (7 days), `notif:claim:<eventId>` (2-minute lease) |
| Sends | SMTP: Mailpit locally, the email provider's relay in production |
| Dead letters | Unreadable events → `order.events.dlq` (key and headers kept, plus `x-reason` and `x-original-*`); unsendable jobs → `email.dead` |
| Port | 8088 (`/healthz`, `/readyz`, `/metrics`) |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
notification-service/
├─ README.md, PREREQUISITES.md, design/, contracts/consumed/ (the order event schemas it reads)
├─ cmd/notification-service/    main: wiring, ROLE=all|router|worker, graceful shutdown
├─ internal/domain/             events → EmailJob, priorities, retry ladder (no I/O)
├─ internal/app/                Router and Worker use cases + ports (JobQueue, DeadEvents, Scheduler, Mailer, SentLog)
├─ internal/adapter/kafka/      consumer with manual commits; dead-letter producer
├─ internal/adapter/rabbit/     topology, confirmed publisher, consumer with manual acks
├─ internal/adapter/dedupe/     Redis sent log
├─ internal/adapter/smtp/       SMTP mailer; 5xx = permanent, everything else retried
├─ internal/adapter/httpapi/    health and metrics
├─ internal/adapter/telemetry/  OpenTelemetry setup, trace context in Kafka and RabbitMQ headers, trace ids in logs
├─ test/                        Kafka + RabbitMQ + Redis + Mailpit containers (build tag containers)
├─ Dockerfile (distroless), k8s/
```

## Build, test, run

```bash
go test ./internal/...                                  # domain, use cases with fakes, SMTP classification
go test -tags containers ./test/                        # Kafka, RabbitMQ, Redis and Mailpit containers
cd ../../deploy/compose && docker compose up -d kafka rabbitmq redis mailpit && cd - && go run ./cmd/notification-service
open http://localhost:8025                               # Mailpit: the emails it sent
```

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BROKERS` | `localhost:9092` | Comma-separated |
| `RABBITMQ_URL` | `amqp://guest:guest@localhost:5672/` | |
| `REDIS_URL` | `redis://localhost:6379` | Sent log |
| `SMTP_ADDR`, `MAIL_FROM` | `localhost:1025`, `Shop <orders@shop.example>` | |
| `RETRY_DELAYS` | `30s,2m,10m` | One delay queue each; failures after the last reuse it |
| `ROLE` | `all` | `router`, `worker` or both |
| `PORT` | `8088` | Health and metrics |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | unset | Where spans go (OTLP over HTTP); unset, none are exported |

## Operations

| Signal | Where |
|---|---|
| Emails by outcome | `notification_emails_total{result="sent|duplicate|busy|retried|dead"}` |
| Events by outcome | `notification_events_total{result="queued|ignored|dead_letter"}` |
| Backlog | RabbitMQ `email.send` depth (KEDA scales workers on it); Kafka lag of group `notification-service` |
| Poison jobs | `email.dead` depth > 0 alerts; each message carries `x-reason` and `x-attempt` headers. Move back with a shovel once fixed |
| Unreadable events | `order.events.dlq`; list and replay with [dlq-replay](../../deploy/tools/dlq-replay/README.md) |
