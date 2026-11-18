package com.jkbootup.ecommerce.search.domain;

/** Availability of one SKU from inventory.StockChanged; {@code stockVersion} orders updates for the same SKU. */
public record StockUpdate(String sku, int available, boolean inStock, long stockVersion) {

    public StockUpdate {
        if (sku == null || sku.isBlank()) throw new DomainException("INVALID_EVENT", "A stock update needs a SKU");
        if (available < 0) throw new DomainException("INVALID_EVENT", "available cannot be negative");
    }
}
