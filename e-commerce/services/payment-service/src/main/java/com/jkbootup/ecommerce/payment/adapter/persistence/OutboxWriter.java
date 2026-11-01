package com.jkbootup.ecommerce.payment.adapter.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Writes events into the outbox table inside the caller's transaction, wrapped in the standard envelope. */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(String topic, String aggregateType, String aggregateId, String type, Map<String, Object> payload) {
        UUID eventId = UuidV7.generate(Instant.now());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("payload", payload);
        jdbc.sql("""
                        insert into outbox (id, aggregate_type, aggregate_id, event_type, topic, payload)
                        values (:id, :at, :agg, :type, :topic, cast(:payload as jsonb))""")
                .param("id", eventId).param("at", aggregateType).param("agg", aggregateId).param("type", type)
                .param("topic", topic).param("payload", json.writeValueAsString(envelope)).update();
    }
}
