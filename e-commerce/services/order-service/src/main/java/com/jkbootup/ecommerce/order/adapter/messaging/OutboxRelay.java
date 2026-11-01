package com.jkbootup.ecommerce.order.adapter.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka. Runs in every replica: {@code FOR UPDATE SKIP LOCKED} gives each replica a disjoint
 * batch. A crash after sending and before marking re-sends the batch; consumers deduplicate by event id.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(UUID id, String topic, String key, String type, String payload) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    /** @return number of events published in this pass. */
    public int relayOnce() {
        Integer sent = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                            select id, topic, aggregate_id, event_type, payload::text as payload from outbox
                             where published_at is null order by created_at limit :n for update skip locked""")
                    .param("n", batchSize)
                    .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                            rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload")))
                    .list();
            if (rows.isEmpty()) return 0;
            CompletableFuture<?>[] acks = rows.stream().map(r -> {
                var record = new ProducerRecord<>(r.topic(), r.key(), r.payload());
                record.headers().add(new RecordHeader("eventId", r.id().toString().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("type", r.type().getBytes(StandardCharsets.UTF_8)));
                return kafka.send(record);
            }).toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(acks).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Kafka did not acknowledge the batch; it will be retried", e);
            }
            jdbc.sql("update outbox set published_at = now() where id in (:ids)")
                    .param("ids", rows.stream().map(Row::id).toList()).update();
            return rows.size();
        });
        return sent == null ? 0 : sent;
    }

    /** Drains the outbox; scheduled in every replica. */
    @Scheduled(fixedDelayString = "${order.outbox.poll-interval:100ms}")
    public void drain() {
        try {
            while (relayOnce() > 0) {
                // keep going while there is a backlog
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed, will retry: {}", e.getMessage());
        }
    }

    /** Deletes published rows older than 7 days; one replica only. */
    @Scheduled(cron = "${order.outbox.purge-cron:0 17 3 * * *}")
    @SchedulerLock(name = "order.purge-outbox", lockAtMostFor = "PT30M")
    public void purgePublished() {
        int n = jdbc.sql("delete from outbox where published_at < now() - interval '7 days'").update();
        log.info("Purged {} published outbox rows", n);
    }
}
