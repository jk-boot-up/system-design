package com.jkbootup.ecommerce.review.adapter.messaging;

import com.jkbootup.ecommerce.review.application.port.ReviewEvents;
import com.jkbootup.ecommerce.review.domain.Ids;
import com.jkbootup.ecommerce.review.domain.Review;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes review.ReviewPosted after the review is stored. Cassandra has no transactions to hold an outbox row, so
 * this is best effort: a failed send is logged and counted, and the review itself is not affected. Its only planned
 * consumer is a rating cache, which can be rebuilt from rating_summary_by_product.
 */
public class KafkaReviewEvents implements ReviewEvents {

    public static final String TOPIC = "review.events";
    private static final Logger log = LoggerFactory.getLogger(KafkaReviewEvents.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public KafkaReviewEvents(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void reviewPosted(Review r) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", Ids.newId().toString());
        envelope.put("type", "review.ReviewPosted");
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", r.sku());
        envelope.put("payload", Map.of("sku", r.sku(), "reviewId", r.id().toString(), "rating", r.rating(), "verified", r.verified()));
        try {
            kafka.send(TOPIC, r.sku(), json.writeValueAsString(envelope)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("ReviewPosted for {} not published: {}", r.id(), e.getMessage());
        }
    }
}
