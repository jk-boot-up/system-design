package com.jkbootup.ecommerce.inventory.domain;

/**
 * Units of a SKU that can still be sold, and units held by open reservations. {@code version} goes up by one with every
 * change of the row, so consumers of StockChanged can tell a newer level from an older one that arrives late.
 */
public record StockLevel(Sku sku, int available, int reserved, long version) {

    public boolean inStock() {
        return available > 0;
    }
}
