package com.jkbootup.ecommerce.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private final Instant expiry = Instant.parse("2026-11-01T10:15:00Z");
    private final Reservation reservation = Reservation.hold(UUID.randomUUID(), Sku.of("SKU-1"), Quantity.of(2), expiry);

    @Test
    void moveIsIdempotent() {
        assertThat(reservation.moveTo(ReservationStatus.RELEASED)).isTrue();
        assertThat(reservation.moveTo(ReservationStatus.RELEASED)).isFalse();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void endedReservationCannotBeCommitted() {
        reservation.moveTo(ReservationStatus.EXPIRED);
        assertThatThrownBy(() -> reservation.moveTo(ReservationStatus.COMMITTED))
                .isInstanceOf(DomainException.class).hasMessageContaining("cannot become COMMITTED");
    }

    @Test
    void onlyCommittedStockCanBeReturned() {
        assertThatThrownBy(() -> reservation.moveTo(ReservationStatus.RETURNED)).isInstanceOf(DomainException.class);
        reservation.moveTo(ReservationStatus.COMMITTED);
        assertThat(reservation.moveTo(ReservationStatus.RETURNED)).isTrue();
    }

    @Test
    void dueOnlyWhileReservedAndPastExpiry() {
        assertThat(reservation.isDue(expiry.minusSeconds(1))).isFalse();
        assertThat(reservation.isDue(expiry)).isTrue();
        reservation.moveTo(ReservationStatus.COMMITTED);
        assertThat(reservation.isDue(expiry.plusSeconds(60))).isFalse();
    }

    @Test
    void valueObjectsValidate() {
        assertThatThrownBy(() -> Sku.of("bad sku")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Quantity.of(0)).isInstanceOf(DomainException.class);
    }
}
