package com.jkbootup.ecommerce.inventory.domain;

/** Units of a SKU that can still be sold, and units held by open reservations. */
public record StockLevel(Sku sku, int available, int reserved) {

    public boolean inStock() {
        return available > 0;
    }
}
