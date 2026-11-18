package com.jkbootup.ecommerce.review.application;

import com.jkbootup.ecommerce.review.application.port.PurchaseLedger;
import com.jkbootup.ecommerce.review.application.port.ReviewEvents;
import com.jkbootup.ecommerce.review.application.port.ReviewStore;
import com.jkbootup.ecommerce.review.domain.RatingSummary;
import com.jkbootup.ecommerce.review.domain.Review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Behaves like the Cassandra tables, and can "crash" just before a chosen step. */
class InMemoryReviews implements ReviewStore, PurchaseLedger, ReviewEvents {

    static class Crash extends RuntimeException {}

    final Map<String, Claim> guard = new HashMap<>();
    final Map<String, Map<UUID, Review>> timeline = new HashMap<>();
    final Map<String, TreeSet<String>> buckets = new HashMap<>();
    final Map<String, long[]> counters = new HashMap<>();
    final Set<String> purchases = new HashSet<>();
    final List<Review> published = new ArrayList<>();
    String crashBefore = "";

    private void step(String name) {
        if (crashBefore.equals(name)) {
            crashBefore = "";
            throw new Crash();
        }
    }

    @Override
    public Optional<Claim> claim(Review r) {
        String key = r.sku() + "|" + r.customerId();
        Claim existing = guard.get(key);
        if (existing != null) return Optional.of(existing);
        guard.put(key, new Claim(r, false));
        return Optional.empty();
    }

    @Override
    public void append(Review r) {
        step("append");
        timeline.computeIfAbsent(r.sku() + "|" + r.bucket(), k -> new HashMap<>()).put(r.id(), r);
        buckets.computeIfAbsent(r.sku(), k -> new TreeSet<>(Comparator.reverseOrder())).add(r.bucket());
    }

    @Override
    public boolean markCounted(String sku, UUID customerId) {
        step("markCounted");
        String key = sku + "|" + customerId;
        Claim c = guard.get(key);
        if (c.counted()) return false;
        guard.put(key, new Claim(c.review(), true));
        return true;
    }

    @Override
    public void count(String sku, int rating) {
        step("count");
        long[] c = counters.computeIfAbsent(sku, k -> new long[2]);
        c[0]++;
        c[1] += rating;
    }

    @Override
    public RatingSummary summary(String sku) {
        long[] c = counters.get(sku);
        return c == null ? RatingSummary.EMPTY : new RatingSummary(c[0], c[1]);
    }

    @Override
    public List<Review> page(String sku, String bucket, Review after, int limit) {
        Comparator<Review> newestFirst = Comparator.comparing(Review::createdAt).thenComparing(Review::id).reversed();
        return timeline.getOrDefault(sku + "|" + bucket, Map.of()).values().stream().sorted(newestFirst)
                .filter(r -> after == null || newestFirst.compare(r, after) > 0).limit(limit).toList();
    }

    @Override
    public List<String> buckets(String sku) {
        return List.copyOf(buckets.getOrDefault(sku, new TreeSet<>()));
    }

    @Override
    public void record(UUID customerId, String sku, Instant purchasedAt) {
        purchases.add(customerId + "|" + sku);
    }

    @Override
    public boolean hasBought(UUID customerId, String sku) {
        return purchases.contains(customerId + "|" + sku);
    }

    @Override
    public void reviewPosted(Review review) {
        published.add(review);
    }
}
