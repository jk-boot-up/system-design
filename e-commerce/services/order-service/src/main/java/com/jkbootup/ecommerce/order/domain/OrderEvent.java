package com.jkbootup.ecommerce.order.domain;

import java.util.List;
import java.util.UUID;

/** Facts recorded by the {@link Order} aggregate and published through the outbox. */
public sealed interface OrderEvent {

    UUID orderId();

    String type();

    record OrderPlaced(UUID orderId, UUID customerId, Money total, List<OrderLine> lines) implements OrderEvent {
        public String type() { return "order.OrderPlaced"; }
    }

    record OrderConfirmed(UUID orderId, UUID customerId, String customerEmail, Money total, List<OrderLine> lines)
            implements OrderEvent {
        public String type() { return "order.OrderConfirmed"; }
    }

    record OrderCancelled(UUID orderId, UUID customerId, String customerEmail, CancelReason reason, Money total)
            implements OrderEvent {
        public String type() { return "order.OrderCancelled"; }
    }
}
