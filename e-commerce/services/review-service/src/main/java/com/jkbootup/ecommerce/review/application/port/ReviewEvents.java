package com.jkbootup.ecommerce.review.application.port;

import com.jkbootup.ecommerce.review.domain.Review;

/** Publishes review.ReviewPosted. */
public interface ReviewEvents {

    void reviewPosted(Review review);
}
