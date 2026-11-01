package com.jkbootup.ecommerce.inventory.domain;

/** A positive number of units. */
public record Quantity(int value) {

    public Quantity {
        if (value <= 0) throw new DomainException("INVALID_QUANTITY", "Quantity must be positive: " + value);
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }
}
