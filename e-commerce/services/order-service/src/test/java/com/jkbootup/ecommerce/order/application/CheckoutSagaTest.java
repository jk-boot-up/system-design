package com.jkbootup.ecommerce.order.application;

import com.jkbootup.ecommerce.order.application.CheckoutResult.Outcome;
import com.jkbootup.ecommerce.order.application.port.CartPort;
import com.jkbootup.ecommerce.order.application.port.InventoryPort;
import com.jkbootup.ecommerce.order.application.port.PaymentPort;
import com.jkbootup.ecommerce.order.domain.CancelReason;
import com.jkbootup.ecommerce.order.domain.DomainException;
import com.jkbootup.ecommerce.order.domain.Money;
import com.jkbootup.ecommerce.order.domain.OrderEvent;
import com.jkbootup.ecommerce.order.domain.OrderStatus;
import com.jkbootup.ecommerce.order.domain.SagaStep;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutSagaTest {

    private final Fakes f = new Fakes();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    private final CheckoutSaga saga = new CheckoutSaga(f.repository, f.cartPort, f.inventory, f.payment, f.tx, f.ids, clock);
    private final UUID customer = UUID.randomUUID();

    private CheckoutResult checkout(String key) {
        return saga.place(new PlaceOrderCommand(customer, "ada@example.com", key, "tok_ok"));
    }

    @Test
    void happyPathConfirmsTheOrder() {
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(r.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(f.calls).containsExactly("cart.snapshot", "inventory.reserve", "payment.authorize", "inventory.commit", "payment.capture");
        assertThat(f.published).extracting(OrderEvent::type).containsExactly("order.OrderPlaced", "order.OrderConfirmed");
        assertThat(r.order().total()).isEqualTo(Money.of("179.80", "EUR"));
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameOrderWithoutCallingAnything() {
        CheckoutResult first = checkout("k1");
        f.calls.clear();
        CheckoutResult second = checkout("k1");
        assertThat(second.replay()).isTrue();
        assertThat(second.order().id()).isEqualTo(first.order().id());
        assertThat(f.calls).isEmpty();
    }

    @Test
    void outOfStockCancelsWithoutTouchingPayment() {
        f.reserve.add(new InventoryPort.Reservation.OutOfStock("SKU-1"));
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.OUT_OF_STOCK);
        assertThat(r.order().cancelReason()).isEqualTo(CancelReason.OUT_OF_STOCK);
        assertThat(f.calls).doesNotContain("payment.authorize");
        assertThat(f.published).extracting(OrderEvent::type).containsExactly("order.OrderPlaced", "order.OrderCancelled");
    }

    @Test
    void declinedPaymentReleasesStock() {
        f.authorize.add(new PaymentPort.Result.Declined("INSUFFICIENT_FUNDS"));
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.PAYMENT_DECLINED);
        assertThat(f.calls).endsWith("payment.authorize", "inventory.release").doesNotContain("payment.void");
        assertThat(r.order().sagaStep()).isEqualTo(SagaStep.COMPENSATED);
    }

    @Test
    void unknownPaymentOutcomeVoidsAndReleases() {
        f.authorize.add(new PaymentPort.Result.Unknown("TIMEOUT"));
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.PAYMENT_UNKNOWN);
        assertThat(f.calls).endsWith("payment.authorize", "payment.void", "inventory.release");
        assertThat(r.order().cancelReason()).isEqualTo(CancelReason.PAYMENT_UNKNOWN);
    }

    @Test
    void inventoryOutageReleasesBestEffortAndCancels() {
        f.reserve.add(new InventoryPort.Reservation.Unavailable("CIRCUIT_OPEN"));
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.DEPENDENCY_UNAVAILABLE);
        assertThat(f.calls).endsWith("inventory.reserve", "inventory.release");
    }

    @Test
    void outageAfterAuthorisationLeavesThePendingOrderForRecoveryWhichFinishesIt() {
        f.commit.add(new InventoryPort.Step.Unavailable("TIMEOUT"));
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.IN_PROGRESS);
        assertThat(r.order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(r.order().sagaStep()).isEqualTo(SagaStep.PAYMENT_AUTHORIZED);

        new SagaRecovery(f.repository, saga, clock, Duration.ofSeconds(30)).recoverBatch(10);
        assertThat(f.orders.get(r.order().id()).status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void expiredReservationVoidsThePayment() {
        f.commit.add(new InventoryPort.Step.Rejected("RESERVATION_EXPIRED"));
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(f.calls).endsWith("inventory.commit", "payment.void");
        assertThat(r.order().cancelReason()).isEqualTo(CancelReason.RESERVATION_EXPIRED);
    }

    @Test
    void declinedCaptureVoidsAndReturnsStock() {
        f.capture.add(new PaymentPort.Result.Declined("EXPIRED_AUTHORIZATION"));
        CheckoutResult r = checkout("k1");
        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(f.calls).endsWith("payment.capture", "payment.void", "inventory.return");
        assertThat(r.order().cancelReason()).isEqualTo(CancelReason.CAPTURE_FAILED);
    }

    @Test
    void recoveryCancelsAnOrderAbandonedBeforePayment() {
        // the service crashed right after reserving stock: the order is PENDING at STOCK_RESERVED
        var crashed = new com.jkbootup.ecommerce.order.domain.Order(UUID.randomUUID(), customer, "ada@example.com",
                List.of(new com.jkbootup.ecommerce.order.domain.OrderLine(1, "SKU-1", "Trail Runner", 1, Money.of("89.90", "EUR"))),
                OrderStatus.PENDING, SagaStep.STOCK_RESERVED, null, 0, clock.instant(), clock.instant());
        f.orders.put(crashed.id(), crashed);

        new SagaRecovery(f.repository, saga, clock, Duration.ofSeconds(30)).recoverBatch(10);

        assertThat(f.calls).containsExactly("payment.void", "inventory.release");
        assertThat(f.orders.get(crashed.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(f.orders.get(crashed.id()).cancelReason()).isEqualTo(CancelReason.CHECKOUT_ABANDONED);
    }

    @Test
    void emptyOrUnavailableCartIsRejected() {
        f.cart = new CartPort.Snapshot.Ready(List.of());
        assertThatThrownBy(() -> checkout("k1")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("CART_NOT_READY"));
        f.cart = new CartPort.Snapshot.Unavailable("CIRCUIT_OPEN");
        assertThatThrownBy(() -> checkout("k2")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void customerCancellationRefundsAndReturnsStock() {
        CheckoutResult r = checkout("k1");
        f.calls.clear();
        var cancelled = saga.cancelByCustomer(r.order().id(), customer, false);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CUSTOMER_REQUEST);
        assertThat(f.calls).containsExactly("payment.refund", "inventory.return");
    }

    @Test
    void failedRefundLeavesTheOrderCancellingForRecovery() {
        CheckoutResult r = checkout("k1");
        f.refund.add(new PaymentPort.Result.Unavailable("CIRCUIT_OPEN"));
        var order = saga.cancelByCustomer(r.order().id(), customer, false);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLING);
        new SagaRecovery(f.repository, saga, clock, Duration.ofSeconds(30)).recoverBatch(10);
        assertThat(f.orders.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void otherCustomersCannotSeeOrCancelAnOrder() {
        CheckoutResult r = checkout("k1");
        assertThatThrownBy(() -> saga.cancelByCustomer(r.order().id(), UUID.randomUUID(), false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThat(saga.cancelByCustomer(r.order().id(), UUID.randomUUID(), true).status()).isEqualTo(OrderStatus.CANCELLED);
    }
}
