# 7. State diagrams

The domain enforces these state machines in code (State pattern on the aggregates). An illegal transition throws an
`IllegalStateTransition` domain error, which maps to HTTP 409.

## 7.1 Order status

```mermaid
stateDiagram-v2
    [*] --> PENDING: place()
    PENDING --> CONFIRMED: confirm()<br/>payment captured
    PENDING --> CANCELLED: cancel(reason)<br/>out of stock / declined / unknown / abandoned
    CONFIRMED --> CANCELLING: requestCancellation()<br/>customer or staff
    CONFIRMED --> SHIPPED: ship()
    CANCELLING --> CANCELLED: refunded and stock returned
    SHIPPED --> DELIVERED: deliver()
    CANCELLED --> [*]
    DELIVERED --> [*]
```

| From | Allowed to |
|---|---|
| `PENDING` | `CONFIRMED`, `CANCELLED` |
| `CONFIRMED` | `CANCELLING`, `SHIPPED` |
| `CANCELLING` | `CANCELLED` |
| `SHIPPED` | `DELIVERED` |
| `CANCELLED`, `DELIVERED` | none (final) |

## 7.2 Checkout saga step

```mermaid
stateDiagram-v2
    [*] --> STARTED
    STARTED --> STOCK_RESERVED: reservation ok
    STARTED --> COMPENSATED: out of stock
    STOCK_RESERVED --> PAYMENT_AUTHORIZED: authorised
    STOCK_RESERVED --> COMPENSATING: declined / unknown
    PAYMENT_AUTHORIZED --> STOCK_COMMITTED: commit ok
    STOCK_COMMITTED --> PAYMENT_CAPTURED: capture ok
    STOCK_COMMITTED --> COMPENSATING: capture failed
    PAYMENT_CAPTURED --> COMPLETED: order confirmed
    COMPENSATING --> COMPENSATED: void/refund + release done
    COMPLETED --> REFUNDING: cancellation requested
    REFUNDING --> COMPENSATED: refunded + stock returned
    COMPLETED --> [*]
    COMPENSATED --> [*]
```

## 7.3 Payment status

```mermaid
stateDiagram-v2
    [*] --> AUTHORIZING: authorize()
    AUTHORIZING --> AUTHORIZED: provider ok
    AUTHORIZING --> DECLINED: provider declined
    AUTHORIZING --> UNKNOWN: timeout / error
    UNKNOWN --> AUTHORIZED: reconciled, provider had authorised (no void requested)
    UNKNOWN --> VOIDED: reconciled authorised + void requested
    UNKNOWN --> DECLINED: reconciled, no authorisation
    AUTHORIZED --> CAPTURED: capture()
    AUTHORIZED --> VOIDED: void()
    CAPTURED --> REFUNDED: refund()
    DECLINED --> [*]
    VOIDED --> [*]
    REFUNDED --> [*]
```

## 7.4 Stock reservation status

```mermaid
stateDiagram-v2
    [*] --> RESERVED: reserve() - stock moved available → reserved
    RESERVED --> COMMITTED: commit() - reserved → sold
    RESERVED --> RELEASED: release() - reserved → available
    RESERVED --> EXPIRED: expiry job - reserved → available
    COMMITTED --> RETURNED: return() - sold → available (cancel after confirm)
    RELEASED --> [*]
    EXPIRED --> [*]
    RETURNED --> [*]
```

Every transition is idempotent: repeating `release()` on a `RELEASED` reservation is a no-op that still answers success. A retried
compensation therefore never fails.

## 7.5 Email job (notification-service)

```mermaid
stateDiagram-v2
    [*] --> QUEUED: router publishes
    QUEUED --> SENT: worker sent + ack
    QUEUED --> RETRY_WAIT: temporary failure
    RETRY_WAIT --> QUEUED: TTL expired (30 s → 2 min → 10 min)
    QUEUED --> DEAD: permanent failure or 5th attempt
    SENT --> [*]
    DEAD --> [*]
```
