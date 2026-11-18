package com.jkbootup.ecommerce.review.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One customer's review of one product. {@code verified} means the customer had bought the product when they wrote it. */
public record Review(UUID id, String sku, UUID customerId, int rating, String title, String body, boolean verified, Instant createdAt) {

    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,39}");

    public Review {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sku == null || !SKU.matcher(sku).matches()) throw new DomainException("INVALID_SKU", "Unknown SKU format");
        if (rating < 1 || rating > 5) throw new DomainException("INVALID_RATING", "A rating is 1 to 5 stars");
        title = title == null ? "" : title.strip();
        body = body == null ? "" : body.strip();
        if (title.isEmpty() || title.length() > 120) throw new DomainException("INVALID_TITLE", "A title of 1 to 120 characters is required");
        if (body.length() > 4000) throw new DomainException("INVALID_BODY", "The review is longer than 4000 characters");
    }

    /** The partition bucket: one per product per month, so no partition grows without bound. */
    public String bucket() {
        return bucketOf(createdAt);
    }

    public static String bucketOf(Instant at) {
        return YearMonth.from(at.atOffset(ZoneOffset.UTC)).toString();
    }
}
