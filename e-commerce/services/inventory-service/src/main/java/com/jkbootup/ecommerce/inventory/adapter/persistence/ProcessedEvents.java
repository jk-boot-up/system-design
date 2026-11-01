package com.jkbootup.ecommerce.inventory.adapter.persistence;

import com.jkbootup.ecommerce.inventory.application.port.ProcessedEventLedger;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/** PostgreSQL implementation of the idempotent-consumer ledger. */
public class ProcessedEvents implements ProcessedEventLedger {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean firstTime(String consumer, UUID eventId) {
        return jdbc.sql("insert into processed_events (consumer, event_id) values (:c, :e) on conflict do nothing")
                .param("c", consumer).param("e", eventId).update() == 1;
    }
}
