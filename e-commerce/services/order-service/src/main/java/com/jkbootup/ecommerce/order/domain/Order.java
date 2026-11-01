package com.jkbootup.ecommerce.order.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Order aggregate. Enforces the status state machine and records the saga's progress and domain events.
 * Events are pulled by the repository and stored in the outbox in the same transaction.
 */
public final class Order {

    private final UUID id;
    private final UUID customerId;
    private final String customerEmail;
    private final List<OrderLine> lines;
    private final Money total;
    private final Instant createdAt;
    private OrderStatus status;
    private SagaStep sagaStep;
    private CancelReason cancelReason;
    private long version;
    private Instant updatedAt;
    private final List<OrderEvent> pendingEvents = new ArrayList<>();

    public Order(UUID id, UUID customerId, String customerEmail, List<OrderLine> lines, OrderStatus status, SagaStep sagaStep,
                 CancelReason cancelReason, long version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.customerId = Objects.requireNonNull(customerId);
        this.customerEmail = Objects.requireNonNull(customerEmail);
        if (lines.isEmpty()) throw new DomainException("EMPTY_ORDER", "An order needs at least one line");
        this.lines = List.copyOf(lines);
        this.total = lines.stream().map(OrderLine::lineTotal).reduce(Money::plus).orElseThrow();
        this.status = status;
        this.sagaStep = sagaStep;
        this.cancelReason = cancelReason;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Creates a new PENDING order and records OrderPlaced. */
    public static Order place(UUID id, Instant createdAt, UUID customerId, String customerEmail, List<OrderLine> lines) {
        Order order = new Order(id, customerId, customerEmail, lines, OrderStatus.PENDING, SagaStep.STARTED, null, 0,
                createdAt, createdAt);
        if (!order.total.isPositive()) throw new DomainException("INVALID_TOTAL", "Order total must be positive");
        order.pendingEvents.add(new OrderEvent.OrderPlaced(id, customerId, order.total, order.lines));
        return order;
    }

    public void recordStep(SagaStep step, Instant now) {
        this.sagaStep = step;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        moveTo(OrderStatus.CONFIRMED, now);
        sagaStep = SagaStep.COMPLETED;
        pendingEvents.add(new OrderEvent.OrderConfirmed(id, customerId, customerEmail, total, lines));
    }

    /** Cancels a PENDING order (checkout failed) or finishes cancelling a CANCELLING one. Idempotent. */
    public void cancel(CancelReason reason, Instant now) {
        if (status == OrderStatus.CANCELLED) return;
        moveTo(OrderStatus.CANCELLED, now);
        this.cancelReason = reason;
        this.sagaStep = SagaStep.COMPENSATED;
        pendingEvents.add(new OrderEvent.OrderCancelled(id, customerId, customerEmail, reason, total));
    }

    /** Customer or staff asks to cancel a confirmed order; refund and stock return follow. */
    public void requestCancellation(Instant now) {
        if (status == OrderStatus.CANCELLING || status == OrderStatus.CANCELLED) return;
        if (!isCancellable())
            throw new DomainException("NOT_CANCELLABLE", "Order " + id + " is " + status + " and can no longer be cancelled");
        moveTo(OrderStatus.CANCELLING, now);
        this.sagaStep = SagaStep.REFUNDING;
        this.cancelReason = CancelReason.CUSTOMER_REQUEST;
    }

    public boolean isCancellable() {
        return status == OrderStatus.CONFIRMED;
    }

    public boolean isOwnedBy(UUID customer) {
        return customerId.equals(customer);
    }

    /** Events recorded since the last call; the repository stores them in the outbox. */
    public List<OrderEvent> pullEvents() {
        List<OrderEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    private void moveTo(OrderStatus target, Instant now) {
        if (!status.canMoveTo(target))
            throw new DomainException("ILLEGAL_STATE", "Order " + id + " cannot move from " + status + " to " + target);
        status = target;
        updatedAt = now;
    }

    public void versionWritten(long newVersion) {
        this.version = newVersion;
    }

    public UUID id() { return id; }

    public UUID customerId() { return customerId; }

    public String customerEmail() { return customerEmail; }

    public List<OrderLine> lines() { return lines; }

    public Money total() { return total; }

    public OrderStatus status() { return status; }

    public SagaStep sagaStep() { return sagaStep; }

    public CancelReason cancelReason() { return cancelReason; }

    public long version() { return version; }

    public Instant createdAt() { return createdAt; }

    public Instant updatedAt() { return updatedAt; }
}
