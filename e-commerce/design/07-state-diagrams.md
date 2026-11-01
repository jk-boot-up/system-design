# 7. State diagrams

The domain enforces these state machines in code (State pattern on the aggregates). An illegal transition throws an
`IllegalStateTransition` domain error, which maps to HTTP 409.

## 7.1 Order status

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/state-diagrams--order-status.dark.svg">
  <img alt="Order status diagram" src="diagrams/state-diagrams--order-status.light.svg">
</picture>

| From | Allowed to |
|---|---|
| `PENDING` | `CONFIRMED`, `CANCELLED` |
| `CONFIRMED` | `CANCELLING`, `SHIPPED` |
| `CANCELLING` | `CANCELLED` |
| `SHIPPED` | `DELIVERED` |
| `CANCELLED`, `DELIVERED` | none (final) |

## 7.2 Checkout saga step

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/state-diagrams--checkout-saga-step.dark.svg">
  <img alt="Checkout saga step diagram" src="diagrams/state-diagrams--checkout-saga-step.light.svg">
</picture>

## 7.3 Payment status

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/state-diagrams--payment-status.dark.svg">
  <img alt="Payment status diagram" src="diagrams/state-diagrams--payment-status.light.svg">
</picture>

## 7.4 Stock reservation status

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/state-diagrams--stock-reservation-status.dark.svg">
  <img alt="Stock reservation status diagram" src="diagrams/state-diagrams--stock-reservation-status.light.svg">
</picture>

Every transition is idempotent: repeating `release()` on a `RELEASED` reservation is a no-op that still answers success. A retried
compensation therefore never fails.

## 7.5 Email job (notification-service)

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/state-diagrams--email-job-notification-service.dark.svg">
  <img alt="Email job (notification-service) diagram" src="diagrams/state-diagrams--email-job-notification-service.light.svg">
</picture>
