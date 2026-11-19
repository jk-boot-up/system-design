package com.jkbootup.ecommerce.catalog.adapter.messaging;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest {

    @Test
    void acknowledgedEventsAreSentEvenWhenOthersInTheBatchFail() {
        var rejected = new IllegalStateException("partition 3 rejected the append");
        List<CompletableFuture<?>> acks = List.of(
                CompletableFuture.completedFuture("ok"),
                CompletableFuture.failedFuture(rejected),
                CompletableFuture.completedFuture("ok"));

        var settled = OutboxRelay.settle(List.of("a", "b", "c"), acks, Duration.ofSeconds(1));

        assertThat(settled.sent()).containsExactly("a", "c");
        assertThat(settled.failed()).isEqualTo(1);
        assertThat(settled.firstFailure()).isSameAs(rejected);
    }

    @Test
    void anUnansweredAcknowledgementCountsAsFailedAfterTheTimeout() {
        List<CompletableFuture<?>> acks = List.of(CompletableFuture.completedFuture("ok"), new CompletableFuture<>());

        var settled = OutboxRelay.settle(List.of("a", "b"), acks, Duration.ofMillis(50));

        assertThat(settled.sent()).containsExactly("a");
        assertThat(settled.failed()).isEqualTo(1);
    }

    @Test
    void backsOffExponentiallyUpToTheMaximumAndResetsAfterSuccess() {
        var clock = new MutableClock();
        var backoff = new OutboxRelay.Backoff(clock);
        assertThat(backoff.ready()).isTrue();

        assertThat(backoff.failed()).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.ready()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(backoff.ready()).isTrue();

        assertThat(backoff.failed()).isEqualTo(Duration.ofSeconds(2));
        for (int i = 0; i < 10; i++) backoff.failed();
        assertThat(backoff.failed()).isEqualTo(OutboxRelay.Backoff.MAX);

        backoff.succeeded();
        assertThat(backoff.ready()).isTrue();
        assertThat(backoff.failed()).isEqualTo(Duration.ofSeconds(1));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-19T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
