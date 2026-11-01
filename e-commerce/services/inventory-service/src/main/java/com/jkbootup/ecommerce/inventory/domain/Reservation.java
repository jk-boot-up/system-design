package com.jkbootup.ecommerce.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stock held for one line of one order. Every transition is idempotent: asking for the state the reservation is
 * already in is a no-op, so retried compensations never fail.
 */
public final class Reservation {

    private final UUID orderId;
    private final Sku sku;
    private final Quantity quantity;
    private final Instant expiresAt;
    private ReservationStatus status;

    public Reservation(UUID orderId, Sku sku, Quantity quantity, ReservationStatus status, Instant expiresAt) {
        this.orderId = Objects.requireNonNull(orderId);
        this.sku = Objects.requireNonNull(sku);
        this.quantity = Objects.requireNonNull(quantity);
        this.status = Objects.requireNonNull(status);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public static Reservation hold(UUID orderId, Sku sku, Quantity quantity, Instant expiresAt) {
        return new Reservation(orderId, sku, quantity, ReservationStatus.RESERVED, expiresAt);
    }

    /** @return true when the state changed and stock must move; false when it was already in that state. */
    public boolean moveTo(ReservationStatus target) {
        if (status == target) return false;
        if (!status.canMoveTo(target))
            throw new DomainException("ILLEGAL_STATE", "Reservation of %s for order %s is %s, cannot become %s"
                    .formatted(sku, orderId, status, target));
        status = target;
        return true;
    }

    public boolean isDue(Instant now) {
        return status == ReservationStatus.RESERVED && !expiresAt.isAfter(now);
    }

    public UUID orderId() { return orderId; }

    public Sku sku() { return sku; }

    public Quantity quantity() { return quantity; }

    public ReservationStatus status() { return status; }

    public Instant expiresAt() { return expiresAt; }
}
