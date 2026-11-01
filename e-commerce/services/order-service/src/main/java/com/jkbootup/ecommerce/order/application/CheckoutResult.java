package com.jkbootup.ecommerce.order.application;

import com.jkbootup.ecommerce.order.domain.Order;

/**
 * Outcome of a checkout attempt. {@code replay} is true when the idempotency key had been used before and the stored
 * order is returned unchanged.
 */
public record CheckoutResult(Order order, Outcome outcome, String detail, boolean replay) {

    public enum Outcome { CONFIRMED, IN_PROGRESS, OUT_OF_STOCK, PAYMENT_DECLINED, PAYMENT_UNKNOWN, DEPENDENCY_UNAVAILABLE, FAILED }
}
