package com.jkbootup.ecommerce.review.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Running totals kept in counters; the average is derived, never stored. */
public record RatingSummary(long count, long total) {

    public static final RatingSummary EMPTY = new RatingSummary(0, 0);

    /** Average stars to one decimal, or null when there are no reviews. */
    public BigDecimal average() {
        if (count == 0) return null;
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }
}
