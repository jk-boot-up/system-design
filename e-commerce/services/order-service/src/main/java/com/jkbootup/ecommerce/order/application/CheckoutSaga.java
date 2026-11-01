package com.jkbootup.ecommerce.order.application;

import com.jkbootup.ecommerce.order.application.CheckoutResult.Outcome;
import com.jkbootup.ecommerce.order.application.port.CartPort;
import com.jkbootup.ecommerce.order.application.port.IdGenerator;
import com.jkbootup.ecommerce.order.application.port.InventoryPort;
import com.jkbootup.ecommerce.order.application.port.OrderRepository;
import com.jkbootup.ecommerce.order.application.port.PaymentPort;
import com.jkbootup.ecommerce.order.application.port.Transactions;
import com.jkbootup.ecommerce.order.domain.CancelReason;
import com.jkbootup.ecommerce.order.domain.DomainException;
import com.jkbootup.ecommerce.order.domain.Order;
import com.jkbootup.ecommerce.order.domain.OrderLine;
import com.jkbootup.ecommerce.order.domain.OrderStatus;
import com.jkbootup.ecommerce.order.domain.SagaStep;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Checkout and cancellation saga (process manager). Calls inventory and payment synchronously, persists every step on
 * the order, and compensates on failure. Before money is authorised a failure moves the saga back (cancel); after
 * authorisation it moves forward (commit, capture, confirm), and the recovery job finishes whatever was interrupted.
 */
public class CheckoutSaga {

    private final OrderRepository orders;
    private final CartPort cart;
    private final InventoryPort inventory;
    private final PaymentPort payment;
    private final Transactions tx;
    private final IdGenerator ids;
    private final Clock clock;

    public CheckoutSaga(OrderRepository orders, CartPort cart, InventoryPort inventory, PaymentPort payment,
                        Transactions tx, IdGenerator ids, Clock clock) {
        this.orders = orders;
        this.cart = cart;
        this.inventory = inventory;
        this.payment = payment;
        this.tx = tx;
        this.ids = ids;
        this.clock = clock;
    }

    public CheckoutResult place(PlaceOrderCommand cmd) {
        var previous = orders.findByIdempotencyKey(cmd.customerId(), cmd.idempotencyKey());
        if (previous.isPresent()) return replay(previous.get());

        List<OrderLine> lines = switch (cart.checkoutSnapshot(cmd.customerId())) {
            case CartPort.Snapshot.Unavailable u ->
                    throw new DomainException("DEPENDENCY_UNAVAILABLE", "Cart could not be read: " + u.reason());
            case CartPort.Snapshot.Ready r -> toLines(r.lines());
        };

        // millisecond precision: the UUIDv7 id carries the same instant, which is the partition key for lookups by id
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Order order = Order.place(ids.newId(now), now, cmd.customerId(), cmd.customerEmail(), lines);
        boolean inserted = tx.inTransaction(() -> orders.insert(order, cmd.idempotencyKey()));
        if (!inserted) {                                    // same key used concurrently: return that order
            return replay(orders.findByIdempotencyKey(cmd.customerId(), cmd.idempotencyKey()).orElseThrow());
        }
        return runForward(order, cmd.paymentToken());
    }

    /** Resumes an interrupted saga from its recorded step (used by the recovery job). */
    public void resume(Order order) {
        switch (order.sagaStep()) {
            case STARTED, STOCK_RESERVED, COMPENSATING -> {
                payment.voidAuthorization(order.id());
                inventory.release(order.id());
                cancel(order, CancelReason.CHECKOUT_ABANDONED);
            }
            case PAYMENT_AUTHORIZED, STOCK_COMMITTED, PAYMENT_CAPTURED -> continueAfterAuthorization(order);
            case REFUNDING -> finishCancellation(order);
            case COMPLETED, COMPENSATED -> { /* nothing left to do */ }
        }
    }

    /** Customer or staff cancels a confirmed order: refund, return stock, cancel. */
    public Order cancelByCustomer(UUID orderId, UUID requestedBy, boolean staff) {
        Order order = orders.findById(orderId).orElseThrow(() -> new DomainException("NOT_FOUND", "Order " + orderId + " not found"));
        if (!staff && !order.isOwnedBy(requestedBy)) throw new DomainException("NOT_FOUND", "Order " + orderId + " not found");
        if (order.status() == OrderStatus.CANCELLED) return order;
        order.requestCancellation(clock.instant());
        save(order);
        return finishCancellation(order);
    }

    private CheckoutResult runForward(Order order, String paymentToken) {
        switch (inventory.reserve(order.id(), order.lines())) {
            case InventoryPort.Reservation.OutOfStock o -> {
                cancel(order, CancelReason.OUT_OF_STOCK);
                return new CheckoutResult(order, Outcome.OUT_OF_STOCK, o.sku(), false);
            }
            case InventoryPort.Reservation.Unavailable u -> {
                inventory.release(order.id());            // in case the reservation happened but the answer was lost
                cancel(order, CancelReason.INVENTORY_UNAVAILABLE);
                return new CheckoutResult(order, Outcome.DEPENDENCY_UNAVAILABLE, u.reason(), false);
            }
            case InventoryPort.Reservation.Reserved r -> step(order, SagaStep.STOCK_RESERVED);
        }

        switch (payment.authorize(order.id(), order.total(), paymentToken)) {
            case PaymentPort.Result.Declined d -> {
                compensateBeforeCapture(order, CancelReason.PAYMENT_DECLINED, false);
                return new CheckoutResult(order, Outcome.PAYMENT_DECLINED, d.reason(), false);
            }
            case PaymentPort.Result.Unknown u -> {
                compensateBeforeCapture(order, CancelReason.PAYMENT_UNKNOWN, true);
                return new CheckoutResult(order, Outcome.PAYMENT_UNKNOWN, u.reason(), false);
            }
            case PaymentPort.Result.Unavailable u -> {
                compensateBeforeCapture(order, CancelReason.PAYMENT_UNKNOWN, true);
                return new CheckoutResult(order, Outcome.DEPENDENCY_UNAVAILABLE, u.reason(), false);
            }
            case PaymentPort.Result.Approved a -> step(order, SagaStep.PAYMENT_AUTHORIZED);
        }
        return continueAfterAuthorization(order);
    }

    /** Money is authorised: move forward. Temporary failures leave the order PENDING for the recovery job. */
    private CheckoutResult continueAfterAuthorization(Order order) {
        if (order.sagaStep() == SagaStep.PAYMENT_AUTHORIZED) {
            switch (inventory.commit(order.id())) {
                case InventoryPort.Step.Done d -> step(order, SagaStep.STOCK_COMMITTED);
                case InventoryPort.Step.Unavailable u -> {
                    return new CheckoutResult(order, Outcome.IN_PROGRESS, "inventory: " + u.reason(), false);
                }
                case InventoryPort.Step.Rejected r -> {        // e.g. RESERVATION_EXPIRED: the stock is gone
                    payment.voidAuthorization(order.id());
                    cancel(order, CancelReason.RESERVATION_EXPIRED);
                    return new CheckoutResult(order, Outcome.FAILED, r.code(), false);
                }
            }
        }
        if (order.sagaStep() == SagaStep.STOCK_COMMITTED) {
            switch (payment.capture(order.id())) {
                case PaymentPort.Result.Approved a -> step(order, SagaStep.PAYMENT_CAPTURED);
                case PaymentPort.Result.Unknown u -> {
                    return new CheckoutResult(order, Outcome.IN_PROGRESS, "payment: " + u.reason(), false);
                }
                case PaymentPort.Result.Unavailable u -> {
                    return new CheckoutResult(order, Outcome.IN_PROGRESS, "payment: " + u.reason(), false);
                }
                case PaymentPort.Result.Declined d -> {
                    payment.voidAuthorization(order.id());
                    inventory.returnStock(order.id());
                    cancel(order, CancelReason.CAPTURE_FAILED);
                    return new CheckoutResult(order, Outcome.FAILED, d.reason(), false);
                }
            }
        }
        if (order.sagaStep() == SagaStep.PAYMENT_CAPTURED) {
            order.confirm(clock.instant());
            save(order);
        }
        return new CheckoutResult(order, Outcome.CONFIRMED, null, false);
    }

    private Order finishCancellation(Order order) {
        if (payment.refund(order.id()) instanceof PaymentPort.Result.Approved
                && inventory.returnStock(order.id()) instanceof InventoryPort.Step.Done) {
            cancel(order, CancelReason.CUSTOMER_REQUEST);
        }
        return order;                                        // otherwise stays CANCELLING; recovery retries
    }

    private void compensateBeforeCapture(Order order, CancelReason reason, boolean voidPayment) {
        step(order, SagaStep.COMPENSATING);
        if (voidPayment) payment.voidAuthorization(order.id());
        inventory.release(order.id());
        cancel(order, reason);
    }

    private void step(Order order, SagaStep step) {
        order.recordStep(step, clock.instant());
        save(order);
    }

    private void cancel(Order order, CancelReason reason) {
        order.cancel(reason, clock.instant());
        save(order);
    }

    private void save(Order order) {
        tx.inTransaction(() -> {
            orders.update(order);
            return null;
        });
    }

    private CheckoutResult replay(Order order) {
        Outcome outcome = switch (order.status()) {
            case CONFIRMED, SHIPPED, DELIVERED, CANCELLING -> Outcome.CONFIRMED;
            case PENDING -> Outcome.IN_PROGRESS;
            case CANCELLED -> switch (order.cancelReason()) {
                case OUT_OF_STOCK -> Outcome.OUT_OF_STOCK;
                case PAYMENT_DECLINED -> Outcome.PAYMENT_DECLINED;
                case PAYMENT_UNKNOWN -> Outcome.PAYMENT_UNKNOWN;
                case INVENTORY_UNAVAILABLE -> Outcome.DEPENDENCY_UNAVAILABLE;
                case null, default -> Outcome.FAILED;
            };
        };
        return new CheckoutResult(order, outcome, null, true);
    }

    private static List<OrderLine> toLines(List<CartPort.Line> cartLines) {
        if (cartLines.isEmpty()) throw new DomainException("CART_NOT_READY", "The cart is empty");
        List<OrderLine> lines = new ArrayList<>();
        int n = 1;
        for (CartPort.Line l : cartLines) {
            if (!l.available()) throw new DomainException("CART_NOT_READY", l.sku() + " is not available");
            lines.add(new OrderLine(n++, l.sku(), l.name(), l.quantity(), l.unitPrice()));
        }
        return lines;
    }
}
