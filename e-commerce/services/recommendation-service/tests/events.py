import json


def confirmed(event_id: str, *skus: str) -> bytes:
    return json.dumps({"eventId": event_id, "type": "order.OrderConfirmed", "version": 1, "occurredAt": "2026-11-18T10:00:00Z",
                       "aggregateId": "o-" + event_id,
                       "payload": {"orderId": "o-" + event_id, "customerId": "c-1", "customerEmail": "ada@example.com",
                                   "total": {"amount": "1.00", "currency": "EUR"},
                                   "lines": [{"sku": s, "productName": s, "quantity": 1,
                                              "unitPrice": {"amount": "1.00", "currency": "EUR"}} for s in skus]}}).encode()
