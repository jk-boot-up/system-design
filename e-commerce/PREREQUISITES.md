# Prerequisites: e-commerce platform

What you need to build, test and run the whole system locally. Each service also has its own `PREREQUISITES.md` listing only what
that service needs; you can work on one service without installing everything here.

## 1. Tools

| Tool | Version | Needed for | Check |
|---|---|---|---|
| Git | 2.40+ | Cloning the repository | `git --version` |
| Docker Engine (or Docker Desktop) | 26+ with Compose v2 | The local stack; container-based tests | `docker version`, `docker compose version` |
| JDK | 25 (Temurin, Oracle or any OpenJDK 25 build) | Java services | `java -version` |
| Go | 1.24+ | notification-service | `go version` |
| Python | 3.12+ | recommendation-service | `python3 --version` |
| Node.js | 20+ | Documentation tooling only (diagram rendering) | `node --version` |
| curl and jq | any | Trying the APIs from a shell | `curl --version`, `jq --version` |

Gradle is **not** a prerequisite: every Java service ships its own Gradle wrapper (`./gradlew`), which downloads the right Gradle
version the first time it runs.

### Install

<details><summary>macOS (Homebrew)</summary>

```bash
brew install git openjdk@25 go python@3.12 node jq
brew install --cask docker            # Docker Desktop
```
</details>

<details><summary>Ubuntu / Debian</summary>

```bash
sudo apt-get update && sudo apt-get install -y git curl jq python3.12 python3.12-venv
# JDK 25: https://adoptium.net/installation/linux  (or: sdk install java 25-tem via SDKMAN)
# Go:     https://go.dev/doc/install
# Docker: https://docs.docker.com/engine/install/ubuntu/
# Node:   https://nodejs.org/en/download/package-manager
```
</details>

<details><summary>Windows</summary>

Use WSL 2 with Ubuntu and follow the Ubuntu steps, plus Docker Desktop with the WSL 2 backend.
</details>

## 2. Machine resources

| Running | CPU | Memory | Disk |
|---|---|---|---|
| One service + its own data store | 2 cores | 4 GB | 5 GB |
| Whole stack (all data stores, brokers, observability, services) | 8 cores | 16 GB (give Docker at least 12 GB) | 25 GB |

The heaviest containers are OpenSearch, Cassandra and Kafka. To save memory, start only the infrastructure a service needs, as
shown in each service's README.

## 3. Ports used on the host

The whole system uses these host ports when the local stack is running. Other systems in this repository use other ranges, so they
can run side by side.

| Range / port | Used by |
|---|---|
| 8080 | api-gateway (behind NGINX on 80/443) |
| 8081 – 8089 | services (catalog, search, cart, inventory, order, payment, review, notification, recommendation) |
| 80, 443 | NGINX edge |
| 5432 | PostgreSQL |
| 6379 | Redis |
| 9092 | Kafka |
| 5672, 15672 | RabbitMQ (AMQP, management UI) |
| 9200 | OpenSearch |
| 9042 | Cassandra |
| 8123 | ClickHouse HTTP |
| 9000, 9001 | MinIO (API, console) |
| 8180 | Keycloak |
| 8025 | Mailpit web UI (catches all emails) |
| 3000, 9090 | Grafana, Prometheus |

## 4. Accounts and secrets

None. The local stack uses throw-away credentials defined in `deploy/compose/.env.example`. The payment provider is simulated, and
emails go to Mailpit. Never put real secrets in the repository.

## 5. Verify your setup

```bash
docker run --rm hello-world
java -version          # must report 25
go version             # 1.24 or newer
python3 --version      # 3.12 or newer
cd services/inventory-service && ./gradlew test     # first run downloads Gradle and dependencies
```

## 6. Optional

| Tool | Why |
|---|---|
| kubectl 1.31+ and kind or minikube | Trying the Kubernetes manifests locally |
| An IDE (IntelliJ IDEA, VS Code, GoLand, PyCharm) | Each service opens as its own project |
| k9s | Watching pods when running on Kubernetes |
