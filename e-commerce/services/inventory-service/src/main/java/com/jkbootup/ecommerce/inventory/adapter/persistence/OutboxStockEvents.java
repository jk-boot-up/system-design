package com.jkbootup.ecommerce.inventory.adapter.persistence;

import com.jkbootup.ecommerce.inventory.application.port.StockEvents;
import com.jkbootup.ecommerce.inventory.domain.StockLevel;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Transactional outbox: the event row is written in the same transaction as the stock change it describes. */
public class OutboxStockEvents implements StockEvents {

    public static final String TOPIC = "inventory.events";
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxStockEvents(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void stockChanged(StockLevel level) {
        UUID eventId = Ids.newId();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("type", "inventory.StockChanged");
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", level.sku().value());
        envelope.put("payload", Map.of("sku", level.sku().value(), "available", level.available(), "inStock", level.inStock()));
        jdbc.sql("""
                        insert into outbox (id, aggregate_type, aggregate_id, event_type, topic, payload)
                        values (:id, 'Stock', :agg, 'inventory.StockChanged', :topic, cast(:payload as jsonb))""")
                .param("id", eventId).param("agg", level.sku().value()).param("topic", TOPIC)
                .param("payload", json.writeValueAsString(envelope)).update();
    }
}
