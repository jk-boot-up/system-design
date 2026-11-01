package com.jkbootup.ecommerce.inventory.domain;

import java.time.Instant;

/** Result of reserving all lines of an order. Sealed so callers must handle every case. */
public sealed interface ReserveOutcome {

    record Reserved(Instant expiresAt) implements ReserveOutcome {}

    record OutOfStock(Sku sku) implements ReserveOutcome {}
}
