package com.jkbootup.ecommerce.order.application.port;

import com.jkbootup.ecommerce.order.domain.Money;

import java.util.List;
import java.util.UUID;

/** Gets the customer's cart, priced by cart-service from the catalogue at this moment. */
public interface CartPort {

    record Line(String sku, String name, int quantity, Money unitPrice, boolean available) {}

    sealed interface Snapshot {
        record Ready(List<Line> lines) implements Snapshot {}

        record Unavailable(String reason) implements Snapshot {}
    }

    Snapshot checkoutSnapshot(UUID customerId);
}
