package com.jkbootup.ecommerce.inventory.application.port;

import com.jkbootup.ecommerce.inventory.domain.StockLevel;

/** Records StockChanged events. Implemented by the outbox, so events are stored in the same transaction. */
public interface StockEvents {

    void stockChanged(StockLevel level);
}
