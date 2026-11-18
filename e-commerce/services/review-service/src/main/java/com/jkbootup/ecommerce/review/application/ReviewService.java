package com.jkbootup.ecommerce.review.application;

import com.jkbootup.ecommerce.review.application.port.PurchaseLedger;
import com.jkbootup.ecommerce.review.application.port.ReviewEvents;
import com.jkbootup.ecommerce.review.application.port.ReviewStore;
import com.jkbootup.ecommerce.review.domain.DomainException;
import com.jkbootup.ecommerce.review.domain.Ids;
import com.jkbootup.ecommerce.review.domain.PostOutcome;
import com.jkbootup.ecommerce.review.domain.Review;
import com.jkbootup.ecommerce.review.domain.ReviewPage;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * Posting a review is three steps on a store without multi-row transactions:
 * <ol>
 *   <li>claim the (product, customer) slot with a lightweight transaction, storing the whole review in the claim;</li>
 *   <li>append the review to the product's timeline (idempotent);</li>
 *   <li>flip the claim's {@code counted} flag with a second lightweight transaction, and only the caller that flipped it
 *       increments the counters.</li>
 * </ol>
 * If a pod dies between the steps, the customer's retry finds an unfinished claim and completes it; the counters are
 * incremented at most once.
 */
public class ReviewService {

    public static final int MAX_LIMIT = 50;

    private final ReviewStore store;
    private final PurchaseLedger purchases;
    private final ReviewEvents events;
    private final Clock clock;

    public ReviewService(ReviewStore store, PurchaseLedger purchases, ReviewEvents events, Clock clock) {
        this.store = store;
        this.purchases = purchases;
        this.events = events;
        this.clock = clock;
    }

    public PostOutcome post(String sku, UUID customerId, int rating, String title, String body) {
        Review review = new Review(Ids.newId(), sku, customerId, rating, title, body, purchases.hasBought(customerId, sku),
                clock.instant().truncatedTo(ChronoUnit.MILLIS));          // Cassandra timestamps hold milliseconds
        Optional<ReviewStore.Claim> existing = store.claim(review);
        if (existing.isPresent()) {
            if (existing.get().counted()) throw new DomainException("ALREADY_REVIEWED", "You have already reviewed " + sku);
            return new PostOutcome(finish(existing.get().review()), true);
        }
        return new PostOutcome(finish(review), false);
    }

    private Review finish(Review review) {
        store.append(review);
        if (store.markCounted(review.sku(), review.customerId())) {
            store.count(review.sku(), review.rating());
            events.reviewPosted(review);
        }
        return review;
    }

    public void purchased(UUID customerId, List<String> skus, Instant at) {
        for (String sku : skus) purchases.record(customerId, sku, at);
    }

    public ReviewPage reviews(String sku, int limit, String cursor) {
        if (limit == 0) limit = 10;
        if (limit < 1 || limit > MAX_LIMIT) throw new DomainException("INVALID_LIMIT", "limit is 1 to " + MAX_LIMIT);
        Cursor from = cursor == null ? null : Cursor.decode(cursor, sku);
        List<Review> items = new ArrayList<>();
        boolean more = false;
        for (String bucket : store.buckets(sku)) {
            if (from != null && bucket.compareTo(from.bucket()) > 0) continue;               // newer than the cursor
            Review after = from != null && bucket.equals(from.bucket()) ? from.last(sku) : null;
            List<Review> got = store.page(sku, bucket, after, limit - items.size() + 1);
            for (Review r : got) {
                if (items.size() == limit) { more = true; break; }
                items.add(r);
            }
            if (more) break;
        }
        String next = more ? new Cursor(items.getLast().bucket(), items.getLast().createdAt(), items.getLast().id()).encode() : null;
        return new ReviewPage(store.summary(sku), items, next);
    }

    /** Position after the last review shown: its bucket, time and id. Opaque to clients. */
    record Cursor(String bucket, Instant createdAt, UUID reviewId) {

        String encode() {
            String raw = bucket + "|" + createdAt.toEpochMilli() + "|" + reviewId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor, String sku) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(p[0], Instant.ofEpochMilli(Long.parseLong(p[1])), UUID.fromString(p[2]));
            } catch (RuntimeException e) {
                throw new DomainException("INVALID_CURSOR", "cursor is not one this service returned");
            }
        }

        /** A stand-in review carrying only the clustering key, for the store's "after" restriction. */
        Review last(String sku) {
            return new Review(reviewId, sku, new UUID(0, 0), 1, "-", "", false, createdAt);
        }
    }
}
