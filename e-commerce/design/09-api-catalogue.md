# 9. API catalogue

## Conventions

| | |
|---|---|
| **Base path** | `/api/v1/…` public (through the gateway); `/internal/…` service-to-service (never routed by the gateway) |
| **Format** | JSON, UTF-8, camelCase fields, ISO-8601 UTC timestamps |
| **Money** | `{"amount": "19.99", "currency": "EUR"}`. The amount is a string, so no floating-point rounding is possible |
| **Errors** | RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, plus `code` and `traceId` |
| **Pagination** | Cursor-based: `?limit=20&cursor=…` → `{"items": […], "nextCursor": "…" or null}` |
| **Idempotency** | `Idempotency-Key` header (1–64 chars) required on `POST /orders`. A replay returns the first response |
| **Concurrency** | `ETag` on admin resources; updates need `If-Match`, otherwise 428; on mismatch 412 |
| **Auth** | `Authorization: Bearer <JWT>`; guests get an opaque `X-Guest-Id` for carts |
| **Tracing** | `traceparent` is accepted and propagated; `X-Request-Id` is echoed back |

The OpenAPI documents live with each service: `services/<service>/contracts/openapi.yaml`.

## Public APIs (through api-gateway)

### Catalogue (catalog-service)

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/api/v1/products/{id}` | public | | `200 Product` · `404` |
| GET | `/api/v1/categories` | public | | `200 [Category]` (tree) |
| POST | `/api/v1/admin/products` | staff | `ProductCreate` | `201 Product` + `ETag` |
| PUT | `/api/v1/admin/products/{id}` | staff | `ProductUpdate` + `If-Match` | `200 Product` · `412` · `428` |
| POST | `/api/v1/admin/products/{id}/images` | staff | `{contentType, sizeBytes}` | `201 {uploadUrl, objectKey, expiresAt}` |
| POST | `/api/v1/admin/products/{id}/images/{objectKey}/confirm` | staff | | `200 Product` · `422` if the object is missing |
| POST | `/api/v1/admin/categories` | staff | `{slug, name, parentId?}` | `201 Category` |

```json
// Product
{ "id": "0193…", "sku": "SKU-1001", "name": "Trail Runner 2", "description": "…",
  "categoryId": "0193…", "brand": "Acme", "price": {"amount": "89.90", "currency": "EUR"},
  "attributes": {"size": "42", "colour": "blue"}, "status": "ACTIVE",
  "images": [{"url": "https://cdn…/products/…/1.webp", "position": 1}], "version": 3 }
```

### Search (search-service)

| Method | Path | Auth | Query | Response |
|---|---|---|---|---|
| GET | `/api/v1/search` | public | `q, category, brand (repeatable), minPrice, maxPrice, inStock, sort=relevance|price_asc|price_desc|newest, limit, cursor` | `200 {items:[ProductHit], facets:{brand:[{value,count}], category:[…]}, total, nextCursor}` |

### Cart (cart-service)

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/api/v1/cart` | customer or `X-Guest-Id` | | `200 Cart` |
| PUT | `/api/v1/cart/items/{sku}` | customer or guest | `{quantity: 0..99}` (0 removes) | `200 Cart` |
| DELETE | `/api/v1/cart` | customer or guest | | `204` |
| POST | `/api/v1/cart/merge` | customer | `{guestId}` | `200 Cart` (guest cart merged into the account cart) |

```json
// Cart
{ "owner": "c:0193…", "lines": [ {"sku": "SKU-1001", "name": "Trail Runner 2", "quantity": 2,
  "unitPrice": {"amount": "89.90", "currency": "EUR"}, "lineTotal": {"amount": "179.80", "currency": "EUR"},
  "available": true} ], "total": {"amount": "179.80", "currency": "EUR"}, "pricing": "CURRENT" }
// pricing = "PENDING" when catalog-service could not be reached (degraded mode)
```

### Orders (order-service)

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/api/v1/orders` | customer + `Idempotency-Key` | `{paymentToken}` | `201 Order` · `402 PAYMENT_DECLINED` · `409 OUT_OF_STOCK` · `422 CART_NOT_READY` · `503 DEPENDENCY_UNAVAILABLE` |
| GET | `/api/v1/orders` | customer | `limit, cursor` | `200 {items:[OrderSummary], nextCursor}` |
| GET | `/api/v1/orders/{id}` | customer (owner) or staff | | `200 Order` · `404` |
| POST | `/api/v1/orders/{id}/cancel` | customer (owner) or staff | | `200 Order` · `409 NOT_CANCELLABLE` |

```json
// Order
{ "id": "0193…", "status": "CONFIRMED", "cancelReason": null, "createdAt": "2026-11-01T10:15:30Z",
  "lines": [{"sku": "SKU-1001", "productName": "Trail Runner 2", "quantity": 2, "unitPrice": {"amount": "89.90", "currency": "EUR"}}],
  "total": {"amount": "179.80", "currency": "EUR"} }
```

### Reviews (review-service)

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/api/v1/products/{sku}/reviews` | public | `limit, cursor` | `200 {summary:{count, average}, items:[Review], nextCursor}` |
| POST | `/api/v1/products/{sku}/reviews` | customer | `{rating 1..5, title ≤ 120, body ≤ 4000}` | `201 Review` · `409 ALREADY_REVIEWED` |

### Recommendations (recommendation-service)

| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/api/v1/products/{sku}/recommendations?limit=10` | public | `200 {sku, items:[{sku, score}]}` |

## Internal APIs (service to service, mTLS)

| Service | Method | Path | Caller | Request → Response |
|---|---|---|---|---|
| catalog | POST | `/internal/products/prices` | cart | `{skus:[…]}` → `{items:[{sku, name, price, status}]}` |
| cart | GET | `/internal/carts/{customerId}/checkout-snapshot` | order | → `{lines:[{sku, name, quantity, unitPrice, available}], total}` |
| inventory | POST | `/internal/reservations` | order | `{orderId, lines:[{sku, quantity}]}` → `200 RESERVED` · `409 {code: OUT_OF_STOCK, sku}` |
| inventory | POST | `/internal/reservations/{orderId}/commit` | order | → `200` (idempotent) |
| inventory | POST | `/internal/reservations/{orderId}/release` | order | → `200` (idempotent) |
| inventory | POST | `/internal/reservations/{orderId}/return` | order | → `200` (idempotent) |
| inventory | PUT | `/internal/stock/{sku}` | staff tools | `{available}` → `200` |
| payment | POST | `/internal/payments/authorize` | order | `{orderId, amount, paymentToken}` → `200 AUTHORIZED` · `402 DECLINED` · `504 UNKNOWN` |
| payment | POST | `/internal/payments/{orderId}/capture` | order | → `200 CAPTURED` · `409` |
| payment | POST | `/internal/payments/{orderId}/void` | order | → `200 VOIDED or VOID_REQUESTED` |
| payment | POST | `/internal/payments/{orderId}/refund` | order | → `200 REFUNDED` |

Every internal command is **idempotent by design**: repeating a call with the same `orderId` returns the current result and never
applies the effect twice.

## Error codes

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Field errors in `errors: [{field, message}]` |
| 401 | `UNAUTHENTICATED` | Missing or invalid token |
| 403 | `FORBIDDEN` | Authenticated but not allowed (e.g. not the owner) |
| 404 | `NOT_FOUND` | Unknown resource |
| 409 | `OUT_OF_STOCK`, `NOT_CANCELLABLE`, `ALREADY_REVIEWED`, `ILLEGAL_STATE` | Business conflict |
| 402 | `PAYMENT_DECLINED` | Provider declined |
| 412 / 428 | `VERSION_MISMATCH` / `PRECONDITION_REQUIRED` | Optimistic locking |
| 422 | `CART_NOT_READY`, `UPLOAD_MISSING` | Semantically invalid request |
| 429 | `RATE_LIMITED` | With `Retry-After` |
| 503 | `DEPENDENCY_UNAVAILABLE` | Circuit open or timeout; retry later |
| 504 | `PAYMENT_UNKNOWN` | Payment outcome not known in time; the order was cancelled safely |
