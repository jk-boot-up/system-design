package com.jkbootup.ecommerce.cart.application.port;

/** Idempotent-consumer ledger with expiry. */
public interface ProcessedEvents {

    /** @return true the first time this event id is seen. */
    boolean firstTime(String eventId);
}
