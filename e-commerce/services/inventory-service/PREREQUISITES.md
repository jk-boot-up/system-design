# Prerequisites: inventory-service

| Tool | Version | Why | Check |
|---|---|---|---|
| JDK | 25 | Build and run | `java -version` |
| Docker | 26+ | Container integration tests (`-PwithContainers`), local PostgreSQL and Kafka, image build | `docker version` |

Nothing else: the Gradle wrapper downloads Gradle 9.8.2 and every dependency on first use.

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 2 GB | 1 GB (Gradle cache) |
| Container tests (PostgreSQL + Kafka) | 4 GB | 2 GB (images) |

## Local infrastructure

The service needs PostgreSQL 17 and Kafka 4 to run. Start only those from the system's Compose file:

```bash
cd ../../deploy/compose && docker compose up -d postgres kafka
```

## Verify

```bash
./gradlew test                      # about a minute on first run, seconds afterwards
./gradlew test -PwithContainers     # starts PostgreSQL and Kafka containers
```
