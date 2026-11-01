package com.jkbootup.ecommerce.order.domain;

import java.util.EnumSet;
import java.util.Set;

/** Order lifecycle. Only the transitions listed here are legal. */
public enum OrderStatus {
    PENDING, CONFIRMED, CANCELLING, CANCELLED, SHIPPED, DELIVERED;

    private Set<OrderStatus> next() {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> EnumSet.of(CANCELLING, SHIPPED);
            case CANCELLING -> EnumSet.of(CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED);
            case CANCELLED, DELIVERED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canMoveTo(OrderStatus target) {
        return next().contains(target);
    }

    public boolean isFinal() {
        return this == CANCELLED || this == DELIVERED;
    }
}
