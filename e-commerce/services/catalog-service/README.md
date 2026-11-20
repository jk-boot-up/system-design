# catalog-service

The source of truth for products and categories. Shoppers read products and the category tree; staff create and edit them
with **conditional updates** (ETag / If-Match), and upload images **straight to object storage** with presigned URLs. Every change
is published as `catalog.ProductChanged` through a transactional outbox, so search stays in step without calling back.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0 (virtual threads), Flyway, ShedLock, MinIO client |
| Store | PostgreSQL: `categories`, `products` (attributes as JSONB with a GIN index), `product_images`, `outbox` |
| Cache | Redis: `product:<id>` JSON, 10-minute expiry ± 10 %, evicted after every committed change (cache-aside); concurrent misses for one product share one load |
| Object storage | Any S3-compatible store (MinIO in the design; RustFS in the container tests, because MinIO no longer publishes community images), bucket `product-images`, keys `products/<productId>/<uuid>.<ext>` |
| Publishes | `catalog.ProductChanged` on `catalog.events` (key = product id) |
| Called by | api-gateway (public and staff API), cart-service (`POST /internal/products/prices`, with its service token: audience `catalog-service`, `azp` `cart-service`) |
| Port | 8081 |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
catalog-service/
├─ README.md, PREREQUISITES.md, design/, contracts/ (openapi.yaml, events/)
├─ src/main/java/com/jkbootup/ecommerce/catalog/
│  ├─ domain/                   Product (versioned, immutable), ProductDetails, ProductStatus, Category, Money, Ids
│  ├─ application/              CatalogService + port/ (ProductRepository, CategoryRepository, ProductEvents,
│  │                            ProductCache, ImageStorage, Transactions)
│  ├─ adapter/web/              ProductController (public), AdminController (staff, ETags), InternalController (prices)
│  ├─ adapter/persistence/      JDBC repositories (conditional UPDATE … WHERE version = ?), OutboxProductEvents
│  ├─ adapter/redis/            RedisProductCache (failures read as misses)
│  ├─ adapter/storage/          MinioImageStorage (presigned PUT, stat)
│  ├─ adapter/messaging/        OutboxRelay (FOR UPDATE SKIP LOCKED)
│  └─ config/                   wiring, security (staff role from the token's roles claim)
├─ src/main/resources/db/migration/V1__catalog_schema.sql
├─ src/test/java/…              domain, service with test doubles, ArchUnit, PostgreSQL + Redis + Kafka + S3 (RustFS) containers
├─ Dockerfile, k8s/, build files
```

## Build, test, run

```bash
./gradlew build
./gradlew test -PwithContainers                 # PostgreSQL, Redis, Kafka and S3 (RustFS) containers
cd ../../deploy/compose && docker compose up -d postgres redis kafka minio && cd - && ./gradlew bootRun
```

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/catalog`, `catalog`, `catalog` | PostgreSQL |
| `REDIS_URL` | `redis://localhost:6379` | Redis (product cache) |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` | `http://localhost:9000`, `minioadmin`, `minioadmin`, `product-images` | MinIO; the endpoint must be reachable by the browser that uploads |
| `S3_PUBLIC_ENDPOINT` | (same as `S3_ENDPOINT`) | Host put in signed upload URLs, when browsers reach storage under another name (compose: `http://localhost:9000`) |
| `IMAGE_BASE_URL` | `http://localhost:9000/product-images` | Where shoppers load images from (the CDN in production) |
| `JWT_ISSUER` | `http://localhost:8180/realms/shop` | Keycloak realm |
| `PORT` | `8081` | HTTP port |

## Try it

```bash
TOKEN=…   # a staff token from Keycloak (realm shop, role staff)
CAT=$(curl -s -X POST localhost:8081/api/v1/admin/categories -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"slug":"running-shoes","name":"Running shoes"}' | jq -r .id)
curl -i -X POST localhost:8081/api/v1/admin/products -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d "{\"sku\":\"SKU-1001\",\"name\":\"Trail Runner 2\",\"categoryId\":\"$CAT\",\"brand\":\"Acme\",
       \"price\":{\"amount\":\"89.90\",\"currency\":\"EUR\"},\"status\":\"ACTIVE\"}"      # ETag: "1"
curl -i -X PUT localhost:8081/api/v1/admin/products/<id> -H 'If-Match: "1"' …                  # 200, ETag: "2"
curl -i -X PUT localhost:8081/api/v1/admin/products/<id> -H 'If-Match: "1"' …                  # 412 VERSION_MISMATCH
```

## Operations

| Signal | Where |
|---|---|
| Health, metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` |
| Outbox backlog | `select count(*) from outbox where published_at is null` (should stay near 0) |
| Cache hit rate | Redis `keyspace_hits` / `keyspace_misses`; a drop raises PostgreSQL read load |
| Lost-update attempts | Rate of 412 responses (normal at a low level; a spike means a tool is not re-reading) |
