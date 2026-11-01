package com.jkbootup.ecommerce.inventory.application;

import com.jkbootup.ecommerce.inventory.domain.DomainException;
import com.jkbootup.ecommerce.inventory.domain.OrderLine;
import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.ReserveOutcome;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationServiceTest {

    private static final Sku SHOES = Sku.of("SKU-SHOES");
    private static final Sku SOCKS = Sku.of("SKU-SOCKS");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private InMemoryStock store;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStock();
        service = new ReservationService(store, store, store, store, clock, Duration.ofMinutes(15));
        service.setAvailable(SHOES, 5);
        service.setAvailable(SOCKS, 1);
        store.published.clear();
    }

    @Test
    void reservesAllLinesAndPublishesAvailability() {
        UUID order = UUID.randomUUID();
        var outcome = service.reserve(order, List.of(line(SHOES, 2), line(SOCKS, 1)));
        assertThat(outcome).isEqualTo(new ReserveOutcome.Reserved(clock.instant().plus(Duration.ofMinutes(15))));
        assertThat(level(SHOES)).containsExactly(3, 2);
        assertThat(level(SOCKS)).containsExactly(0, 1);
        assertThat(store.published).extracting(l -> l.sku().value()).containsExactly("SKU-SHOES", "SKU-SOCKS");
    }

    @Test
    void reservesNothingWhenOneLineIsShort() {
        var outcome = service.reserve(UUID.randomUUID(), List.of(line(SHOES, 2), line(SOCKS, 2)));
        assertThat(outcome).isEqualTo(new ReserveOutcome.OutOfStock(SOCKS));
        assertThat(level(SHOES)).containsExactly(5, 0);
        assertThat(store.reservations).isEmpty();
        assertThat(store.published).isEmpty();
    }

    @Test
    void mergesRepeatedSkus() {
        assertThat(service.reserve(UUID.randomUUID(), List.of(line(SHOES, 3), line(SHOES, 3))))
                .isInstanceOf(ReserveOutcome.OutOfStock.class);
        assertThat(service.reserve(UUID.randomUUID(), List.of(line(SHOES, 2), line(SHOES, 3))))
                .isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(level(SHOES)).containsExactly(0, 5);
    }

    @Test
    void reserveIsIdempotentPerOrder() {
        UUID order = UUID.randomUUID();
        service.reserve(order, List.of(line(SHOES, 2)));
        assertThat(service.reserve(order, List.of(line(SHOES, 2)))).isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(level(SHOES)).containsExactly(3, 2);
    }

    @Test
    void commitThenReturn() {
        UUID order = UUID.randomUUID();
        service.reserve(order, List.of(line(SHOES, 2)));
        service.commit(order);
        service.commit(order);
        assertThat(level(SHOES)).containsExactly(3, 0);
        service.returnStock(order);
        service.returnStock(order);
        assertThat(level(SHOES)).containsExactly(5, 0);
    }

    @Test
    void releaseIsIdempotentAndIgnoresUnknownOrders() {
        UUID order = UUID.randomUUID();
        service.reserve(order, List.of(line(SHOES, 4)));
        service.release(order);
        service.release(order);
        service.release(UUID.randomUUID());
        assertThat(level(SHOES)).containsExactly(5, 0);
    }

    @Test
    void expiredReservationsReturnStockAndCannotBeCommitted() {
        UUID order = UUID.randomUUID();
        service.reserve(order, List.of(line(SHOES, 4)));
        clock.advance(Duration.ofMinutes(14));
        assertThat(service.expireDue(100)).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(service.expireDue(100)).isEqualTo(1);
        assertThat(level(SHOES)).containsExactly(5, 0);
        assertThatThrownBy(() -> service.commit(order)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RESERVATION_EXPIRED");
    }

    private int[] level(Sku sku) {
        var l = service.level(sku).orElseThrow();
        return new int[]{l.available(), l.reserved()};
    }

    private static OrderLine line(Sku sku, int q) {
        return new OrderLine(sku, Quantity.of(q));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
