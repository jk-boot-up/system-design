package com.jkbootup.ecommerce.order.adapter.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void carriesItsCreationTimeAndVersion() {
        Instant at = Instant.parse("2026-11-01T10:15:30.123Z");
        UUID id = UuidV7.generate(at);
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(UuidV7.timestampOf(id)).isEqualTo(at);
    }

    @Test
    void sortsByCreationTime() {
        UUID earlier = UuidV7.generate(Instant.parse("2026-11-01T10:00:00.000Z"));
        UUID later = UuidV7.generate(Instant.parse("2026-11-01T10:00:00.001Z"));
        assertThat(earlier.toString()).isLessThan(later.toString());
    }
}
