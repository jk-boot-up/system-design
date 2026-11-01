package com.jkbootup.ecommerce.order.application.port;

import java.time.Instant;
import java.util.UUID;

/** Time-ordered identifiers (UUIDv7); the creation time can be read back from the id. */
public interface IdGenerator {

    UUID newId(Instant at);
}
