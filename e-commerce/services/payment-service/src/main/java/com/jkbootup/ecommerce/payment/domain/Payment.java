package com.jkbootup.ecommerce.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment per order. A void may arrive before, during or after authorisation; it always wins: a payment with
 * {@code voidRequested} is never captured, and a late authorisation is voided as soon as it is seen.
 */
public final class Payment {

    private final UUID id;
    private final UUID orderId;
    private final Money amount;            // null only for a void that arrived before any authorisation
    private final String paymentToken;
    private PaymentStatus status;
    private String providerRef;
    private String failureReason;
    private boolean voidRequested;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    public Payment(UUID id, UUID orderId, Money amount, String paymentToken, PaymentStatus status, String providerRef,
                   String failureReason, boolean voidRequested, long version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.orderId = Objects.requireNonNull(orderId);
        this.amount = amount;
        this.paymentToken = paymentToken;
        this.status = Objects.requireNonNull(status);
        this.providerRef = providerRef;
        this.failureReason = failureReason;
        this.voidRequested = voidRequested;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Payment authorizing(UUID id, UUID orderId, Money amount, String token, Instant now) {
        if (amount == null || !amount.isPositive()) throw new DomainException("INVALID_AMOUNT", "Amount must be positive");
        if (token == null || token.isBlank()) throw new DomainException("INVALID_TOKEN", "A payment token is required");
        return new Payment(id, orderId, amount, token, PaymentStatus.AUTHORIZING, null, null, false, 0, now, now);
    }

    /** A void for an order that has no payment yet: blocks any later authorisation for that order. */
    public static Payment voidedBeforeAuthorization(UUID id, UUID orderId, Instant now) {
        return new Payment(id, orderId, null, null, PaymentStatus.VOIDED, null, "VOIDED_BEFORE_AUTHORIZATION", true, 0, now, now);
    }

    public void authorized(String ref, Instant now) { this.providerRef = ref; move(PaymentStatus.AUTHORIZED, now); }

    public void declined(String reason, Instant now) { this.failureReason = reason; move(PaymentStatus.DECLINED, now); }

    public void unknown(String reason, Instant now) { this.failureReason = reason; move(PaymentStatus.UNKNOWN, now); }

    public void captured(Instant now) {
        if (voidRequested) throw new DomainException("VOID_REQUESTED", "Payment for order " + orderId + " must not be captured");
        move(PaymentStatus.CAPTURED, now);
    }

    public void voided(Instant now) { move(PaymentStatus.VOIDED, now); }

    public void refunded(Instant now) { move(PaymentStatus.REFUNDED, now); }

    public void requestVoid(Instant now) {
        this.voidRequested = true;
        this.updatedAt = now;
    }

    private void move(PaymentStatus target, Instant now) {
        if (status == target) return;
        if (!status.canMoveTo(target))
            throw new DomainException("ILLEGAL_STATE", "Payment for order " + orderId + " is " + status + ", cannot become " + target);
        status = target;
        updatedAt = now;
    }

    public void versionWritten(long v) { this.version = v; }

    public UUID id() { return id; }

    public UUID orderId() { return orderId; }

    public Money amount() { return amount; }

    public String paymentToken() { return paymentToken; }

    public PaymentStatus status() { return status; }

    public String providerRef() { return providerRef; }

    public String failureReason() { return failureReason; }

    public boolean voidRequested() { return voidRequested; }

    public long version() { return version; }

    public Instant createdAt() { return createdAt; }

    public Instant updatedAt() { return updatedAt; }
}
