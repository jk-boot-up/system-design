package com.jkbootup.ecommerce.cart.adapter.messaging;

import com.jkbootup.ecommerce.cart.application.CartService;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** Empties a customer's cart once their order is confirmed. */
public class OrderConfirmedListener {

    private final CartService service;
    private final JsonMapper json;

    public OrderConfirmedListener(CartService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(id = "cart.order-confirmed", topics = "order.events", groupId = "cart-service")
    public void onOrderEvent(String message) {
        JsonNode event = json.readTree(message);
        if (!"order.OrderConfirmed".equals(event.path("type").asString())) return;
        service.orderConfirmed(event.path("eventId").asString(), UUID.fromString(event.path("payload").path("customerId").asString()));
    }
}
