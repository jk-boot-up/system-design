package com.jkbootup.ecommerce.order.domain;

/** How far the checkout (or cancellation) saga has got; persisted so it can always be resumed. */
public enum SagaStep {
    STARTED, STOCK_RESERVED, PAYMENT_AUTHORIZED, STOCK_COMMITTED, PAYMENT_CAPTURED, COMPLETED,
    COMPENSATING, COMPENSATED, REFUNDING;

    /** Once money is authorised the saga moves forward on recovery; before that it moves back. */
    public boolean movesForwardOnRecovery() {
        return this == PAYMENT_AUTHORIZED || this == STOCK_COMMITTED || this == PAYMENT_CAPTURED || this == REFUNDING;
    }
}
