package com.jkbootup.ecommerce.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The product aggregate. Immutable: every change returns the next version, and {@code version} goes up by one, so a
 * write can be made conditional on the version the editor last saw (optimistic locking, exposed as an ETag).
 */
public record Product(UUID id, String sku, ProductDetails details, List<ProductImage> images, long version,
                      Instant createdAt, Instant updatedAt) {

    public static final int MAX_IMAGES = 10;
    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,39}");

    public Product {
        if (sku == null || !SKU.matcher(sku).matches())
            throw new DomainException("INVALID_SKU", "A SKU is 3 to 40 upper-case letters, digits or dashes");
        images = List.copyOf(images);
    }

    public static Product create(UUID id, String sku, ProductDetails details, Instant now) {
        return new Product(id, sku, details, List.of(), 1, now, now);
    }

    /** Replaces the editable details. The status may only follow the allowed transitions. */
    public Product update(ProductDetails next, Instant now) {
        if (!details.status().canMoveTo(next.status()))
            throw new DomainException("INVALID_TRANSITION", "A product cannot move from " + details.status() + " to " + next.status());
        return new Product(id, sku, next, images, version + 1, createdAt, now);
    }

    /** Adds a confirmed image at the end of the gallery; adding a key that is already there changes nothing. */
    public Product addImage(String objectKey, String contentType, Instant now) {
        if (hasImage(objectKey)) return this;
        if (images.size() >= MAX_IMAGES)
            throw new DomainException("TOO_MANY_IMAGES", "A product has at most " + MAX_IMAGES + " images");
        List<ProductImage> next = new ArrayList<>(images);
        next.add(new ProductImage(images.size() + 1, objectKey, contentType));
        return new Product(id, sku, details, next, version + 1, createdAt, now);
    }

    public boolean hasImage(String objectKey) {
        return images.stream().anyMatch(i -> i.objectKey().equals(objectKey));
    }

    public boolean isActive() {
        return details.status() == ProductStatus.ACTIVE;
    }

    /** The folder in object storage that holds this product's images. */
    public String imagePrefix() {
        return "products/" + id + "/";
    }
}
