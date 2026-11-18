package com.jkbootup.ecommerce.review.domain;

import java.util.List;

public record ReviewPage(RatingSummary summary, List<Review> items, String nextCursor) {
}
