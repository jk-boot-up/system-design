package com.jkbootup.ecommerce.catalog.adapter.messaging;

import com.jkbootup.ecommerce.catalog.observability.TraceContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Scope;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes outbox rows to Kafka. Runs in every replica: {@code FOR UPDATE SKIP LOCKED} gives each replica a disjoint
 * batch. A crash after sending and before marking re-sends the batch; consumers deduplicate by event id.
 *
 * <p>Every row Kafka acknowledged is marked published, even when others in the same batch failed: re-sending the whole
 * batch would duplicate the acknowledged rows on every pass, and a broker that keeps rejecting one partition would turn
 * a few hundred events into millions. Rows of one aggregate share a key, hence a partition, so they fail together and
 * keep their order. After a pass with failures the relay backs off (doubling up to {@link Backoff#MAX}).
 *
 * <p>Each event is sent inside the trace of the request that wrote it ({@code outbox.traceparent}). Metrics:
 * {@code outbox_unpublished} and {@code outbox_oldest_unpublished_age_seconds} (SPEC §8.2 alerts when the age passes 30 s)
 * and {@code outbox_events_published_total{type}}, which doubles as the business event rate (orders, payments, stock).
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** How long a pass waits for Kafka's acknowledgements before treating the rest as failed. */
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private record Row(UUID id, String topic, String key, String type, String payload, String traceparent) {}

    /** The outcome of waiting for a batch's acknowledgements: what was sent, and how many failed and why. */
    record Settled<T>(List<T> sent, int failed, Throwable firstFailure) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Backoff backoff = new Backoff(Clock.systemUTC());
    private final MeterRegistry meters;
    private final ConcurrentMap<String, Counter> published = new ConcurrentHashMap<>();

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, int batchSize,
                       MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.meters = meters;
        Gauge.builder("outbox.unpublished", this, r -> r.backlog("count(*)"))
                .description("Outbox rows not yet published to Kafka").register(meters);
        Gauge.builder("outbox.oldest.unpublished.age", this, r -> r.backlog("coalesce(extract(epoch from now() - min(created_at)), 0)"))
                .description("Age of the oldest outbox row not yet published").baseUnit("seconds").register(meters);
    }

    /**
     * One batch: send, wait, mark the acknowledged rows.
     *
     * @return number of events published in this pass
     * @throws IllegalStateException when any row of the batch was not acknowledged (after marking the ones that were)
     */
    public int relayOnce() {
        Settled<Row> settled = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                            select id, topic, aggregate_id, event_type, payload::text as payload, traceparent from outbox
                             where published_at is null order by created_at limit :n for update skip locked""")
                    .param("n", batchSize)
                    .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                            rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                            rs.getString("traceparent")))
                    .list();
            if (rows.isEmpty()) return new Settled<Row>(List.of(), 0, null);
            List<CompletableFuture<?>> acks = rows.stream().<CompletableFuture<?>>map(r -> {
                var record = new ProducerRecord<>(r.topic(), r.key(), r.payload());
                record.headers().add(new RecordHeader("eventId", r.id().toString().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("type", r.type().getBytes(StandardCharsets.UTF_8)));
                try (Scope ignored = TraceContext.fromTraceparent(r.traceparent()).makeCurrent()) {
                    return kafka.send(record);   // the producer span is a child of the writer's span
                }
            }).toList();
            Settled<Row> result = settle(rows, acks, ACK_TIMEOUT);
            if (!result.sent().isEmpty()) {
                jdbc.sql("update outbox set published_at = now() where id in (:ids)")
                        .param("ids", result.sent().stream().map(Row::id).toList()).update();
                result.sent().forEach(r -> published.computeIfAbsent(r.type(), t -> Counter.builder("outbox.events.published")
                        .description("Events published to Kafka, by type").tag("type", t).register(meters)).increment());
            }
            return result;
        });
        if (settled == null) return 0;
        if (settled.failed() > 0) {
            throw new IllegalStateException("Kafka did not acknowledge %d of %d events (%d published): %s".formatted(
                    settled.failed(), settled.failed() + settled.sent().size(), settled.sent().size(),
                    rootMessage(settled.firstFailure())), settled.firstFailure());
        }
        return settled.sent().size();
    }

    /** Drains the outbox; scheduled in every replica. Skips its turns while backing off after a failure. */
    @Scheduled(fixedDelayString = "${catalog.outbox.poll-interval:100ms}")
    public void drain() {
        if (!backoff.ready()) return;
        try {
            while (relayOnce() >= batchSize) {
                // a full batch: there is probably more
            }
            backoff.succeeded();
        } catch (RuntimeException e) {
            Duration wait = backoff.failed();
            log.warn("Outbox relay pass failed, retrying in {} ms: {}", wait.toMillis(), e.getMessage());
        }
    }

    /** Deletes published rows older than 7 days; one replica only. */
    @Scheduled(cron = "${catalog.outbox.purge-cron:0 17 3 * * *}")
    @SchedulerLock(name = "catalog.purge-outbox", lockAtMostFor = "PT30M")
    public void purgePublished() {
        int n = jdbc.sql("delete from outbox where published_at < now() - interval '7 days'").update();
        log.info("Purged {} published outbox rows", n);
    }

    /** A figure about unpublished rows, read when metrics are scraped; NaN when the database cannot be reached. */
    private double backlog(String expression) {
        try {
            return jdbc.sql("select " + expression + " from outbox where published_at is null").query(Double.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Waits (in total at most {@code timeout}) for each acknowledgement and splits the items into sent and failed. */
    static <T> Settled<T> settle(List<T> items, List<? extends CompletableFuture<?>> acks, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<T> sent = new ArrayList<>(items.size());
        int failed = 0;
        Throwable first = null;
        for (int i = 0; i < items.size(); i++) {
            try {
                acks.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                sent.add(items.get(i));
            } catch (ExecutionException | TimeoutException e) {
                failed++;
                if (first == null) first = e instanceof ExecutionException ? e.getCause() : e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed += items.size() - i;
                if (first == null) first = e;
                break;
            }
        }
        return new Settled<>(sent, failed, first);
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown";
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** Exponential backoff after failed passes: 1 s, 2 s, 4 s … at most {@link #MAX}; reset by a clean pass. */
    static final class Backoff {
        static final Duration FIRST = Duration.ofSeconds(1);
        static final Duration MAX = Duration.ofSeconds(30);

        private final Clock clock;
        private Duration current = Duration.ZERO;
        private Instant next = Instant.MIN;

        Backoff(Clock clock) {
            this.clock = clock;
        }

        boolean ready() {
            return !clock.instant().isBefore(next);
        }

        Duration failed() {
            current = current.isZero() ? FIRST : current.multipliedBy(2);
            if (current.compareTo(MAX) > 0) current = MAX;
            next = clock.instant().plus(current);
            return current;
        }

        void succeeded() {
            current = Duration.ZERO;
            next = Instant.MIN;
        }
    }
}
