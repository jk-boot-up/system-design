package com.jkbootup.ecommerce.review.domain;

/**
 * CREATED: a new review. RESUMED: an earlier attempt by the same customer stopped half-way (a crash between the steps);
 * this attempt finished it and returns that review. Both answer 201. A finished earlier review is ALREADY_REVIEWED (409).
 */
public record PostOutcome(Review review, boolean resumed) {
}
