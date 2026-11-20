package com.jkbootup.ecommerce.catalog.adapter.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedisProductCacheExpiryTest {

    @Test
    void expiryIsTheBaseTimeWithinTenPercentEitherWay() {
        Duration ttl = Duration.ofMinutes(10);
        assertThat(new RedisProductCache(null, null, ttl, () -> 0.0).expiry()).isEqualTo(Duration.ofMinutes(9));
        assertThat(new RedisProductCache(null, null, ttl, () -> 0.5).expiry()).isEqualTo(ttl);
        assertThat(new RedisProductCache(null, null, ttl, () -> 0.999_999).expiry()).isLessThanOrEqualTo(Duration.ofMinutes(11));
    }

    @Test
    void entriesWrittenTogetherExpireAtDifferentTimes() {
        RedisProductCache cache = new RedisProductCache(null, null, Duration.ofMinutes(10));
        Set<Duration> expiries = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Duration d = cache.expiry();
            assertThat(d).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11));
            expiries.add(d);
        }
        assertThat(expiries).hasSizeGreaterThan(90);
    }
}
