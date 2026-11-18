package com.jkbootup.ecommerce.review.adapter.messaging;

import com.jkbootup.ecommerce.review.application.ReviewService;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Records purchases for the verified badge. Recording is an upsert, so duplicate deliveries need no dedupe. */
public class OrderConfirmedListener {

    /** A message that can never be processed: dead-lettered at once. */
    public static class BadEventException extends RuntimeException {
        public BadEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ReviewService service;
    private final JsonMapper json;

    public OrderConfirmedListener(ReviewService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(id = "review.order-confirmed", topics = "order.events", groupId = "review-service")
    public void onOrderEvent(String message) {
        UUID customer;
        List<String> skus = new ArrayList<>();
        Instant at;
        try {
            JsonNode e = json.readTree(message);
            if (!"order.OrderConfirmed".equals(e.path("type").asString())) return;
            customer = UUID.fromString(e.path("payload").path("customerId").asString());
            for (JsonNode line : e.path("payload").path("lines")) skus.add(line.path("sku").asString());
            at = e.hasNonNull("occurredAt") ? Instant.parse(e.path("occurredAt").asString()) : Instant.now();
        } catch (RuntimeException ex) {
            throw new BadEventException("Unreadable OrderConfirmed: " + ex.getMessage(), ex);
        }
        service.purchased(customer, skus, at);
    }
}
