package com.jkbootup.ecommerce.order.domain;

import java.util.Objects;

/** One line of an order, priced when the order was placed. */
public record OrderLine(int lineNo, String sku, String productName, int quantity, Money unitPrice) {

    public OrderLine {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity <= 0) throw new DomainException("INVALID_QUANTITY", "Quantity must be positive");
        if (unitPrice.amount().signum() < 0) throw new DomainException("INVALID_PRICE", "Price cannot be negative");
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
