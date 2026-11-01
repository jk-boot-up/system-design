package com.jkbootup.ecommerce.inventory.adapter.persistence;

import java.security.SecureRandom;
import java.util.UUID;

/** UUIDv7 identifiers: 48-bit Unix milliseconds, version 7, then random bits (RFC 9562). */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long msb = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
