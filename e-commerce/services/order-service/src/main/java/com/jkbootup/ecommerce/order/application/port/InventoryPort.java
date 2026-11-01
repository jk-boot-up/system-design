package com.jkbootup.ecommerce.order.application.port;

import com.jkbootup.ecommerce.order.domain.OrderLine;

import java.util.List;
import java.util.UUID;

/** inventory-service, seen from the saga. All commands are idempotent per order id. */
public interface InventoryPort {

    sealed interface Reservation {
        record Reserved() implements Reservation {}

        record OutOfStock(String sku) implements Reservation {}

        record Unavailable(String reason) implements Reservation {}
    }

    sealed interface Step {
        record Done() implements Step {}

        record Rejected(String code) implements Step {}

        record Unavailable(String reason) implements Step {}
    }

    Reservation reserve(UUID orderId, List<OrderLine> lines);

    Step commit(UUID orderId);

    Step release(UUID orderId);

    Step returnStock(UUID orderId);
}
