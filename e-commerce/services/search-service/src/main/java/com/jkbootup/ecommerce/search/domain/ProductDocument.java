package com.jkbootup.ecommerce.search.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The searchable copy of a product, built from catalog.ProductChanged. {@code catalogVersion} orders the copies: a
 * document is only ever replaced by one with a higher version.
 */
public record ProductDocument(String productId, String sku, String name, String description, String category, String brand,
                              BigDecimal price, String currency, Map<String, Object> attributes, String status, String image,
                              Instant updatedAt, long catalogVersion) {

    public ProductDocument {
        Objects.requireNonNull(productId, "productId");
        if (sku == null || sku.isBlank()) throw new DomainException("INVALID_EVENT", "A product needs a SKU");
        if (catalogVersion < 1) throw new DomainException("INVALID_EVENT", "catalogVersion must be at least 1");
        Objects.requireNonNull(price, "price");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        description = description == null ? "" : description;
    }

    public boolean sellable() {
        return "ACTIVE".equals(status);
    }
}
