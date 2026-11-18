# Local stack (Docker Compose)

Every service and every piece of infrastructure of the e-commerce system on one machine. Compose project `e-commerce`.

```bash
cd e-commerce/deploy/compose
docker compose up -d --build                    # core: the checkout path
./smoke.sh                                      # staff adds a product and stock; Ada buys two; order CONFIRMED, stock 5 → 3
docker compose --profile all up -d --build      # everything
docker compose down                             # stop (add -v to delete the data volumes)
```

## Profiles

The full stack needs about 10 GB of memory for Docker. With 4 GB (Docker Desktop's default) run the core plus one light profile
(`async`), or stop the checkout services while trying `search`, `reviews` or `analytics`: OpenSearch and Cassandra are killed by the
kernel when the Docker VM itself runs out of memory, whatever their own limits say.

| Profile | Adds | Memory (limits) |
|---|---|---|
| (none) — core | PostgreSQL, Kafka (+ topic creation), Redis, Keycloak, RustFS (S3), catalog, inventory, payment, cart, order, api-gateway, NGINX edge | ~4 GB |
| `search` | OpenSearch, search-service | +1.5 GB |
| `reviews` | Cassandra, review-service | +2 GB |
| `async` | RabbitMQ, Mailpit, notification-service, recommendation-service | +0.9 GB |
| `analytics` | ClickHouse (analytics), Prometheus, Grafana | +2 GB |
| `all` | all of the above | ~10 GB |

Without a profile its routes still exist: the gateway answers `503 SEARCH_UNAVAILABLE` / `DEPENDENCY_UNAVAILABLE` at once.

## Addresses

| What | Where | Sign in |
|---|---|---|
| Shop API through the edge | https://localhost:8443/api/v1/… (self-signed certificate) | Bearer token, below |
| System documentation | https://localhost:8443/docs/ | |
| Gateway directly | http://localhost:8080 | |
| Services directly | http://localhost:8081 … 8089 (catalog … recommendation) | |
| Keycloak | http://localhost:8180 (realm `shop`) | admin / admin |
| Grafana | http://localhost:3001 (`GRAFANA_PORT` to change; dashboard "E-commerce · Orders and revenue") | anonymous viewer; admin / admin |
| Prometheus | http://localhost:9090 | |
| RabbitMQ | http://localhost:15672 | shop / shop |
| Mailpit (emails sent) | http://localhost:8025 | |
| PostgreSQL | localhost:5432, databases `inventory`, `orders`, `payments`, `catalog` | user = password = database name |
| Kafka | localhost:9092 (containers: kafka:29092) | |
| Redis, OpenSearch, Cassandra, ClickHouse, S3 | localhost:6379, 9200, 9042, 8123, 9000 | S3: minioadmin / minioadmin |

## Users and tokens

Realm `shop` ([keycloak/shop-realm.json](keycloak/shop-realm.json)); password = user name.

| User | Roles | Use |
|---|---|---|
| `ada`, `bob` | customer | Carts, orders, reviews |
| `sam` | customer, staff | Catalogue and stock administration |

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=shop-web -d username=ada -d password=ada \
  http://localhost:8180/realms/shop/protocol/openid-connect/token | jq -r .access_token)
```

Tokens carry `iss = http://localhost:8180/realms/shop`, `aud = shop-api`, `roles`, `email` and `sub`. Keycloak always issues that
issuer (`KC_HOSTNAME`), while containers fetch its keys at `keycloak:8080`; services check the issuer against the public URL.

## How it fits together

* **One PostgreSQL server, one database and owner per service** ([postgres/init.sql](postgres/init.sql)); each service migrates its own schema.
* **Kafka in KRaft mode** with two listeners: `kafka:29092` for containers, `localhost:9092` for tools and services run from an IDE.
  Topics are created by `kafka-init` from the event catalogue ([kafka/create-topics.sh](kafka/create-topics.sh)); brokers do not
  auto-create topics.
* **Object storage:** catalog reaches RustFS as `storage:9000` but signs upload URLs for `localhost:9000` (`S3_PUBLIC_ENDPOINT`), the
  address a browser on this machine can reach.
* **Start order** comes from health checks: infrastructure healthy → topics created → services ready → gateway → edge.
* **Memory:** each Java service is limited to 512 MB (heap 70 % of it, C1 only for faster start).

## Running one service from the IDE

Stop its container and run it locally; the defaults in each service's `application.yaml` point at `localhost` ports above.

```bash
docker compose stop catalog-service
cd ../../services/catalog-service && ./gradlew bootRun
```

## Verified

On a 4 GB / 4 CPU Docker VM, one profile at a time:

| Check | Result |
|---|---|
| `./smoke.sh` (core) | Product and stock created by staff; second read served from the edge cache (`HIT`); 2 in Ada's cart; order `CONFIRMED`; stock 5 → 3 |
| `async` | One confirmation email per order in Mailpit (also for orders placed before the profile started); a tent-and-stove order makes the tent recommend the stove |
| `search` | Products created earlier back-filled from Kafka with stock merged; `q=trail` finds both smoke products with the Acme brand facet |
| `reviews` | Bob's review of a product he bought is marked verified; reads through the edge |
| `analytics` | 3 confirmed orders, 379.60 EUR in `orders_per_day`; Grafana's ClickHouse and Prometheus datasources healthy; dashboard provisioned |

Found and fixed while bringing the stack up: health checks must run under bash (`/dev/tcp`); catalog signs upload URLs for the
browser's host (`S3_PUBLIC_ENDPOINT`); the gateway re-resolves service addresses every 10 s (a recreated container gets a new IP,
which Netty would otherwise trust for Docker's 600 s DNS TTL); OpenSearch needs more than 1 GB; Grafana moved to port 3001.

Tracing and log aggregation (OpenTelemetry collector, Tempo, Loki) come with the observability work (P5).
