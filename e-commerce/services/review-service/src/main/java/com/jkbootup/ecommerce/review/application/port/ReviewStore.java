package com.jkbootup.ecommerce.review.application.port;

import com.jkbootup.ecommerce.review.domain.RatingSummary;
import com.jkbootup.ecommerce.review.domain.Review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cassandra: one table per query. Every method is a single-partition operation. */
public interface ReviewStore {

    /** The guard row for (sku, customer): the review it holds and whether the counters already include it. */
    record Claim(Review review, boolean counted) {}

    /**
     * Lightweight transaction {@code INSERT … IF NOT EXISTS} on the guard. Returns empty when this call claimed the
     * slot; otherwise the existing claim, which may belong to an attempt that never finished.
     */
    Optional<Claim> claim(Review review);

    /** Writes the review into the product's timeline (an idempotent upsert, safe to repeat). */
    void append(Review review);

    /** {@code UPDATE guard SET counted = true IF counted = false}: true only for the one caller that flipped it. */
    boolean markCounted(String sku, UUID customerId);

    /** Adds one review with {@code rating} stars to the product's counters. Not idempotent: call only after markCounted. */
    void count(String sku, int rating);

    RatingSummary summary(String sku);

    /** Newest first, from {@code bucket} backwards; {@code after} is the last review of the previous page, or null. */
    List<Review> page(String sku, String bucket, Review after, int limit);

    /** Month buckets that hold reviews of this product, newest first. */
    List<String> buckets(String sku);
}
