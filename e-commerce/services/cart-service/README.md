# cart-service

Keeps shopping carts for guests and signed-in customers in Redis, and shows them with **current** prices. It hands order-service a
priced snapshot at checkout, and empties a customer's cart once their order is confirmed.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0 (virtual threads), Resilience4j, Caffeine |
| Store | Redis: hash `cart:c:<customer>` / `cart:g:<guest>` (SKU → quantity), 30-day expiry; `cart:seen:<eventId>` dedupe keys |
| Calls | catalog-service `POST /internal/products/prices` (300 ms timeout, circuit breaker, 30 s price cache) |
| Consumes | `order.OrderConfirmed` (empties the cart) |
| Called by | api-gateway (public cart API), order-service (checkout snapshot) |
| Port | 8083 |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
cart-service/
├─ README.md, PREREQUISITES.md, design/, contracts/ (openapi.yaml, consumed/)
├─ src/main/java/com/jkbootup/ecommerce/cart/
│  ├─ domain/                   Cart, CartOwner, PricedCart, Money
│  ├─ application/              CartService + port/ (CartRepository, PricingPort, ProcessedEvents)
│  ├─ adapter/web/              CartController (customer or guest), InternalCartController (snapshot), CartView
│  ├─ adapter/redis/            RedisCartRepository (atomic line write + expiry), RedisProcessedEvents
│  ├─ adapter/client/           CatalogPricingClient (cache + circuit breaker)
│  ├─ adapter/messaging/        OrderConfirmedListener
│  └─ config/                   wiring, security (guests allowed, tokens validated when present; checkout snapshot for order-service's token only)
├─ src/test/java/…              domain, service, ArchUnit, Redis + Kafka containers with a fake catalog
├─ Dockerfile, k8s/, build files
```

## Build, test, run

```bash
./gradlew build
./gradlew test -PwithContainers                 # Redis + Kafka containers, fake catalog over HTTP
cd ../../deploy/compose && docker compose up -d redis kafka && cd - && ./gradlew bootRun
```

| Variable | Default | Meaning |
|---|---|---|
| `REDIS_URL` | `redis://localhost:6379` | Redis |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `CATALOG_URL` | `http://localhost:8081` | catalog-service |
| `SERVICE_AUTH_CLIENT_SECRET` | — | Secret of the realm client `cart-service`; its client-credentials token goes on every price lookup ([ADR 0014](../../design/adr/0014-service-identity-with-client-credentials-tokens.md)) |
| `SERVICE_AUTH_TOKEN_URI` | `$JWT_ISSUER/protocol/openid-connect/token` | Token endpoint |
| `JWT_ISSUER` | `http://localhost:8180/realms/shop` | Keycloak realm |
| `PORT` | `8083` | HTTP port |

## Try it

```bash
G=$(uuidgen)
curl -X PUT localhost:8083/api/v1/cart/items/SKU-1001 -H "X-Guest-Id: $G" -H 'content-type: application/json' -d '{"quantity":2}'
curl localhost:8083/api/v1/cart -H "X-Guest-Id: $G"
```

## Operations

| Signal | Where |
|---|---|
| Health, metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` |
| Degraded pricing | Rate of responses with `"pricing":"PENDING"`; catalog circuit-breaker state |
| Redis memory | `INFO memory`; keys expire after 30 days without changes |
