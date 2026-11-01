package com.jkbootup.ecommerce.inventory.adapter.messaging;

import com.jkbootup.ecommerce.inventory.adapter.persistence.ProcessedEvents;
import com.jkbootup.ecommerce.inventory.application.ReservationService;
import com.jkbootup.ecommerce.inventory.application.port.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Safety net: when an order is cancelled, release any stock still held for it. Normally order-service has already
 * released it; release is idempotent, and the processed-events ledger skips duplicate deliveries.
 */
public class OrderCancelledListener {

    static final String CONSUMER = "inventory.order-cancelled";
    private static final Logger log = LoggerFactory.getLogger(OrderCancelledListener.class);

    private final ReservationService service;
    private final ProcessedEvents processed;
    private final Transactions tx;
    private final JsonMapper json;

    public OrderCancelledListener(ReservationService service, ProcessedEvents processed, Transactions tx, JsonMapper json) {
        this.service = service;
        this.processed = processed;
        this.tx = tx;
        this.json = json;
    }

    @KafkaListener(id = CONSUMER, topics = "order.events", groupId = "inventory-service")
    public void onOrderEvent(String message) {
        JsonNode event = json.readTree(message);
        if (!"order.OrderCancelled".equals(event.path("type").asString())) return;
        UUID eventId = UUID.fromString(event.path("eventId").asString());
        UUID orderId = UUID.fromString(event.path("payload").path("orderId").asString());
        tx.inTransaction(() -> {
            if (processed.firstTime(CONSUMER, eventId)) {
                service.release(orderId);
                log.info("Released any held stock for cancelled order {}", orderId);
            }
            return null;
        });
    }
}
