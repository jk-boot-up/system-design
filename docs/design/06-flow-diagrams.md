# 6. Flow diagrams

## 6.1 Checkout decision flow (order-service)

```mermaid
flowchart TD
    A([POST /orders with Idempotency-Key]) --> B{Key seen for this customer?}
    B -- yes --> B1[Return the stored order<br/>same status code] --> Z([End])
    B -- no --> C[Fetch priced cart snapshot]
    C --> C1{Cart empty or a line unavailable?}
    C1 -- yes --> C2[422 problem: cart not checkout-ready] --> Z
    C1 -- no --> D[Create order PENDING<br/>+ idempotency key + OrderPlaced]
    D --> E[Reserve stock]
    E --> E1{Result}
    E1 -- out of stock --> X1[Cancel: OUT_OF_STOCK] --> R409[409] --> Z
    E1 -- timeout / breaker open --> X2[Release reservation best-effort<br/>Cancel: INVENTORY_UNAVAILABLE] --> R503[503 Retry-After] --> Z
    E1 -- reserved --> F[Authorise payment]
    F --> F1{Result}
    F1 -- declined --> G1[Release stock<br/>Cancel: PAYMENT_DECLINED] --> R402[402] --> Z
    F1 -- unknown / timeout --> G2[Request void<br/>Release stock<br/>Cancel: PAYMENT_UNKNOWN] --> R503
    F1 -- authorised --> H[Commit reservation]
    H --> I[Capture payment]
    I --> I1{Captured?}
    I1 -- no --> G3[Void authorisation<br/>Return stock<br/>Cancel: CAPTURE_FAILED] --> R502[502] --> Z
    I1 -- yes --> J[Confirm order + OrderConfirmed] --> R201[201 Created] --> Z
```

## 6.2 Saga recovery job (every 15 s, one runner via ShedLock)

```mermaid
flowchart TD
    S([Tick]) --> Q[Select orders with status PENDING or CANCELLING<br/>and updated_at older than 30 s, limit 100]
    Q --> L{More orders?}
    L -- no --> E([Done])
    L -- yes --> O[Load order and its saga step]
    O --> D{Saga step}
    D -- STARTED --> R1[Release any reservation<br/>cancel: CHECKOUT_ABANDONED]
    D -- STOCK_RESERVED --> R2[Request void, release stock<br/>cancel: CHECKOUT_ABANDONED]
    D -- PAYMENT_AUTHORIZED --> R3[Continue forward:<br/>commit stock, capture, confirm]
    D -- STOCK_COMMITTED --> R4[Continue forward:<br/>capture, confirm]
    D -- PAYMENT_CAPTURED --> R5[Confirm order]
    D -- REFUNDING --> R6[Refund, return stock, cancel]
    R1 & R2 & R3 & R4 & R5 & R6 --> U[Persist step and status + outbox event]
    U --> L
```

* **Moving forward or back:** once money has been authorised, the saga moves **forward**, because the customer intended to buy and stock is held. Before that point it moves **back**.
* **Safe to repeat:** every step is idempotent on the remote side, so running recovery twice is harmless.

## 6.3 Reservation expiry job (inventory-service, every 30 s)

```mermaid
flowchart TD
    S([Tick, ShedLock]) --> Q[SELECT reservations WHERE status = RESERVED<br/>AND expires_at < now LIMIT 500 FOR UPDATE SKIP LOCKED]
    Q --> L{Rows?}
    L -- no --> E([Done])
    L -- yes --> T[In one transaction per row:<br/>stock.available += q, stock.reserved -= q<br/>reservation.status = EXPIRED<br/>outbox StockChanged]
    T --> Q
```

## 6.4 Gateway request pipeline (Chain of Responsibility)

```mermaid
flowchart LR
    IN([Request]) --> F1[Request id<br/>X-Request-Id, traceparent]
    F1 --> F2{Route found?}
    F2 -- no --> N404[404 problem]
    F2 -- yes --> F3{Route needs auth?}
    F3 -- yes --> F4{Valid JWT?}
    F4 -- no --> N401[401 problem]
    F4 -- yes --> F5
    F3 -- no --> F5{Within rate limit?<br/>key = user or IP + route}
    F5 -- no --> N429[429 + Retry-After]
    F5 -- yes --> F6{Downstream breaker closed?}
    F6 -- no --> N503[503 fallback problem]
    F6 -- yes --> F7[Forward with timeout<br/>strip hop headers]
    F7 --> OUT([Response + security headers])
```

## 6.5 Token bucket rate limiting

```mermaid
flowchart TD
    A([Request with key k]) --> B[Lua script in Redis, atomic:<br/>tokens = min capacity, tokens + elapsed × rate]
    B --> C{tokens ≥ 1?}
    C -- yes --> D[tokens -= 1; allow<br/>X-RateLimit-Remaining]
    C -- no --> E[deny 429<br/>Retry-After = time to the next token]
    B -. Redis unavailable .-> F[Fail open with a per-pod local limiter<br/>so one outage doesn't block all traffic]
```

Default limits:

| Route | Rate |
|---|---|
| Read | 50 req/s burst 100 per user |
| Checkout | 2 req/s burst 5 per user |
| Hot SKU checkout | Per-SKU bucket set by staff during sales |

## 6.6 Kafka consumer retry and dead letters

```mermaid
flowchart TD
    A([Record received]) --> B{Already in processed_events?}
    B -- yes --> K[Commit offset] --> Z([Next])
    B -- no --> C[Process in one transaction<br/>+ insert processed_events]
    C --> D{Success?}
    D -- yes --> K
    D -- no --> E{Error type}
    E -- deserialisation / validation --> DLQ[Publish to topic.dlq<br/>headers: error, stack, attempt] --> K
    E -- transient --> F{Attempt < 5?}
    F -- yes --> G[Back off 1, 2, 4, 8, 16 s<br/>pause partition, seek back] --> A
    F -- no --> DLQ
```

DLQ records are replayed with `tools/dlq-replay` after the cause is fixed. Alerts fire when DLQ depth is above 0.

## 6.7 Cache-aside read and invalidation

```mermaid
flowchart TD
    R([Read product id]) --> C{In Redis?}
    C -- yes --> RET[Return cached]
    C -- no --> DB[Read from PostgreSQL replica] --> P[Put in Redis, TTL 10 min] --> RET
    W([Staff update]) --> T[Commit change + outbox] --> EV[Delete Redis key]
    EV --> CDN[CDN purge on ProductChanged]
```

Deleting the key after the commit, not before it, avoids a race: if the key were deleted first, a concurrent read could put the old value back.

## 6.8 Flash-sale admission

```mermaid
flowchart TD
    A([Checkout request for hot SKU]) --> B{Per-user bucket ok?}
    B -- no --> R1[429]
    B -- yes --> C{Per-SKU bucket ok?}
    C -- no --> R2[429 + Retry-After<br/>client shows queue position]
    C -- yes --> D{SKU marked sold out?<br/>local cache from StockChanged}
    D -- yes --> R3[409 sold out, no DB hit]
    D -- no --> E[Normal checkout<br/>conditional stock decrement]
```

## 6.9 Recommendation update

```mermaid
flowchart TD
    A([OrderConfirmed]) --> B{reco:seen:eventId set NX?}
    B -- no --> Z([Skip duplicate])
    B -- yes --> C[Distinct SKUs in order]
    C --> D{At least 2 SKUs?}
    D -- no --> Z2([Nothing to learn])
    D -- yes --> E[For every ordered pair a ≠ b:<br/>ZINCRBY reco:also:a 1 b]
    E --> F[Trim each set to top 200]
    F --> Z3([Done])
```
