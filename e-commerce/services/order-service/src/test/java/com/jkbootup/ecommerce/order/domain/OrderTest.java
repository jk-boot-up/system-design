package com.jkbootup.ecommerce.order.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static Order newOrder() {
        return Order.place(UUID.randomUUID(), NOW, UUID.randomUUID(), "ada@example.com", List.of(
                new OrderLine(1, "SKU-1", "Shoes", 2, Money.of("89.90", "EUR")),
                new OrderLine(2, "SKU-2", "Socks", 3, Money.of("4.99", "EUR"))));
    }

    @Test
    void placingTotalsTheLinesAndRecordsOrderPlaced() {
        Order order = newOrder();
        assertThat(order.total()).isEqualTo(Money.of("194.77", "EUR"));
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.pullEvents()).singleElement().isInstanceOf(OrderEvent.OrderPlaced.class);
        assertThat(order.pullEvents()).isEmpty();
    }

    @Test
    void confirmRecordsOrderConfirmed() {
        Order order = newOrder();
        order.pullEvents();
        order.confirm(NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.sagaStep()).isEqualTo(SagaStep.COMPLETED);
        assertThat(order.pullEvents()).singleElement().isInstanceOf(OrderEvent.OrderConfirmed.class);
    }

    @Test
    void cancelIsIdempotentAndRecordsOneEvent() {
        Order order = newOrder();
        order.pullEvents();
        order.cancel(CancelReason.OUT_OF_STOCK, NOW);
        order.cancel(CancelReason.OUT_OF_STOCK, NOW);
        assertThat(order.pullEvents()).hasSize(1);
        assertThat(order.cancelReason()).isEqualTo(CancelReason.OUT_OF_STOCK);
    }

    @Test
    void onlyConfirmedOrdersCanBeCancelledByTheCustomer() {
        Order order = newOrder();
        assertThatThrownBy(() -> order.requestCancellation(NOW))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_CANCELLABLE"));
        order.confirm(NOW);
        order.requestCancellation(NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLING);
        assertThat(order.sagaStep()).isEqualTo(SagaStep.REFUNDING);
    }

    @Test
    void illegalTransitionsAreRejected() {
        Order order = newOrder();
        order.cancel(CancelReason.PAYMENT_DECLINED, NOW);
        assertThatThrownBy(() -> order.confirm(NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void moneyRulesHold() {
        assertThat(Money.of("0.105", "EUR").amount()).hasToString("0.10");     // banker's rounding
        assertThatThrownBy(() -> Money.of("1", "EUR").plus(Money.of("1", "USD"))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new OrderLine(1, "SKU-1", "x", 0, Money.of("1", "EUR"))).isInstanceOf(DomainException.class);
    }
}
