package com.jkbootup.ecommerce.catalog.domain;

/**
 * Lifecycle of a product. A draft can be activated or archived; an active product can be archived; an archived product can
 * be brought back as a draft. Only ACTIVE products are visible to shoppers and searchable.
 */
public enum ProductStatus {
    DRAFT, ACTIVE, ARCHIVED;

    public boolean canMoveTo(ProductStatus next) {
        if (next == this) return true;
        return switch (this) {
            case DRAFT -> next == ACTIVE || next == ARCHIVED;
            case ACTIVE -> next == ARCHIVED;
            case ARCHIVED -> next == DRAFT;
        };
    }
}
