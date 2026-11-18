package com.jkbootup.ecommerce.gateway.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Token buckets kept in this process, one per key, at most {@code maxKeys} (least recently used are forgotten).
 * Used only while Redis is unreachable: each replica then limits on its own, so the policy is scaled down to stay
 * conservative in total.
 */
public class LocalTokenBuckets {

    private record Bucket(double tokens, long refilledAtNanos) {}

    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets;

    public LocalTokenBuckets(int maxKeys, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.buckets = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > maxKeys;
            }
        };
    }

    public synchronized Decision take(RatePolicy policy, String key) {
        long now = nanoTime.getAsLong();
        String id = policy.name() + ":" + key;
        Bucket b = buckets.get(id);
        double tokens = b == null ? policy.burst()
                : Math.min(policy.burst(), b.tokens() + (now - b.refilledAtNanos()) / 1e9 * policy.ratePerSecond());
        boolean allowed = tokens >= 1;
        double left = allowed ? tokens - 1 : tokens;
        buckets.put(id, new Bucket(left, now));
        return new Decision(allowed, (long) Math.floor(left), allowed ? 0 : policy.retryAfterSeconds(left), false);
    }
}
