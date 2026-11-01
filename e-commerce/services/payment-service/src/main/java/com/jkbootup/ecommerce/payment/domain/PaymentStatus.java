package com.jkbootup.ecommerce.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/** Payment lifecycle (State pattern on an enum). */
public enum PaymentStatus {
    AUTHORIZING, AUTHORIZED, CAPTURED, DECLINED, UNKNOWN, VOIDED, REFUNDED;

    private Set<PaymentStatus> next() {
        return switch (this) {
            case AUTHORIZING -> EnumSet.of(AUTHORIZED, DECLINED, UNKNOWN, VOIDED);
            case UNKNOWN -> EnumSet.of(AUTHORIZED, DECLINED, VOIDED);
            case AUTHORIZED -> EnumSet.of(CAPTURED, VOIDED);
            case CAPTURED -> EnumSet.of(REFUNDED);
            case DECLINED, VOIDED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canMoveTo(PaymentStatus target) {
        return next().contains(target);
    }

    /** The outcome is not settled yet: a provider call is in flight or its result was lost. */
    public boolean isUnsettled() {
        return this == AUTHORIZING || this == UNKNOWN;
    }
}
