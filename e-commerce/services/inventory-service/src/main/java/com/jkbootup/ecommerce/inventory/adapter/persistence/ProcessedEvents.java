package com.jkbootup.ecommerce.inventory.adapter.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/** Idempotent-consumer ledger: remembers which events a consumer has already applied. */
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time this event is seen by this consumer, inside the caller's transaction. */
    public boolean firstTime(String consumer, UUID eventId) {
        return jdbc.sql("insert into processed_events (consumer, event_id) values (:c, :e) on conflict do nothing")
                .param("c", consumer).param("e", eventId).update() == 1;
    }
}
