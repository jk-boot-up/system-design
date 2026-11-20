# api-gateway (+ NGINX edge)

The single way in. **NGINX** terminates TLS and HTTP/2, compresses, applies a coarse per-address limit, **micro-caches** anonymous
catalogue and search reads for 5 seconds, and serves the documentation. Behind it, **Spring Cloud Gateway** routes each public
API to its service, validates tokens, stamps a request id, applies **token-bucket rate limits shared through Redis** (falling back
to a stricter local bucket when Redis is down), and turns a down service into a fast **503 problem** with its circuit breaker.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0, Spring Cloud Gateway Server WebFlux 5.0, Resilience4j; NGINX 1.29 |
| Store | Redis: `rate:<policy>:<key>` hashes (tokens, refill time), expiring when idle |
| Calls | catalog, search, cart, inventory (staff stock only), order, review and recommendation services; Keycloak signing keys |
| Called by | NGINX edge only (the network policy enforces it) |
| Port | 8080 (gateway); edge 8080 → redirect, 8443 TLS |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
api-gateway/
├─ README.md, PREREQUISITES.md, design/
├─ src/main/java/com/jkbootup/ecommerce/gateway/
│  ├─ domain/                   RatePolicy, Decision, LocalTokenBuckets (pure Java, injectable clock)
│  ├─ adapter/admission/        RedisTokenBuckets (Lua script, timeout, local fallback), AdmissionControl filter (429)
│  ├─ adapter/filter/           RequestIdFilter
│  ├─ adapter/web/              FallbackController (503 problem per service)
│  ├─ problem/                  ProblemResponses: 401, 403 and 429 as problems with code and traceId
│  └─ config/                   rate policies, JWT decoder (issuer + audience), access rules
├─ src/main/resources/application.yaml   routes, circuit breakers, time limits, rate limits
├─ edge/                        nginx.conf, server template, security headers, dev certificate, Dockerfile, k8s/
│  └─ waf/                      the shop's own firewall rules and exclusions (OWASP Core Rule Set plugin)
├─ src/test/java/…              buckets, Redis-down fallback, ArchUnit, gateway + Redis + stub services, NGINX image
├─ Dockerfile, k8s/, build files
```

## Build, test, run

```bash
./gradlew build
./gradlew test -PwithContainers                 # Redis container, stub services over HTTP, the NGINX edge image
cd ../../deploy/compose && docker compose up -d redis keycloak && cd - && ./gradlew bootRun
docker build -t e-commerce/edge edge && docker run --rm -p 8443:8443 -e GATEWAY_UPSTREAM=host.docker.internal:8080 e-commerce/edge
```

| Variable | Default | Meaning |
|---|---|---|
| `REDIS_URL` | `redis://localhost:6379` | Shared rate-limit buckets |
| `JWT_ISSUER`, `JWT_AUDIENCE` | `http://localhost:8180/realms/shop`, `shop-api` | Accepted tokens |
| `SHOP_ORIGINS` | `http://localhost:5173` | Browser origins allowed by CORS |
| `CATALOG_URL` … `RECOMMENDATION_URL` | `http://localhost:8081` … `8089` | Where each route goes |
| `PORT` | `8080` | HTTP port |
| `GATEWAY_UPSTREAM` (edge) | `api-gateway:8080` | Where NGINX forwards |
| `MODSEC_RULE_ENGINE` (edge) | `On` | Firewall mode: `On` refuses, `DetectionOnly` only logs (for a new rule set version) |
| `BLOCKING_PARANOIA`, `DETECTION_PARANOIA`, `ANOMALY_INBOUND` (edge) | `1`, `2`, `5` | Core Rule Set strictness: level 1 blocks, level 2 is logged only so it can be tuned; refuse at an anomaly score of 5 |

## Routes

| Path | Service | Extra |
|---|---|---|
| `/api/v1/products/*/reviews` | review-service | Listed before the catalogue route: first match wins |
| `/api/v1/products/*/recommendations` | recommendation-service | |
| `/api/v1/products/**`, `/api/v1/categories`, `/api/v1/admin/products/**`, `/api/v1/admin/categories` | catalog-service | Staff paths need the `staff` role |
| `PUT /api/v1/admin/stock/{sku}` | inventory-service | Rewritten to `/internal/stock/{sku}`; staff only |
| `/api/v1/search` | search-service | Fallback code `SEARCH_UNAVAILABLE` |
| `/api/v1/cart/items/{sku}` | cart-service | Plus the `hot-sku` limit, one bucket per SKU for all shoppers |
| `/api/v1/cart/**` | cart-service | Guests allowed (`X-Guest-Id`) |
| `POST /api/v1/orders` | order-service | Signed in; `checkout` limit per customer (placing orders only); 12 s time limit |
| `/api/v1/orders/**` | order-service | Signed in; reading, polling and cancelling use the `browse` limit |
| `/internal/**` | none | Denied at the gateway and 404 at the edge |

## Rate limits

| Policy | Key | Rate | Burst | When Redis is down (× 0.25, per replica) |
|---|---|---|---|---|
| `browse` | user, else client address | 20/s | 40 | 5/s, 10 |
| `checkout` | user | 1 every 5 s | 3 | 1 every 20 s, 1 |
| `hot-sku` | SKU | 200/s | 400 | 50/s, 100 |

Over the limit: `429`, `Retry-After`, problem code `RATE_LIMITED`. Every response has `X-RateLimit-Remaining-<policy>`.

## Operations

| Signal | Where |
|---|---|
| Health, metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` |
| Rate limits degraded | `gateway_ratelimit_degraded_total` > 0 means Redis is not answering within 50 ms |
| Circuit breakers | `resilience4j_circuitbreaker_state{name=…}`; open means the gateway is answering 503 for that service |
| Rejections | `spring_cloud_gateway_requests_seconds_count{httpStatusCode="429"}` per route |
| Edge cache | `cache` field in the NGINX JSON access log (`HIT`, `MISS`, `STALE`, `UPDATING`, `BYPASS`) |
| Tracing a request | `X-Request-Id` in the response; the same id is in the edge, gateway and service logs |
