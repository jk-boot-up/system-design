package com.jkbootup.ecommerce.review.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewTest {

    static Review review(int rating, String title, String body) {
        return new Review(UUID.randomUUID(), "SKU-1001", UUID.randomUUID(), rating, title, body, false, Instant.parse("2026-11-30T23:59:59Z"));
    }

    @Test
    void validReviewIsTrimmedAndBucketedByMonth() {
        Review r = review(5, "  Great grip ", " Wet rocks, no slips. ");
        assertThat(r.title()).isEqualTo("Great grip");
        assertThat(r.bucket()).isEqualTo("2026-11");
        assertThat(Review.bucketOf(Instant.parse("2026-12-01T00:00:00Z"))).isEqualTo("2026-12");
    }

    @Test
    void rulesAreEnforced() {
        assertThatThrownBy(() -> review(0, "x", "")).hasMessageContaining("1 to 5");
        assertThatThrownBy(() -> review(6, "x", "")).hasMessageContaining("1 to 5");
        assertThatThrownBy(() -> review(3, " ", "")).hasMessageContaining("title");
        assertThatThrownBy(() -> review(3, "x".repeat(121), "")).hasMessageContaining("120");
        assertThatThrownBy(() -> review(3, "x", "y".repeat(4001))).hasMessageContaining("4000");
    }

    @Test
    void averageIsDerivedToOneDecimal() {
        assertThat(RatingSummary.EMPTY.average()).isNull();
        assertThat(new RatingSummary(3, 13).average()).isEqualByComparingTo("4.3");
        assertThat(new RatingSummary(2, 9).average()).isEqualByComparingTo("4.5");
    }
}
