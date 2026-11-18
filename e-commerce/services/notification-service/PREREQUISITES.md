# Prerequisites: notification-service

| Tool | Version | Why | Check |
|---|---|---|---|
| Go | 1.27 | Build and test | `go version` |
| Docker | 26+ | Container tests (Kafka, RabbitMQ, Redis, Mailpit), image build | `docker version` |

`go` downloads every module on first use. Without a system-wide Go, unpack the official archive from go.dev anywhere (for
example `~/.cache/go-sdk/go1.27.4`) and put its `bin` on `PATH`.

The container test uses `confluentinc/confluent-local` for Kafka, because the testcontainers-go Kafka module starts the broker
with Confluent's scripts.

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 1 GB | 1 GB |
| Container tests | 3 GB | 2 GB |

## Verify

```bash
go test ./internal/...
go test -tags containers ./test/
```
