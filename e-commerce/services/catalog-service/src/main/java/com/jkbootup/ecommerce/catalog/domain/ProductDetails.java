package com.jkbootup.ecommerce.catalog.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Everything staff edit about a product, apart from its SKU (fixed at creation) and its images (uploaded separately). */
public record ProductDetails(String name, String description, UUID categoryId, String brand, Money price,
                             Map<String, Object> attributes, ProductStatus status) {

    public static final int MAX_ATTRIBUTES = 50;

    public ProductDetails {
        if (name == null || name.isBlank() || name.length() > 200)
            throw new DomainException("INVALID_NAME", "A name of 1 to 200 characters is required");
        if (brand == null || brand.isBlank() || brand.length() > 100)
            throw new DomainException("INVALID_BRAND", "A brand of 1 to 100 characters is required");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(price, "price");
        if (price.amount().signum() < 0) throw new DomainException("INVALID_PRICE", "The price cannot be negative");
        description = description == null ? "" : description;
        if (description.length() > 10_000) throw new DomainException("INVALID_DESCRIPTION", "The description is longer than 10,000 characters");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (attributes.size() > MAX_ATTRIBUTES)
            throw new DomainException("TOO_MANY_ATTRIBUTES", "At most " + MAX_ATTRIBUTES + " attributes are allowed");
        status = status == null ? ProductStatus.DRAFT : status;
        if (status == ProductStatus.ACTIVE && !price.isPositive())
            throw new DomainException("INVALID_PRICE", "An active product needs a price above zero");
    }
}
