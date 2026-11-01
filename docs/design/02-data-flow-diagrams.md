# 2. Data-flow diagrams

Notation:
* **Rectangles** are external entities.
* **Circles** (rounded nodes) are processes.
* **Cylinders** are data stores.
* **Labelled arrows** are data flows.
* **Dashed boxes** are trust boundaries.

## 2.1 Level 0: context

```mermaid
flowchart LR
    C[Customer]
    S[Staff]
    P[Payment provider]
    M[Email relay]
    I[Identity provider]
    SYS((0. E-commerce<br/>platform))

    C -- search terms, cart changes, order request, payment token --> SYS
    SYS -- products, results, cart, order status --> C
    S -- products, prices, stock levels, images --> SYS
    SYS -- presigned upload URL, confirmation --> S
    SYS -- authorise / capture / refund request + idempotency key --> P
    P -- result, provider reference --> SYS
    SYS -- order emails --> M
    I -- signing keys, user identity in tokens --> SYS
```

## 2.2 Level 1: main processes

```mermaid
flowchart TB
    C[Customer]
    S[Staff]
    P[Payment provider]
    M[Email relay]

    subgraph TB1[Trust boundary: public edge]
        G((1. Authenticate,<br/>throttle, route))
    end

    subgraph TB2[Trust boundary: cluster - mTLS]
        CAT((2. Manage<br/>catalogue))
        SR((3. Search<br/>products))
        CA((4. Maintain<br/>cart))
        ORD((5. Place and<br/>track orders))
        INV((6. Control<br/>stock))
        PAY((7. Take<br/>payment))
        REV((8. Collect<br/>reviews))
        NOT((9. Notify<br/>customers))
        RECO((10. Recommend<br/>products))
        AN((11. Analyse<br/>sales))
    end

    D1[(D1 Products)]
    D2[(D2 Search index)]
    D3[(D3 Carts)]
    D4[(D4 Orders + outbox)]
    D5[(D5 Stock + reservations)]
    D6[(D6 Payments)]
    D7[(D7 Reviews)]
    D8[(D8 Images)]
    D9[(D9 Co-purchase sets)]
    D10[(D10 Analytics tables)]
    Q1[(Q1 Email job queue)]
    EV[(Event log - Kafka)]

    C -- requests + JWT --> G
    S -- admin requests + JWT --> G
    G -- product queries --> CAT
    G -- search query --> SR
    G -- cart change --> CA
    G -- order request + idempotency key --> ORD
    G -- review --> REV

    CAT <--> D1
    CAT -- presigned URL --> S
    S -- image bytes --> D8
    CAT -- ProductChanged --> EV
    EV -- ProductChanged, StockChanged --> SR
    SR <--> D2

    CA <--> D3
    CA -- SKUs --> CAT
    CAT -- current prices --> CA

    ORD -- checkout snapshot request --> CA
    ORD <--> D4
    ORD -- reserve / commit / release --> INV
    INV <--> D5
    INV -- StockChanged --> EV
    ORD -- authorise / capture / void / refund --> PAY
    PAY <--> D6
    PAY -- charge requests --> P
    ORD -- OrderConfirmed, OrderCancelled --> EV

    EV -- OrderConfirmed --> CA
    EV -- OrderConfirmed --> REV
    EV -- OrderCancelled --> INV
    REV <--> D7
    EV -- order events --> NOT
    NOT -- email jobs --> Q1
    Q1 -- jobs --> NOT
    NOT -- emails --> M
    EV -- OrderConfirmed --> RECO
    RECO <--> D9
    G -- recommendation query --> RECO
    EV -- order + payment events --> AN
    AN <--> D10
    S -- dashboards --> AN
```

## 2.3 Level 2: process 5 (place order)

```mermaid
flowchart TB
    C[Customer]
    D4[(D4 Orders)]
    D4o[(D4 Outbox)]
    D4i[(D4 Idempotency keys)]
    EV[(Event log)]

    P51((5.1 Check<br/>idempotency key))
    P52((5.2 Fetch priced<br/>cart snapshot))
    P53((5.3 Create PENDING<br/>order))
    P54((5.4 Reserve<br/>stock))
    P55((5.5 Authorise<br/>payment))
    P56((5.6 Commit stock,<br/>capture payment))
    P57((5.7 Confirm or<br/>compensate))
    P58((5.8 Relay outbox<br/>to event log))

    C -- order request, Idempotency-Key --> P51
    P51 <-- key lookup --> D4i
    P51 -- stored response, if the key was used before --> C
    P51 -- new request --> P52
    P52 -- lines with prices --> P53
    P53 -- order, OrderPlaced --> D4 & D4o
    P53 --> P54
    P54 -- reserved / out of stock --> P57
    P54 -- reserved --> P55
    P55 -- authorised / declined / unknown --> P57
    P55 -- authorised --> P56
    P56 --> P57
    P57 -- final status, saga step --> D4
    P57 -- OrderConfirmed or OrderCancelled --> D4o
    P57 -- response --> C
    D4o -- unpublished rows --> P58
    P58 -- events --> EV
```

## 2.4 Data stores

| Id | Store | Owner | Contents | Sensitivity |
|---|---|---|---|---|
| D1 | PostgreSQL `catalog` | catalog-service | Products, categories, prices, attributes, image keys | Public |
| D2 | OpenSearch `products` | search-service | Denormalised product documents + availability | Public |
| D3 | Redis `cart:*` | cart-service | Cart lines per owner | Personal (customer id) |
| D4 | PostgreSQL `orders` | order-service | Orders, lines, saga progress, idempotency keys, outbox | Personal (customer id, email) |
| D5 | PostgreSQL `inventory` | inventory-service | Stock per SKU and warehouse, reservations | Internal |
| D6 | PostgreSQL `payments` | payment-service | Payments, attempts, provider references (no card data) | Financial |
| D7 | Cassandra `reviews` | review-service | Reviews, rating summaries, verified purchases | Personal (customer id) |
| D8 | MinIO `product-images` | catalog-service | Image objects | Public |
| D9 | Redis `reco:*` | recommendation-service | Co-purchase counts per SKU | Aggregate, no personal data |
| D10 | ClickHouse `analytics` | analytics | Order and payment facts | Personal ids hashed |
| Q1 | RabbitMQ `email.*` | notification-service | Email jobs (recipient, template, data) | Personal (email) |
| EV | Kafka topics | producers | Domain events, 7-day retention | Mixed: order events carry email |

## 2.5 Trust boundaries and controls

| Boundary | Crossing | Controls |
|---|---|---|
| Internet → edge | All client traffic | TLS 1.3, WAF, bot rules, JWT validation, per-IP and per-user rate limits, request size limits |
| Edge → cluster | Gateway → services | mTLS, network policies (gateway may only reach public-facing services), forwarded identity headers stripped and re-derived from the token |
| Service → service | Internal APIs | mTLS identity per service account; internal endpoints not routed by the gateway |
| Cluster → payment provider | Charges | Outbound allow-list, provider API key from the secret store, provider idempotency keys |
| Staff → object storage | Image upload | Presigned URL limited to one key, `PUT` only, 5-minute expiry, size and content-type conditions |
| Services → event log | Events | Kafka ACLs per service principal: produce only to its own topic, consume only from subscribed topics |
