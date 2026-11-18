package com.jkbootup.ecommerce.gateway.domain;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTokenBucketsTest {

    final AtomicLong now = new AtomicLong();
    final LocalTokenBuckets buckets = new LocalTokenBuckets(1000, now::get);
    final RatePolicy policy = new RatePolicy("checkout", 0.5, 3);           // 3 at once, then one every 2 s

    @Test
    void burstThenRefillAtTheRate() {
        for (int i = 0; i < 3; i++) assertThat(buckets.take(policy, "ada").allowed()).isTrue();
        Decision denied = buckets.take(policy, "ada");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(2);
        now.addAndGet(2_000_000_000L);
        assertThat(buckets.take(policy, "ada").allowed()).isTrue();
        assertThat(buckets.take(policy, "ada").allowed()).isFalse();
    }

    @Test
    void keysAreIndependentAndTheMapIsBounded() {
        for (int i = 0; i < 3; i++) buckets.take(policy, "ada");
        assertThat(buckets.take(policy, "bob").allowed()).isTrue();
        LocalTokenBuckets tiny = new LocalTokenBuckets(2, now::get);
        for (int i = 0; i < 3; i++) tiny.take(policy, "a");
        tiny.take(policy, "b");
        tiny.take(policy, "c");                                             // "a" is forgotten: a fresh bucket
        assertThat(tiny.take(policy, "a").allowed()).isTrue();
    }

    @Test
    void scaledPolicyIsStricter() {
        RatePolicy browse = new RatePolicy("browse", 20, 40).scaled(0.25);
        assertThat(browse.burst()).isEqualTo(10);
        assertThat(browse.ratePerSecond()).isEqualTo(5);
        assertThat(new RatePolicy("x", 1, 1).scaled(0.1).burst()).isEqualTo(1);
    }
}
