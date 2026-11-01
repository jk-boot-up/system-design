package com.jkbootup.ecommerce.payment.adapter.persistence;



import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/** UUIDv7 (RFC 9562): 48-bit Unix milliseconds, version 7, then random bits. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static UUID generate(Instant at) {
        long msb = (at.toEpochMilli() << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /** The creation time stored in a UUIDv7, used to prune order partitions on lookups by id. */
    public static Instant timestampOf(UUID id) {
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}
