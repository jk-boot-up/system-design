# search-service

Full-text and faceted product search. It owns no business data: its OpenSearch index is a **read model** built from
`catalog.ProductChanged` and `inventory.StockChanged`, and can always be rebuilt by replaying those topics. Every write is
**version-guarded**, so events that arrive late, twice or out of order can never overwrite newer data.

| | |
|---|---|
| Language / framework | Java 25, Spring Boot 4.0 (virtual threads), OpenSearch REST API through `RestClient` |
| Store | OpenSearch index `products-v1` behind the alias `products`; document id = SKU |
| Consumes | `catalog.ProductChanged` (`catalog.events`), `inventory.StockChanged` (`inventory.events`); unreadable events → `<topic>.dlq` |
| Called by | api-gateway (`GET /api/v1/search`) |
| Port | 8082 |
| Design | [design/README.md](design/README.md) · [walkthrough](design/walkthrough/index.html) |
| Prerequisites | [PREREQUISITES.md](PREREQUISITES.md) |

## Layout

```
search-service/
├─ README.md, PREREQUISITES.md, design/, contracts/ (openapi.yaml, consumed/)
├─ src/main/java/com/jkbootup/ecommerce/search/
│  ├─ domain/                   ProductDocument, StockUpdate, SearchQuery, SearchResult, SortOrder, WriteOutcome
│  ├─ application/              Indexer, ProductSearch + port/SearchIndex
│  ├─ adapter/opensearch/       OpenSearchIndex (versioned scripted upserts, facets, search_after), IndexBootstrap
│  ├─ adapter/messaging/        IndexingListeners, EventParser
│  ├─ adapter/web/              SearchController, ProblemHandler (503 SEARCH_UNAVAILABLE)
│  └─ config/                   wiring, Kafka error handler (retry forever, dead-letter only unreadable events)
├─ src/main/resources/opensearch/products-v1.json   index settings and strict mapping
├─ src/test/java/…              domain, parser, indexer, ArchUnit, OpenSearch + Kafka containers
├─ Dockerfile, k8s/, build files
```

## Build, test, run

```bash
./gradlew build
./gradlew test -PwithContainers                 # OpenSearch + Kafka containers
cd ../../deploy/compose && docker compose up -d opensearch kafka && cd - && ./gradlew bootRun
```

| Variable | Default | Meaning |
|---|---|---|
| `OPENSEARCH_URL` | `http://localhost:9200` | OpenSearch |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `PORT` | `8082` | HTTP port |

## Try it

```bash
curl 'localhost:8082/api/v1/search?q=trail&brand=Acme&brand=Bolt&maxPrice=100&inStock=true&sort=price_asc&limit=20'
curl 'localhost:8082/api/v1/search?q=trail&cursor=<nextCursor from the previous page>'
```

## Operations

| Signal | Where |
|---|---|
| Health, metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` |
| Freshness | Consumer lag of group `search-service` on `catalog.events` and `inventory.events` |
| Out-of-order traffic | `search_events_stale` (events ignored because a newer version was already indexed) |
| Poison events | Depth of `catalog.events.dlq` and `inventory.events.dlq` |
| Rebuild | Create `products-v2`, reset the consumer group to the earliest offset, then move the alias |
