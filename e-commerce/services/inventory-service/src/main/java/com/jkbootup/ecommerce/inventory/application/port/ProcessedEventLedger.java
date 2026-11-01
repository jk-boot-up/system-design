package com.jkbootup.ecommerce.inventory.application.port;

import java.util.UUID;

/** Idempotent-consumer ledger: remembers which events each consumer has already applied. */
public interface ProcessedEventLedger {

    /** @return true the first time this consumer sees the event; must run inside the consumer's transaction. */
    boolean firstTime(String consumer, UUID eventId);
}
